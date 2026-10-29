package dev.zakocoder.app.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

object OwnedProcesses {
    private data class Identity(val pid: Int, val parent: Int, val start: String)
    private fun identity(pid: Int): Identity? = try {
        val directory = File("/proc/$pid")
        if (Os.stat(directory.path).st_uid != Process.myUid()) null else {
            val text = File(directory, "stat").readText()
            val fields = text.substring(text.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
            Identity(pid, fields[1].toInt(), fields[19])
        }
    } // A process can disappear after stat succeeds but while its /proc record is read.
    // An unreadable identity never grants authority to signal that PID.
    catch (_: java.io.IOException) { null }
      catch (_: android.system.ErrnoException) { null }

    /** Android kernels may omit task/<pid>/children. Use PPid from our own UID's
     * /proc records instead; neither process names nor another project's roots confer ownership.
     */
    fun terminate(pid: Int) {
        if (pid <= 0) return
        val root = identity(pid) ?: return
        val owned = File("/proc").listFiles().orEmpty().mapNotNull { file -> file.name.toIntOrNull()?.let(::identity) }
        val children = mutableListOf<Identity>()
        val visited = mutableSetOf(pid)
        fun visit(parent: Int) {
            owned.filter { it.parent == parent }.forEach { child ->
                if (visited.size < 1024 && visited.add(child.pid)) { visit(child.pid); children += child }
            }
        }
        visit(pid)
        fun signal(process: Identity, value: Int) {
            // PID reuse must not turn a stale identity into authority over a new process.
            if (identity(process.pid) != process) return
            try { Os.kill(process.pid, value) }
            catch (error: android.system.ErrnoException) {
                if (error.errno != OsConstants.ESRCH) Log.w("ProcessCleanup", "Unable to stop owned process", error)
            }
        }
        children.forEach { signal(it, OsConstants.SIGKILL) }
        // Let the PRoot tracer reap its killed tracees before terminating the tracer.
        signal(root, OsConstants.SIGTERM)
        val deadline = System.nanoTime() + 500_000_000L
        while (System.nanoTime() < deadline && identity(pid) == root) Thread.sleep(20)
        signal(root, OsConstants.SIGKILL)
    }
}

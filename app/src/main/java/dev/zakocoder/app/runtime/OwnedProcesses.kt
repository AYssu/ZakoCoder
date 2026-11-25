package dev.zakocoder.app.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

object OwnedProcesses {
    /** Capture immediately after launch; the start time prevents later PID reuse from granting kill authority. */
    class Identity internal constructor(internal val pid: Int, internal val parent: Int, internal val startTime: String)

    private data class Snapshot(val pid: Int, val parent: Int, val startTime: String)

    private fun snapshot(pid: Int): Snapshot? = try {
        val directory = File("/proc/$pid")
        if (Os.stat(directory.path).st_uid != Process.myUid()) null else {
            val text = File(directory, "stat").readText()
            val fields = text.substring(text.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
            Snapshot(pid, fields[1].toInt(), fields[19])
        }
    } // A process can disappear after stat succeeds while its /proc record is read.
    catch (_: java.io.IOException) { null }
    catch (_: android.system.ErrnoException) { null }
    catch (_: RuntimeException) { null }

    fun capture(pid: Int): Identity? {
        if (pid <= 0) return null
        return snapshot(pid)?.let { Identity(it.pid, it.parent, it.startTime) }
    }

    fun matches(identity: Identity): Boolean = snapshot(identity.pid)?.startTime == identity.startTime

    /** Compatibility entry point for existing callers; launch paths must capture identity immediately. */
    fun terminate(pid: Int): Boolean {
        val identity = capture(pid) ?: return false
        return terminate(identity)
    }

    /** Kill only the token's process tree after rechecking the start time; false means authority was stale. */
    fun terminate(identity: Identity): Boolean {
        if (identity.pid <= 0) return false
        val root = snapshot(identity.pid) ?: return false
        if (root.startTime != identity.startTime) return false

        // Android may omit task/PID/children, so build the tree only from same-UID PPid records.
        val owned = File("/proc").listFiles().orEmpty().mapNotNull { file -> file.name.toIntOrNull()?.let(::snapshot) }
        val children = mutableListOf<Snapshot>()
        val visited = mutableSetOf(identity.pid)
        fun visit(parent: Int) {
            owned.filter { it.parent == parent }.forEach { child ->
                if (visited.size < 1024 && visited.add(child.pid)) {
                    visit(child.pid)
                    children += child
                }
            }
        }
        visit(identity.pid)

        fun signal(process: Snapshot, value: Int) {
            if (snapshot(process.pid)?.startTime != process.startTime) return
            try { Os.kill(process.pid, value) }
            catch (error: android.system.ErrnoException) {
                if (error.errno != OsConstants.ESRCH) Log.w("ProcessCleanup", "Unable to stop owned process", error)
            }
        }

        children.forEach { signal(it, OsConstants.SIGKILL) }
        // Let the PRoot tracer reap its killed tracees before terminating the tracer.
        signal(root, OsConstants.SIGTERM)
        val deadline = System.nanoTime() + 500_000_000L
        try {
            while (System.nanoTime() < deadline && snapshot(identity.pid)?.startTime == identity.startTime) Thread.sleep(20)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        signal(root, OsConstants.SIGKILL)
        return true
    }
}

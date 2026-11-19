package dev.zakocoder.app.device

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Output is deliberately bounded; this is a device command, never a PRoot guest command. */
data class DeviceShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
    val uid: Int,
    val identity: String,
    val truncated: Boolean = false,
)

/** Owns only sessions started by this instance. No commands or output are sent to public logcat. */
internal class DeviceShell(private val context: Context) {
    private val executor = Executors.newCachedThreadPool { job ->
        Thread(job, "ZakoDeviceShell").apply { isDaemon = true }
    }
    private val active = ConcurrentHashMap.newKeySet<Session>()

    suspend fun execute(
        root: Boolean,
        command: String,
        timeoutSeconds: Int,
        onOutput: (String) -> Unit,
        isAllowed: () -> Boolean = { true },
    ): DeviceShellResult {
        require(command.isNotBlank() && command.length <= 256 * 1024) { "device_shell_invalid_command" }
        require(timeoutSeconds in 1..3600) { "device_shell_invalid_timeout" }
        val session = Session(root, command, onOutput)
        active += session
        try {
            // Register before checking the lease so revoke cannot miss a newly starting session.
            check(isAllowed()) { "device_access_revoked" }
            return withTimeoutOrNull(timeoutSeconds * 1000L) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { session.cancel() }
                    executor.execute {
                        try {
                            val result = session.run()
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            active -= session
                            session.close()
                        }
                    }
                }
            } ?: session.snapshot(-1, timedOut = true)
        } finally {
            session.cancel()
        }
    }

    fun stop() { active.toList().forEach { it.cancel() } }

    private inner class Session(
        private val root: Boolean,
        private val command: String,
        private val onOutput: (String) -> Unit,
    ) {
        private val cancelled = AtomicBoolean(false)
        private val cleanupScheduled = AtomicBoolean(false)
        private val stdout = BoundedText()
        private val stderr = BoundedText()
        private val callbackLock = Any()
        private val pidFile = File(context.cacheDir, "device-shell-${UUID.randomUUID()}.pid")
        @Volatile private var process: Process? = null
        @Volatile private var shell: Shell? = null
        @Volatile private var actualUid = if (root) -1 else AndroidProcess.myUid()

        fun run(): DeviceShellResult {
            if (cancelled.get()) throw CancellationException("device_shell_stopped")
            check(pidFile.createNewFile()) { "device_shell_lease_failed" }
            val owned = ProcessBuilder(if (root) listOf("su") else listOf("/system/bin/sh"))
                .directory(File("/"))
                .start()
            process = owned
            if (cancelled.get()) { owned.destroy(); throw CancellationException("device_shell_stopped") }
            val built = Shell.Builder.create().setContext(context).setTimeout(20).build(owned)
            shell = built
            val identity = built.newJob().add("/system/bin/id -u").to(ArrayList<String>()).exec()
            actualUid = identity.out.firstOrNull()?.trim()?.toIntOrNull() ?: -1
            check(identity.code == 0 && actualUid >= 0 && (!root || built.isRoot && actualUid == 0)) {
                "device_shell_identity_failed"
            }
            if (!root) check(actualUid == AndroidProcess.myUid()) { "device_shell_unexpected_identity" }
            if (cancelled.get()) throw CancellationException("device_shell_stopped")
            // A new session isolates the command's process group. Cancellation targets this lease only.
            val childScript = "umask 077\n" +
                "zako_stat=\$(cat /proc/\$\$/stat); zako_stat=\${zako_stat##*) }; set -- \$zako_stat; shift 19\n" +
                "printf '%s %s' \"\$\$\" \"\$1\" > ${quote(pidFile.absolutePath)}\n" +
                "exec /system/bin/sh -c ${quote(command)}"
            built.execTask { input, output, errors ->
                input.write(("exec /system/bin/setsid -w /system/bin/sh -c ${quote(childScript)}\n").toByteArray(Charsets.UTF_8))
                input.flush()
                val outReader = executor.submit { consume(output, stdout) }
                val errReader = executor.submit { consume(errors, stderr) }
                outReader.get()
                errReader.get()
            }
            return snapshot(owned.waitFor())
        }

        private fun consume(stream: InputStream, destination: BoundedText) {
            val reader = InputStreamReader(stream, Charsets.UTF_8)
            val buffer = CharArray(4096)
            while (!cancelled.get()) {
                val count = reader.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count)
                destination.append(chunk)
                synchronized(callbackLock) { if (!cancelled.get()) onOutput(chunk) }
            }
        }

        fun snapshot(code: Int, timedOut: Boolean = false) = DeviceShellResult(
            stdout.text(), stderr.text(), code, timedOut, actualUid,
            if (root) "android_root" else "android_app", stdout.truncated || stderr.truncated,
        )

        fun cancel() {
            cancelled.set(true)
            if (!cleanupScheduled.compareAndSet(false, true)) return
            executor.execute {
                try {
                    // Cancellation may arrive just before the child publishes its process group.
                    var lease: List<String>? = null
                    repeat(15) {
                        if (lease == null) {
                            lease = runCatching { pidFile.readText().trim().split(' ').takeIf { it.size == 2 } }.getOrNull()
                            if (lease == null && process != null) Thread.sleep(20)
                        }
                    }
                    val ownedGroup = lease?.get(0)?.toIntOrNull()
                    val startTime = lease?.get(1)?.toLongOrNull()
                    if (ownedGroup != null && ownedGroup > 1 && startTime != null) {
                        if (root) {
                            // Verify the leader's creation time before EACH signal: a completed
                            // command's numeric PID may already have been reused by another process.
                            val checkOwner = "zako_owned() { " +
                                "s=\$(cat /proc/$ownedGroup/stat 2>/dev/null); " +
                                "[ -z \"\$s\" ] && return 0; s=\${s##*) }; set -- \$s; shift 19; " +
                                "[ \"\$1\" = $startTime ]; }; "
                            val killer = ProcessBuilder("su", "-c", checkOwner +
                                "zako_owned && kill -TERM -- -$ownedGroup; sleep 0.15; " +
                                "zako_owned && kill -KILL -- -$ownedGroup")
                                .redirectErrorStream(true).start()
                            if (!killer.waitFor(2, TimeUnit.SECONDS)) killer.destroy()
                        } else {
                            fun stillOwned(): Boolean {
                                val stat = File("/proc/$ownedGroup/stat")
                                if (!stat.exists()) return true // Leader exited; children can still own the group.
                                return runCatching {
                                    stat.readText().substringAfterLast(") ").split(' ')[19].toLong() == startTime
                                }.getOrDefault(false)
                            }
                            if (stillOwned()) runCatching { Os.kill(-ownedGroup, OsConstants.SIGTERM) }
                            Thread.sleep(150)
                            if (stillOwned()) runCatching { Os.kill(-ownedGroup, OsConstants.SIGKILL) }
                        }
                    }
                } finally {
                    process?.destroy()
                    runCatching { shell?.close() }
                    pidFile.delete()
                }
            }
        }

        fun close() {
            // Run the same cleanup even on normal completion: a command may have started children.
            cancel()
        }
    }

    private class BoundedText {
        private val value = StringBuilder()
        @Volatile var truncated = false
            private set
        @Synchronized fun append(text: String) {
            val remaining = MAX_OUTPUT_CHARS - value.length
            if (text.length > remaining) truncated = true
            if (remaining > 0) value.append(text, 0, minOf(text.length, remaining))
        }
        @Synchronized fun text(): String = value.toString()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 256 * 1024
        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

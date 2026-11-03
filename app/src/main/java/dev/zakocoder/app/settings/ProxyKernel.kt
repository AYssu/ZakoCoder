package dev.zakocoder.app.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface ProxyKernelState {
    data object Stopped : ProxyKernelState
    data class Starting(val nodeId: String) : ProxyKernelState
    data class Running(val nodeId: String, val nodeName: String, val port: Int) : ProxyKernelState
    data class Failed(val reason: KernelFailure) : ProxyKernelState
}

/** UI-safe failure categories; the kernel log/config is never exposed here. */
enum class KernelFailure { BinaryMissing, UnsupportedNode, StartFailed, Timeout, PortUnavailable }

/**
 * Owns one Xray-core child process for VMess/SS/Trojan nodes.
 *
 * Contract:
 * - the app never talks to the remote node directly for these protocols: the kernel exposes a
 *   loopback SOCKS5 inbound and the app's own proxy setting is pointed at it by [Workbench];
 * - the inbound requires a per-start random RFC 1929 credential, so another app on the device
 *   cannot scan the loopback port and use the user's node. The credential never enters a state
 *   value or a log line; [localProxyUrl] is the only accessor and is consumed by settings;
 * - start is serialized and always stops the previous kernel first. A generation token makes
 *   `stop()` win over an in-flight start, so a stopped kernel can never be resurrected as Running;
 * - failures leave no process behind and only expose a [KernelFailure] category (never the
 *   generated config, credentials or raw log lines);
 * - the config (which contains credentials) lives in the app's private cache dir and is deleted on
 *   stop; stale config files and the previous log are removed on construction. The kernel inherits
 *   `SSL_CERT_DIR=/system/etc/security/cacerts` because a CGO_ENABLED=0 Go binary on Android has no
 *   system trust store of its own (TLS nodes would otherwise fail with x509 errors);
 * - readiness is a SOCKS5 greeting + password handshake against the loopback inbound, capped by
 *   [READY_TIMEOUT_MS].
 */
class ProxyKernel(
    context: Context,
    private val executable: () -> File? = { File(context.applicationInfo.nativeLibraryDir, "libzakocoder_xray.so") },
) {
    private val appContext = context.applicationContext
    private val mutable = MutableStateFlow<ProxyKernelState>(ProxyKernelState.Stopped)
    val state: StateFlow<ProxyKernelState> = mutable.asStateFlow()
    private val startMutex = Mutex()
    private val guard = Any()
    private var process: Process? = null
    private var configFile: File? = null
    private var generation = 0L
    private var socksUser: String? = null
    private var socksPassword: String? = null
    private val workDirectory = File(appContext.cacheDir, "proxy-kernel")
    private val logDirectory = File(appContext.noBackupFilesDir, "kernel-logs")

    init {
        // Crash/force-stop leaves the plaintext config (and the kernel log) behind; clean it before
        // any new start so secrets do not survive an app death in the cache dir.
        cleanupStaleFiles()
    }

    suspend fun start(node: ProxyNode): ProxyKernelState = startMutex.withLock {
        val token = synchronized(guard) { ++generation }
        mutable.value = ProxyKernelState.Starting(node.id)
        withContext(Dispatchers.IO) { startLocked(node, token) }
    }

    /** Stops the running kernel (if any) and returns to [ProxyKernelState.Stopped]. Non-blocking. */
    fun stop() {
        val stopped = synchronized(guard) {
            generation++
            val old = process
            process = null
            configFile?.delete()
            configFile = null
            socksUser = null
            socksPassword = null
            old
        }
        mutable.value = ProxyKernelState.Stopped
        if (stopped != null) {
            thread(name = "ProxyKernelStop", isDaemon = true) {
                runCatching {
                    stopped.destroy()
                    if (!stopped.waitFor(2, TimeUnit.SECONDS)) stopped.destroyForcibly()
                }
            }
        }
    }

    /** Full proxy URL (random SOCKS credentials included) for the running kernel, or null. */
    fun localProxyUrl(): String? = synchronized(guard) {
        val running = mutable.value as? ProxyKernelState.Running ?: return null
        val user = socksUser ?: return null
        val password = socksPassword ?: return null
        "socks5://${encodeUserInfo(user)}:${encodeUserInfo(password)}@127.0.0.1:${running.port}"
    }

    private fun isCurrent(token: Long): Boolean = synchronized(guard) { generation == token }

    private fun startLocked(node: ProxyNode, token: Long): ProxyKernelState {
        stopProcess()
        if (!node.viaKernel) return fail(KernelFailure.UnsupportedNode, token)
        val binary = executable()
        if (binary == null || !binary.isFile) return fail(KernelFailure.BinaryMissing, token)
        val port = freePort() ?: return fail(KernelFailure.PortUnavailable, token)
        val user = randomCredential()
        val password = randomCredential()
        val json = ProxyKernelConfig.build(node, port, user, password) ?: return fail(KernelFailure.UnsupportedNode, token)
        return try {
            workDirectory.mkdirs()
            cleanupStaleFiles()
            if (!isCurrent(token)) return ProxyKernelState.Stopped
            val target = File(workDirectory, "$CONFIG_PREFIX${safeNodeId(node.id)}.json")
            target.writeText(json)
            configFile = target
            val logFile = File(workDirectory, KERNEL_LOG)
            val started = ProcessBuilder(binary.absolutePath, "run", "-c", target.absolutePath)
                .directory(workDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .apply {
                    // Android stores the system CA bundle here; a static Go binary cannot find it on
                    // its own, and without this every TLS node fails with x509 errors.
                    environment()["SSL_CERT_DIR"] = SYSTEM_CA_DIR
                }
                .start()
            val accepted = synchronized(guard) {
                if (generation != token) {
                    false
                } else {
                    process = started
                    configFile = target
                    socksUser = user
                    socksPassword = password
                    true
                }
            }
            if (!accepted) {
                kill(started)
                return ProxyKernelState.Stopped
            }
            waitReady(node, started, port, user, password, token)
        } catch (_: Exception) {
            fail(KernelFailure.StartFailed, token)
        }
    }

    private fun waitReady(node: ProxyNode, started: Process, port: Int, user: String, password: String, token: Long): ProxyKernelState {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isCurrent(token)) {
                kill(started)
                return ProxyKernelState.Stopped
            }
            if (!started.isAlive) return fail(KernelFailure.StartFailed, token)
            if (socksReady(port, user, password)) {
                return synchronized(guard) {
                    if (generation != token) {
                        kill(started)
                        ProxyKernelState.Stopped
                    } else {
                        val running = ProxyKernelState.Running(node.id, node.name.take(80), port)
                        mutable.value = running
                        running
                    }
                }
            }
            try {
                Thread.sleep(READY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return fail(KernelFailure.Timeout, token)
    }

    private fun fail(reason: KernelFailure, token: Long): ProxyKernelState {
        if (!isCurrent(token)) {
            // stop() won the race; do not turn Stopped into Failed.
            return ProxyKernelState.Stopped
        }
        archiveLog()
        stopProcess()
        val failed = ProxyKernelState.Failed(reason)
        mutable.value = failed
        return failed
    }

    /** Blocking cleanup used from the IO start path; the public [stop] is the non-blocking variant. */
    private fun stopProcess() {
        synchronized(guard) {
            process?.let { running ->
                runCatching {
                    running.destroy()
                    if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly()
                }
            }
            process = null
            configFile?.delete()
            configFile = null
            socksUser = null
            socksPassword = null
        }
    }

    private fun kill(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun freePort(): Int? = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        null
    }

    /** Full SOCKS5 no-auth-method + password handshake; proves the credential-gated inbound is up. */
    private fun socksReady(port: Int, user: String, password: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(byteArrayOf(5, 1, 2))
                output.flush()
                val greeting = ByteArray(2)
                if (input.read(greeting) != 2 || greeting[0].toInt() != 5 || greeting[1].toInt() != 2) {
                    false
                } else {
                    val userBytes = user.toByteArray(Charsets.UTF_8)
                    val passwordBytes = password.toByteArray(Charsets.UTF_8)
                    output.write(
                        byteArrayOf(1, userBytes.size.toByte()) + userBytes +
                            byteArrayOf(passwordBytes.size.toByte()) + passwordBytes,
                    )
                    output.flush()
                    val auth = ByteArray(2)
                    input.read(auth) == 2 && auth[0].toInt() == 1 && auth[1].toInt() == 0
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun randomCredential(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return buildString(CREDENTIAL_LENGTH) {
            repeat(CREDENTIAL_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    /** Removes stale plaintext configs and the previous kernel log from a crash/force-stop. */
    private fun cleanupStaleFiles() {
        runCatching {
            workDirectory.listFiles()?.forEach { file ->
                if (file.name.startsWith(CONFIG_PREFIX) || file.name == KERNEL_LOG) file.delete()
            }
        }
    }

    /** Copies only the tail of the kernel log to noBackupFilesDir; the full log is never shown. */
    private fun archiveLog() {
        try {
            val source = File(workDirectory, KERNEL_LOG)
            if (!source.isFile) return
            logDirectory.mkdirs()
            RandomAccessFile(source, "r").use { input ->
                val length = input.length()
                val start = (length - MAX_LOG_BYTES).coerceAtLeast(0)
                input.seek(start)
                val bytes = ByteArray((length - start).toInt())
                input.readFully(bytes)
                File(logDirectory, "kernel.log").writeBytes(bytes)
            }
        } catch (_: Exception) {
            // Diagnostics are best-effort and must never break kernel lifecycle handling.
        }
    }

    private fun safeNodeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "node" }

    private companion object {
        const val READY_TIMEOUT_MS = 10_000L
        const val READY_POLL_MS = 100L
        const val CONNECT_TIMEOUT_MS = 300
        const val HANDSHAKE_TIMEOUT_MS = 800
        const val MAX_LOG_BYTES = 64L * 1024
        const val CONFIG_PREFIX = "config-"
        const val KERNEL_LOG = "kernel.log"
        const val CREDENTIAL_LENGTH = 20
        const val SYSTEM_CA_DIR = "/system/etc/security/cacerts"
    }
}

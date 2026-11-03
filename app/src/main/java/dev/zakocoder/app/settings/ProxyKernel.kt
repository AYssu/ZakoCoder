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
 * - start is serialized and always stops the previous kernel first. Failures leave no process
 *   behind and only expose a [KernelFailure] category (never the generated config, credentials or
 *   raw log lines);
 * - the config (which contains credentials) lives in the app's cache dir and is deleted on stop;
 *   kernel stdout/stderr goes to the cache dir and only a bounded tail is archived under
 *   filesDir/kernel-logs for diagnostics;
 * - readiness is a TCP connect to the loopback inbound, capped by [READY_TIMEOUT_MS].
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
    private val workDirectory = File(appContext.cacheDir, "proxy-kernel")
    private val logDirectory = File(appContext.filesDir, "kernel-logs")

    suspend fun start(node: ProxyNode): ProxyKernelState = startMutex.withLock {
        mutable.value = ProxyKernelState.Starting(node.id)
        withContext(Dispatchers.IO) { startLocked(node) }
    }

    /** Stops the running kernel (if any) and returns to [ProxyKernelState.Stopped]. Non-blocking. */
    fun stop() {
        val stopped = synchronized(guard) {
            val old = process
            process = null
            configFile?.delete()
            configFile = null
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

    private fun startLocked(node: ProxyNode): ProxyKernelState {
        stopProcess()
        if (!node.viaKernel) return fail(node, KernelFailure.UnsupportedNode)
        val binary = executable()
        if (binary == null || !binary.isFile) return fail(node, KernelFailure.BinaryMissing)
        val port = freePort() ?: return fail(node, KernelFailure.PortUnavailable)
        val json = ProxyKernelConfig.build(node, port) ?: return fail(node, KernelFailure.UnsupportedNode)
        return try {
            workDirectory.mkdirs()
            workDirectory.listFiles()?.filter { it.name.startsWith(CONFIG_PREFIX) }?.forEach { it.delete() }
            val target = File(workDirectory, "$CONFIG_PREFIX${safeNodeId(node.id)}.json")
            target.writeText(json)
            configFile = target
            val logFile = File(workDirectory, KERNEL_LOG)
            val started = ProcessBuilder(binary.absolutePath, "run", "-c", target.absolutePath)
                .directory(workDirectory)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()
            synchronized(guard) { process = started }
            waitReady(node, started, port)
        } catch (_: Exception) {
            fail(node, KernelFailure.StartFailed)
        }
    }

    private fun waitReady(node: ProxyNode, started: Process, port: Int): ProxyKernelState {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) return fail(node, KernelFailure.StartFailed)
            if (canConnect(port)) {
                val running = ProxyKernelState.Running(node.id, node.name.take(80), port)
                mutable.value = running
                return running
            }
            try {
                Thread.sleep(READY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return fail(node, KernelFailure.Timeout)
    }

    private fun fail(node: ProxyNode, reason: KernelFailure): ProxyKernelState {
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
        }
    }

    private fun freePort(): Int? = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        null
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    /** Copies only the tail of the kernel log to filesDir; the full log can be large and is never shown. */
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
        const val MAX_LOG_BYTES = 64L * 1024
        const val CONFIG_PREFIX = "config-"
        const val KERNEL_LOG = "kernel.log"
    }
}

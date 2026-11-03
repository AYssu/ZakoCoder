package dev.zakocoder.app.settings

import android.content.Context
import dev.zakocoder.app.kernel.IKernelService
import dev.zakocoder.app.kernel.KernelPluginManager
import dev.zakocoder.app.kernel.KernelPluginStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

sealed interface ProxyKernelState {
    data object Stopped : ProxyKernelState
    data class Starting(val nodeId: String) : ProxyKernelState
    data class Running(val nodeId: String, val nodeName: String, val port: Int) : ProxyKernelState
    data class Failed(val reason: KernelFailure) : ProxyKernelState
}

/**
 * UI-safe failure categories. The main app no longer executes Xray itself: it either lacks the
 * optional plugin ([PluginNotInstalled]), finds it untrusted/too old ([PluginIncompatible]) or the
 * plugin's own start failed ([BinaryMissing]/[UnsupportedNode]/[Timeout]/[PortUnavailable]/
 * [StartFailed]). Configs, credentials and plugin log lines never appear in this state.
 */
enum class KernelFailure {
    BinaryMissing,
    UnsupportedNode,
    StartFailed,
    Timeout,
    PortUnavailable,
    PluginNotInstalled,
    PluginIncompatible,
    ServiceUnavailable,
}

/**
 * Client for the optional Xray kernel plugin.
 *
 * The main APK does not package or execute the kernel. [start] validates the installed plugin
 * through [KernelPluginManager] (status already reflects the compiled-in signer pin), generates the
 * Xray JSON with [ProxyKernelConfig], binds the signature-protected service and calls its AIDL
 * `start`. The plugin starts the child process and exposes a loopback SOCKS5 inbound with a random
 * per-start RFC 1929 credential; [localProxyUrl] is the only accessor of that credential.
 *
 * A generation token keeps `stop()` winning over an in-flight `start`; binder death turns a running
 * kernel into [KernelFailure.StartFailed] instead of silently keeping a dead endpoint. Legacy
 * in-process kernel files (plaintext configs/logs) from older builds are removed on construction.
 */
class ProxyKernel(
    context: Context,
    private val plugins: KernelPluginManager = KernelPluginManager(context),
) {
    private val appContext = context.applicationContext
    private val mutable = MutableStateFlow<ProxyKernelState>(ProxyKernelState.Stopped)
    val state: StateFlow<ProxyKernelState> = mutable.asStateFlow()
    private val startMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = Any()
    private var generation = 0L
    private var service: IKernelService? = null
    private var socksUser: String? = null
    private var socksPassword: String? = null

    init {
        plugins.onServiceDied = { handleServiceDied() }
        // Legacy in-process builds kept plaintext node configs and host logs in the app's cache;
        // the plugin owns its own private copies now, so remove the old ones.
        runCatching { appContext.cacheDir?.let { File(it, LEGACY_CACHE_DIR).deleteRecursively() } }
        runCatching { File(appContext.noBackupFilesDir, LEGACY_LOG_DIR).deleteRecursively() }
    }

    suspend fun start(node: ProxyNode): ProxyKernelState = startMutex.withLock {
        val token = synchronized(guard) { ++generation }
        mutable.value = ProxyKernelState.Starting(node.id)
        withContext(Dispatchers.IO) { startLocked(node, token) }
    }

    /** Stops the plugin kernel (if any) and returns to [ProxyKernelState.Stopped]; never blocks UI. */
    fun stop() {
        val pending = synchronized(guard) {
            generation++
            val binding = service
            service = null
            socksUser = null
            socksPassword = null
            binding
        }
        mutable.value = ProxyKernelState.Stopped
        // Serialize the remote stop/unbind with starts so a slow plugin stop cannot unbind a new bind.
        ioScope.launch {
            startMutex.withLock {
                runCatching { pending?.stop() }
                plugins.unbind()
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
        if (!isCurrent(token)) return ProxyKernelState.Stopped
        plugins.refresh()
        when (plugins.status.value) {
            KernelPluginStatus.NotInstalled -> return fail(KernelFailure.PluginNotInstalled, token)
            KernelPluginStatus.Incompatible -> return fail(KernelFailure.PluginIncompatible, token)
            is KernelPluginStatus.Installed -> Unit
        }
        if (!node.viaKernel) return fail(KernelFailure.UnsupportedNode, token)
        val port = freePort() ?: return fail(KernelFailure.PortUnavailable, token)
        val user = randomCredential()
        val password = randomCredential()
        val config = ProxyKernelConfig.build(node, port, user, password) ?: return fail(KernelFailure.UnsupportedNode, token)
        if (!isCurrent(token)) return ProxyKernelState.Stopped
        val bound = plugins.bind() ?: run {
            val status = plugins.status.value
            return fail(
                when (status) {
                    KernelPluginStatus.NotInstalled -> KernelFailure.PluginNotInstalled
                    KernelPluginStatus.Incompatible -> KernelFailure.PluginIncompatible
                    is KernelPluginStatus.Installed -> KernelFailure.ServiceUnavailable
                },
                token,
            )
        }
        if (!isCurrent(token)) {
            plugins.unbind()
            return ProxyKernelState.Stopped
        }
        val reply = try {
            bound.start(config, port, user, password, node.id, node.name.take(80))
        } catch (_: SecurityException) {
            plugins.unbind()
            return fail(KernelFailure.PluginIncompatible, token)
        } catch (_: android.os.RemoteException) {
            plugins.unbind()
            return fail(KernelFailure.ServiceUnavailable, token)
        } catch (_: Exception) {
            plugins.unbind()
            return fail(KernelFailure.StartFailed, token)
        }
        val outcome = parseStartReply(reply)
        if (outcome == null || !outcome.ok || outcome.port !in 1..65535) {
            runCatching { bound.stop() }
            plugins.unbind()
            return fail(outcome?.failure ?: KernelFailure.StartFailed, token)
        }
        return synchronized(guard) {
            if (generation != token) {
                runCatching { bound.stop() }
                plugins.unbind()
                ProxyKernelState.Stopped
            } else {
                service = bound
                socksUser = user
                socksPassword = password
                val running = ProxyKernelState.Running(node.id, node.name.take(80), outcome.port)
                mutable.value = running
                running
            }
        }
    }

    private fun fail(reason: KernelFailure, token: Long): ProxyKernelState {
        if (!isCurrent(token)) return ProxyKernelState.Stopped
        synchronized(guard) {
            service = null
            socksUser = null
            socksPassword = null
        }
        plugins.unbind()
        return ProxyKernelState.Failed(reason).also { mutable.value = it }
    }

    private fun handleServiceDied() {
        val wasRunning = synchronized(guard) {
            if (service == null) {
                false
            } else {
                service = null
                socksUser = null
                socksPassword = null
                generation++
                true
            }
        }
        if (wasRunning) mutable.value = ProxyKernelState.Failed(KernelFailure.ServiceUnavailable)
    }

    private data class StartReply(val ok: Boolean, val port: Int, val failure: KernelFailure)

    private fun parseStartReply(reply: String?): StartReply? = try {
        val json = JSONObject(reply ?: return null)
        StartReply(ok = json.optBoolean("ok", false), port = json.optInt("port", 0), failure = mapFailure(json.optString("error")))
    } catch (_: Exception) {
        null
    }

    private fun mapFailure(error: String?): KernelFailure = when (error?.trim()?.lowercase()) {
        "binary_missing" -> KernelFailure.BinaryMissing
        "invalid", "unsupported" -> KernelFailure.UnsupportedNode
        "timeout" -> KernelFailure.Timeout
        "port_unavailable" -> KernelFailure.PortUnavailable
        "denied" -> KernelFailure.PluginIncompatible
        else -> KernelFailure.StartFailed
    }

    private fun freePort(): Int? = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        null
    }

    private fun randomCredential(): String {
        val random = SecureRandom()
        return buildString(CREDENTIAL_LENGTH) {
            repeat(CREDENTIAL_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    private companion object {
        const val LEGACY_CACHE_DIR = "proxy-kernel"
        const val LEGACY_LOG_DIR = "kernel-logs"
        const val CREDENTIAL_LENGTH = 20
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}

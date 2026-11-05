package dev.zakocoder.app.settings

import android.content.Context
import dev.zakocoder.app.kernel.KernelBindingFailure
import dev.zakocoder.app.kernel.IKernelService
import dev.zakocoder.app.kernel.KernelPluginManager
import dev.zakocoder.app.kernel.KernelPluginStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext
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
    DnsResolutionFailed,
    EndpointUnavailable,
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
        // Legacy in-process builds kept plaintext node configs and host logs in the app's cache;
        // the plugin owns its own private copies now, so remove the old ones.
        runCatching { appContext.cacheDir?.let { File(it, LEGACY_CACHE_DIR).deleteRecursively() } }
        runCatching { File(appContext.noBackupFilesDir, LEGACY_LOG_DIR).deleteRecursively() }
    }

    suspend fun start(node: ProxyNode): ProxyKernelState = startMutex.withLock {
        val token = synchronized(guard) {
            ++generation
            mutable.value = ProxyKernelState.Starting(node.id)
            generation
        }
        try {
            withContext(Dispatchers.IO) { startLocked(node, token, coroutineContext) }
        } catch (cancelled: CancellationException) {
            stop()
            throw cancelled
        }
    }

    /** Stops the plugin kernel (if any) and returns to [ProxyKernelState.Stopped]; never blocks UI. */
    fun stop() {
        val pending = synchronized(guard) {
            generation++
            val binding = service
            service = null
            socksUser = null
            socksPassword = null
            mutable.value = ProxyKernelState.Stopped
            generation to binding
        }
        // A queued stop must not stop a newer start that acquired startMutex before this job.
        ioScope.launch {
            startMutex.withLock {
                if (!isCurrent(pending.first)) return@withLock
                runCatching { pending.second?.stop() }
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

    private fun supersededState(): ProxyKernelState = synchronized(guard) {
        mutable.value.takeIf { it is ProxyKernelState.Failed } ?: ProxyKernelState.Stopped
    }

    private fun startLocked(node: ProxyNode, token: Long, owner: CoroutineContext): ProxyKernelState {
        if (!isCurrent(token) || !owner.isActive) return supersededState()
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
        // The static Go kernel cannot use Android's resolver and otherwise queries [::1]:53.
        // Resolve only the node endpoint here; keep the original host for TLS SNI and WS headers.
        val addresses = try {
            val addresses = InetAddress.getAllByName(node.host)
            if (addresses.isEmpty()) return fail(KernelFailure.DnsResolutionFailed, token)
            addresses
        } catch (_: java.net.UnknownHostException) {
            return fail(KernelFailure.DnsResolutionFailed, token)
        } catch (_: SecurityException) {
            return fail(KernelFailure.DnsResolutionFailed, token)
        }
        val destination = ProxyEndpointAddresses.selectReachable(
            addresses.toList(),
            node.port,
            isActive = { isCurrent(token) && owner.isActive },
        )
        if (destination == null) {
            return if (!owner.isActive) supersededState() else fail(KernelFailure.EndpointUnavailable, token)
        }
        if (!isCurrent(token) || !owner.isActive) return supersededState()
        val config = ProxyKernelConfig.build(node, port, user, password, resolvedAddress = destination)
            ?: return fail(KernelFailure.UnsupportedNode, token)
        if (!isCurrent(token) || !owner.isActive) return supersededState()
        val bound = plugins.bind(onLost = { handleServiceDied(token) }) ?: run {
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
        if (!isCurrent(token) || !owner.isActive) {
            plugins.unbind()
            return supersededState()
        }
        val reply = try {
            bound.start(config, port, user, password, node.id, node.name.take(80))
        } catch (_: SecurityException) {
            plugins.recordFailure(KernelBindingFailure.SecurityDenied)
            plugins.unbind()
            return fail(KernelFailure.PluginIncompatible, token)
        } catch (_: android.os.RemoteException) {
            plugins.recordFailure(KernelBindingFailure.RemoteCallFailed)
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
        val result = synchronized(guard) {
            when {
                generation != token -> supersededState()
                !plugins.isCurrentService(bound) -> {
                    generation++
                    service = null
                    socksUser = null
                    socksPassword = null
                    ProxyKernelState.Failed(KernelFailure.ServiceUnavailable).also { mutable.value = it }
                }
                else -> {
                    service = bound
                    socksUser = user
                    socksPassword = password
                    ProxyKernelState.Running(node.id, node.name.take(80), outcome.port).also { mutable.value = it }
                }
            }
        }
        if (result !is ProxyKernelState.Running) {
            runCatching { bound.stop() }
            plugins.unbind()
        }
        return result
    }

    private fun fail(reason: KernelFailure, token: Long): ProxyKernelState {
        val result = synchronized(guard) {
            if (generation != token) return supersededState()
            service = null
            socksUser = null
            socksPassword = null
            ProxyKernelState.Failed(reason).also { mutable.value = it }
        }
        plugins.unbind()
        return result
    }

    private fun handleServiceDied(token: Long) {
        synchronized(guard) {
            if (generation == token &&
                (mutable.value is ProxyKernelState.Starting || mutable.value is ProxyKernelState.Running)) {
                service = null
                socksUser = null
                socksPassword = null
                generation++
                mutable.value = ProxyKernelState.Failed(KernelFailure.ServiceUnavailable)
            }
        }
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

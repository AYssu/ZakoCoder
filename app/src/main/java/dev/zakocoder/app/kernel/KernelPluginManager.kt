package dev.zakocoder.app.kernel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.os.RemoteException
import androidx.core.net.toUri
import dev.zakocoder.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the optional kernel plugin lifecycle: installed-status detection, signature-checked
 * install (download or SAF import), system-installer hand-off, uninstall, and a single binder
 * binding consumed by [dev.zakocoder.app.settings.ProxyKernel].
 *
 * Contract:
 * - status is derived from PackageManager metadata and the compiled-in signer digest
 *   (this app's own signing certificate); nothing is trusted from the plugin's own claims;
 * - install never happens silently: a validated APK is staged and handed to the platform installer;
 * - download/import are explicit calls on IO, bounded to [KernelPluginTrust.MAX_APK_BYTES], and
 *   cancellable (the copy loop checks the coroutine state between chunks);
 * - [refresh] is the only status-polling path (no timer); the UI calls it on entry/resume.
 */
class KernelPluginManager(context: Context) {
    private val appContext = context.applicationContext
    private val installer = KernelPluginInstaller(appContext)
    private val mutable = MutableStateFlow<KernelPluginStatus>(KernelPluginStatus.NotInstalled)
    val status: StateFlow<KernelPluginStatus> = mutable.asStateFlow()

    private val installMutex = Mutex()
    private val bindGuard = Any()
    private class Binding(var onLost: (() -> Unit)?) {
        val ready = CountDownLatch(1)
        lateinit var connection: ServiceConnection
        var attempted = false
        var finished = false
        var released = false
        var service: IKernelService? = null
        var deathRecipient: IBinder.DeathRecipient? = null
    }
    private var binding: Binding? = null
    private val mutableBindFailure = MutableStateFlow<KernelBindingFailure?>(null)
    val bindFailure: StateFlow<KernelBindingFailure?> = mutableBindFailure.asStateFlow()
    private val events = ArrayDeque<String>()

    /** Only fixed codes enter this bounded, in-memory diagnostic history. */
    fun recordFailure(failure: KernelBindingFailure) = synchronized(bindGuard) {
        mutableBindFailure.value = failure
        recordEventLocked(failure.name)
    }

    private fun recordEventLocked(code: String) {
        if (events.size >= 24) events.removeFirst()
        events.addLast("${System.currentTimeMillis()} $code")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    init {
        refresh()
    }

    /** Re-reads the installed plugin from PackageManager. Never polls; call on UI entry/resume. */
    fun refresh() {
        mutable.value = queryStatus()
    }

    /**
     * Binds the plugin service, waiting up to [timeoutMillis] for `onServiceConnected` (the proxy
     * kernel calls this from an IO thread). Returns null when the plugin is not installed, lacks the
     * signature permission, or does not answer in time.
     */
    fun bind(timeoutMillis: Long = BIND_TIMEOUT_MS, onLost: (() -> Unit)? = null): IKernelService? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Kernel binding requires a worker thread" }
        val previous: Binding?
        val pending = synchronized(bindGuard) {
            binding?.service?.takeIf { it.asBinder().isBinderAlive }?.let {
                binding?.onLost = onLost
                return it
            }
            previous = detachLocked()
            mutableBindFailure.value = null
            Binding(onLost).also { binding = it; recordEventLocked("BindRequested") }
        }
        previous?.let(::release)
        pending.connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    onBindingLost(pending, KernelBindingFailure.NullBinding)
                    return
                }
                val recipient = IBinder.DeathRecipient { onBindingLost(pending, KernelBindingFailure.BindingDied) }
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (_: RemoteException) {
                    onBindingLost(pending, KernelBindingFailure.BindingDied)
                    return
                }
                val accepted = synchronized(bindGuard) {
                    if (binding !== pending) false else {
                        pending.deathRecipient = recipient
                        pending.service = IKernelService.Stub.asInterface(binder)
                        recordEventLocked("Connected")
                        pending.ready.countDown()
                        true
                    }
                }
                if (!accepted) runCatching { binder.unlinkToDeath(recipient, 0) }
            }
            override fun onServiceDisconnected(name: ComponentName?) =
                onBindingLost(pending, KernelBindingFailure.Disconnected)
            override fun onBindingDied(name: ComponentName?) =
                onBindingLost(pending, KernelBindingFailure.BindingDied)
            override fun onNullBinding(name: ComponentName?) =
                onBindingLost(pending, KernelBindingFailure.NullBinding)
        }
        synchronized(bindGuard) {
            if (binding !== pending) return null
            pending.attempted = true
        }
        // bind/unbind and all waits happen outside the state lock, including rejected attempts.
        val failure = try {
            val accepted = appContext.bindService(
                Intent().setComponent(ComponentName(KernelPluginTrust.PLUGIN_PACKAGE, KernelPluginTrust.SERVICE_CLASS)),
                pending.connection,
                Context.BIND_AUTO_CREATE,
            )
            if (accepted) null else KernelBindingFailure.Rejected
        } catch (_: SecurityException) {
            KernelBindingFailure.SecurityDenied
        } catch (_: Exception) {
            KernelBindingFailure.Unexpected
        }
        val wait = synchronized(bindGuard) {
            pending.finished = true
            if (binding !== pending) false else if (failure != null) {
                recordFailure(failure)
                detachLocked()
                false
            } else true
        }
        if (!wait) {
            release(pending)
            return null
        }
        val interrupted = try {
            pending.ready.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            true
        }
        val result = synchronized(bindGuard) {
            if (binding !== pending) null else {
                val connected = pending.service
                if (interrupted || connected == null) {
                    recordFailure(if (interrupted) KernelBindingFailure.Interrupted else KernelBindingFailure.Timeout)
                    detachLocked()
                    null
                } else connected
            }
        }
        if (result == null) release(pending)
        return result
    }

    /** Unbind wakes pending waits. Framework cleanup never holds the state guard. */
    fun unbind() {
        synchronized(bindGuard) { detachLocked() }?.let(::release)
    }

    /** Allows a start reply to be promoted only while this exact binding is still alive. */
    fun isCurrentService(service: IKernelService): Boolean = synchronized(bindGuard) {
        binding?.service?.asBinder() === service.asBinder() && service.asBinder().isBinderAlive
    }

    /** User initiated only. An explicit foreground Activity launch can clear first-install stopped state. */
    fun openActivation(activity: Activity): KernelPluginActivationResult {
        refresh()
        when (status.value) {
            KernelPluginStatus.NotInstalled -> return KernelPluginActivationResult.NotInstalled
            KernelPluginStatus.Incompatible -> return KernelPluginActivationResult.Incompatible
            is KernelPluginStatus.Installed -> Unit
        }
        return try {
            activity.startActivity(Intent().setComponent(ComponentName(
                KernelPluginTrust.PLUGIN_PACKAGE, "dev.zakocoder.app.kernel.KernelActivationActivity",
            )))
            synchronized(bindGuard) { recordEventLocked("ActivationOpened") }
            KernelPluginActivationResult.Opened
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${KernelPluginTrust.PLUGIN_PACKAGE}".toUri()))
                KernelPluginActivationResult.SettingsOpened
            } catch (_: Exception) {
                KernelPluginActivationResult.Unavailable
            }
        }
    }

    /** White-listed metadata only: no nodes, subscriptions, config, ports or exception messages. */
    fun diagnostics(): String {
        refresh()
        val info = runCatching { appContext.packageManager.getApplicationInfo(KernelPluginTrust.PLUGIN_PACKAGE, 0) }.getOrNull()
        val installed = status.value as? KernelPluginStatus.Installed
        val remote = synchronized(bindGuard) { binding?.service }
        // Never perform Binder IPC under bindGuard or on the UI thread. Old plugins remain valid.
        val runtime = if (remote != null && Looper.myLooper() != Looper.getMainLooper()) {
            try { remote.status().takeIf { it.length <= 8192 }?.let { org.json.JSONObject(it) } }
            catch (_: Exception) { null }
        } else null
        return buildString {
            appendLine("ZakoCoder kernel diagnostic v2")
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("main_version_code=${BuildConfig.VERSION_CODE}")
            appendLine("plugin_status=${when (status.value) {
                KernelPluginStatus.NotInstalled -> "not_installed"
                KernelPluginStatus.Incompatible -> "incompatible"
                is KernelPluginStatus.Installed -> "trusted"
            }}")
            appendLine("plugin_version_code=${installed?.info?.versionCode ?: "unknown"}")
            appendLine("plugin_enabled=${info?.enabled ?: "unknown"}")
            appendLine("plugin_stopped=${info?.let { it.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0 } ?: "unknown"}")
            val runtimeStillCurrent = synchronized(bindGuard) { remote != null && binding?.service === remote }
            if (runtime != null && runtimeStillCurrent) {
                appendLine("kernel_state=${runtime.optString("state").takeIf { it in setOf("running", "stopped", "failed") } ?: "unknown"}")
                appendLine("kernel_process_alive=${if (runtime.opt("processAlive") is Boolean) runtime.optBoolean("processAlive") else "unknown"}")
                appendLine("kernel_exit_code=${(runtime.opt("exitCode") as? Number)?.toInt() ?: "unknown"}")
                val allowed = setOf("unavailable", "none_observed", "snapshot_changed", "reality_auth_failed",
                    "certificate_time_invalid", "certificate_untrusted", "connection_refused", "connection_reset",
                    "network_timeout", "dns_failed", "network_unreachable", "config_rejected")
                val categories = runtime.optString("errorCategories").split(',').filter { it in allowed }.distinct()
                appendLine("kernel_log_scope=last_start")
                appendLine("kernel_error_categories=${categories.joinToString(",").ifEmpty { "unavailable" }}")
            } else appendLine("kernel_state=unavailable")
            synchronized(bindGuard) {
                appendLine("binding_connected=${binding?.service?.asBinder()?.isBinderAlive == true}")
                appendLine("binding_failure=${mutableBindFailure.value?.name ?: "none"}")
                events.forEach { appendLine(it) }
            }
        }
    }

    /** Downloads the pinned plugin APK, validates it and opens the platform installer. */
    suspend fun downloadAndInstall(onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null): KernelPluginInstallResult {
        if (!installMutex.tryLock()) return KernelPluginInstallResult.Failed(KernelPluginFailure.Io)
        return try {
            withContext(Dispatchers.IO) {
                installer.clearStaged()
                val target = File(installer.stagingDirectory(), "download-${java.util.UUID.randomUUID()}.apk")
                val request = Request.Builder()
                    .url(DOWNLOAD_URL)
                    .header("User-Agent", "ZakoCoder/${BuildConfig.VERSION_NAME}")
                    .build()
                val failure = try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use KernelPluginFailure.Download
                        val body = response.body ?: return@use KernelPluginFailure.Download
                        val total = body.contentLength().takeIf { it > 0 }
                        if (total != null && total > KernelPluginTrust.MAX_APK_BYTES) return@use KernelPluginFailure.Download
                        val context = currentCoroutineContext()
                        body.byteStream().use { input ->
                            copyBounded(input, target) { downloaded ->
                                context.ensureActive()
                                onProgress?.let { callback -> runCatching { callback(downloaded, total) } }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    KernelPluginFailure.Download
                } catch (_: Exception) {
                    KernelPluginFailure.Io
                }
                if (failure != null) {
                    target.delete()
                    KernelPluginInstallResult.Failed(failure)
                } else {
                    installStaged(target)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            installMutex.unlock()
        }
    }

    /** Copies a user-picked plugin APK (SAF/document provider), validates it and opens the installer. */
    suspend fun installFromUri(uri: Uri): KernelPluginInstallResult {
        if (!installMutex.tryLock()) return KernelPluginInstallResult.Failed(KernelPluginFailure.Io)
        return try {
            withContext(Dispatchers.IO) {
                installer.clearStaged()
                val target = File(installer.stagingDirectory(), "import-${java.util.UUID.randomUUID()}.apk")
                val input = try {
                    appContext.contentResolver.openInputStream(uri)
                } catch (_: Exception) {
                    null
                } ?: return@withContext KernelPluginInstallResult.Failed(KernelPluginFailure.Io)
                val failure = try {
                    input.use { stream ->
                        val context = currentCoroutineContext()
                        copyBounded(stream, target) { context.ensureActive() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    KernelPluginFailure.Io
                }
                when {
                    failure == null -> installStaged(target)
                    target.length() > KernelPluginTrust.MAX_APK_BYTES -> {
                        target.delete()
                        KernelPluginInstallResult.Failed(KernelPluginFailure.InvalidPackage)
                    }
                    else -> {
                        target.delete()
                        KernelPluginInstallResult.Failed(KernelPluginFailure.Io)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            installMutex.unlock()
        }
    }

    /** Opens the platform uninstall confirmation for the plugin; UI refreshes on resume. */
    fun uninstall() {
        unbind()
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_DELETE, "package:${KernelPluginTrust.PLUGIN_PACKAGE}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        refresh()
    }

    private fun installStaged(apkFile: File): KernelPluginInstallResult {
        val failure = installer.validate(apkFile)
        if (failure != null) {
            apkFile.delete()
            return KernelPluginInstallResult.Failed(failure)
        }
        val openFailure = installer.openInstaller(apkFile)
        return if (openFailure == null) KernelPluginInstallResult.InstallerOpened else {
            apkFile.delete()
            KernelPluginInstallResult.Failed(openFailure)
        }
    }

    private fun queryStatus(): KernelPluginStatus {
        val info = try {
            appContext.packageManager.getPackageInfo(KernelPluginTrust.PLUGIN_PACKAGE, KernelPluginInstaller.signingFlags())
        } catch (_: PackageManager.NameNotFoundException) {
            return KernelPluginStatus.NotInstalled
        } catch (_: Exception) {
            return KernelPluginStatus.NotInstalled
        }
        val signer = KernelPluginInstaller.signerSha256(info) ?: return KernelPluginStatus.Incompatible
        val metaData = info.applicationInfo?.metaData
        val minMain = metaData?.getInt(KernelPluginTrust.MIN_MAIN_VERSION_METADATA, -1) ?: -1
        val contract = metaData?.getInt(KernelPluginTrust.CONTRACT_VERSION_METADATA, -1) ?: -1
        val failure = KernelPluginTrust.verify(
            packageName = KernelPluginTrust.PLUGIN_PACKAGE,
            signerSha256 = signer,
            versionCode = KernelPluginInstaller.versionCode(info),
            installedVersionCode = null,
            minMainVersion = minMain,
            mainVersionCode = BuildConfig.VERSION_CODE,
            contractVersion = contract,
            expectedSignerSha256 = KernelPluginInstaller.appSignerSha256(appContext) ?: return KernelPluginStatus.Incompatible,
        )
        return if (failure == null) {
            KernelPluginStatus.Installed(KernelPluginInfo(info.versionName.orEmpty(), KernelPluginInstaller.versionCode(info)))
        } else {
            KernelPluginStatus.Incompatible
        }
    }

    /** Must be called with [bindGuard] held; does not call the framework or remote service. */
    private fun detachLocked(): Binding? {
        val previous = binding ?: return null
        binding = null
        previous.ready.countDown()
        return previous
    }

    private fun release(previous: Binding) {
        val cleanup = synchronized(bindGuard) {
            if (!previous.attempted || !previous.finished || previous.released) return
            previous.released = true
            previous.service?.asBinder() to previous.deathRecipient
        }
        cleanup.second?.let { recipient -> runCatching { cleanup.first?.unlinkToDeath(recipient, 0) } }
        // Context requires cleanup even when bindService returned false or threw SecurityException.
        runCatching { appContext.unbindService(previous.connection) }
    }

    private fun onBindingLost(pending: Binding, failure: KernelBindingFailure) {
        val notify = synchronized(bindGuard) {
            if (binding !== pending) return
            recordFailure(failure)
            detachLocked()
            pending.onLost
        }
        release(pending)
        // The callback belongs to the detached binding's owner generation, never a replacement.
        notify?.invoke()
    }

    companion object {
        /** Placeholder release asset; replaced with the published plugin asset before release. */
        const val DOWNLOAD_URL =
            "https://github.com/AYssu/ZakoCoder/releases/download/kernel-plugin-v1/kernel-plugin-arm64-v8a.apk"
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}

/** Fixed diagnostic categories; never transport remote or system exception text to UI/logs. */
enum class KernelBindingFailure {
    Rejected, SecurityDenied, Timeout, NullBinding, BindingDied, Disconnected,
    RemoteCallFailed, Unexpected, Interrupted,
}

enum class KernelPluginActivationResult {
    Opened, SettingsOpened, NotInstalled, Incompatible, Unavailable,
}

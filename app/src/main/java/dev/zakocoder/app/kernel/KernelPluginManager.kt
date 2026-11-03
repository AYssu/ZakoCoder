package dev.zakocoder.app.kernel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
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
 *   ([BuildConfig.KERNEL_PLUGIN_CERT_SHA256]); nothing is trusted from the plugin's own claims;
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
    private var connection: ServiceConnection? = null
    private var service: IKernelService? = null
    private var bound = false
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** Invoked when the plugin process dies; the kernel client turns this into ServiceUnavailable. */
    @Volatile
    var onServiceDied: (() -> Unit)? = null

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
    fun bind(timeoutMillis: Long = BIND_TIMEOUT_MS): IKernelService? {
        synchronized(bindGuard) {
            service?.let { existing ->
                if (existing.asBinder().isBinderAlive) return existing
                unbindLocked()
            }
            unbindLocked()
            val latch = CountDownLatch(1)
            var connected: IKernelService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val resolved = binder?.let { IKernelService.Stub.asInterface(it) }
                    connected = resolved
                    if (binder != null) {
                        val recipient = IBinder.DeathRecipient { onBinderDied() }
                        try {
                            binder.linkToDeath(recipient, 0)
                            deathRecipient = recipient
                        } catch (_: RemoteException) {
                            // The binder died between connect and linkToDeath; treated as unavailable.
                        }
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    onBinderDied()
                }
            }
            val intent = Intent().setComponent(ComponentName(KernelPluginTrust.PLUGIN_PACKAGE, KernelPluginTrust.SERVICE_CLASS))
            val started = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
            if (!started) return null
            bound = true
            this.connection = connection
            val connectedInTime = try {
                latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            val result = connected
            if (!connectedInTime || result == null) {
                unbindLocked()
                return null
            }
            service = result
            return result
        }
    }

    /** Unbinds if currently bound; safe to call repeatedly and from any thread. */
    fun unbind() {
        synchronized(bindGuard) { unbindLocked() }
    }

    /** Downloads the pinned plugin APK, validates it and opens the platform installer. */
    suspend fun downloadAndInstall(onProgress: ((downloaded: Long, total: Long?) -> Unit)? = null): KernelPluginInstallResult {
        if (!installMutex.tryLock()) return KernelPluginInstallResult.Failed(KernelPluginFailure.Io)
        return try {
            withContext(Dispatchers.IO) {
                installer.clearStaged()
                val target = File(installer.stagingDirectory(), KernelPluginInstaller.DOWNLOAD_NAME)
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
                val target = File(installer.stagingDirectory(), KernelPluginInstaller.IMPORT_NAME)
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
            expectedSignerSha256 = BuildConfig.KERNEL_PLUGIN_CERT_SHA256,
        )
        return if (failure == null) {
            KernelPluginStatus.Installed(KernelPluginInfo(info.versionName.orEmpty(), KernelPluginInstaller.versionCode(info)))
        } else {
            KernelPluginStatus.Incompatible
        }
    }

    /** Must be called with [bindGuard] held. */
    private fun unbindLocked() {
        val connection = this.connection
        this.connection = null
        service = null
        deathRecipient = null
        if (bound) {
            bound = false
            if (connection != null) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    private fun onBinderDied() {
        synchronized(bindGuard) { unbindLocked() }
        onServiceDied?.invoke()
    }

    companion object {
        /** Placeholder release asset; replaced with the published plugin asset before release. */
        const val DOWNLOAD_URL =
            "https://github.com/AYssu/ZakoCoder/releases/download/kernel-plugin-v1/kernel-plugin-arm64-v8a.apk"
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}

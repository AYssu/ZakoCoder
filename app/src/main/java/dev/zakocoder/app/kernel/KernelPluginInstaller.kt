package dev.zakocoder.app.kernel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.zakocoder.app.BuildConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * APK staging, archive inspection and hand-off to the system installer.
 *
 * Security contract:
 * - nothing is installed silently: a verified APK is staged under `cacheDir/kernel-plugin/` and
 *   opened through the app's `FileProvider` with a one-shot read grant; the platform installer asks
 *   the user for confirmation;
 * - validation always runs on the real archive (package name, signer digest against the compiled-in
 *   BuildConfig value, versions, contract metadata), never on fields the plugin declares about
 *   itself;
 * - the staging directory matches the FileProvider path so no arbitrary file can be exposed.
 */
internal class KernelPluginInstaller(private val context: Context) {
    private val appContext = context.applicationContext

    fun stagingDirectory(): File = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }

    /** Deletes previous staged APKs; only one install is in flight (manager lock). */
    fun clearStaged() {
        runCatching {
            stagingDirectory().listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        }
    }

    /** Validates a staged/imported APK for installation; null means every check passed. */
    fun validate(apkFile: File): KernelPluginFailure? {
        if (!apkFile.isFile) return KernelPluginFailure.InvalidPackage
        if (KernelPluginTrust.verifyApkSize(apkFile.length()) != null) return KernelPluginFailure.InvalidPackage
        val archive = try {
            packageManager().getPackageArchiveInfo(apkFile.absolutePath, signingFlags())
        } catch (_: Exception) {
            null
        } ?: return KernelPluginFailure.InvalidPackage
        val signer = signerSha256(archive) ?: return KernelPluginFailure.WrongSigner
        val metaData = archive.applicationInfo?.metaData
        val minMain = metaData?.getInt(KernelPluginTrust.MIN_MAIN_VERSION_METADATA, -1) ?: -1
        val contract = metaData?.getInt(KernelPluginTrust.CONTRACT_VERSION_METADATA, -1) ?: -1
        return KernelPluginTrust.verify(
            packageName = archive.packageName,
            signerSha256 = signer,
            versionCode = versionCode(archive),
            installedVersionCode = installedVersionCode(),
            minMainVersion = minMain,
            mainVersionCode = BuildConfig.VERSION_CODE,
            contractVersion = contract,
            expectedSignerSha256 = BuildConfig.KERNEL_PLUGIN_CERT_SHA256,
        )
    }

    /** Opens the platform installer for a validated APK; null means the intent was handed off. */
    fun openInstaller(apkFile: File): KernelPluginFailure? = try {
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.kernel-plugin", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, INSTALLER_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        null
    } catch (_: Exception) {
        KernelPluginFailure.Io
    }

    private fun installedVersionCode(): Int? = try {
        packageManager().getPackageInfo(KernelPluginTrust.PLUGIN_PACKAGE, 0).let { versionCode(it) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun packageManager(): PackageManager = appContext.packageManager

    companion object {
        const val CACHE_DIR = "kernel-plugin"
        const val DOWNLOAD_NAME = "kernel-plugin-download.apk"
        const val IMPORT_NAME = "kernel-plugin-import.apk"
        const val INSTALLER_MIME = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES = KernelPluginTrust.MAX_APK_BYTES

        /** Flags that expose both the signer certificates and the plugin's compatibility metadata. */
        fun signingFlags(): Int {
            val base = PackageManager.GET_META_DATA
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                base or PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                base or PackageManager.GET_SIGNATURES
            }
        }

        /** SHA-256 of the first signing certificate, lowercase hex; null when absent. */
        @Suppress("DEPRECATION")
        fun signerSha256(info: PackageInfo): String? {
            val signatures: Array<Signature>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
            val certificate = signatures?.firstOrNull() ?: return null
            return MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        /** `PackageInfo.versionCode` is deprecated on API 28+; prefer the long form there. */
        fun versionCode(info: PackageInfo): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
    }
}

/** Copies at most [maxBytes] from [input] to [target]; callers handle cancellation between chunks. */
internal fun copyBounded(
    input: InputStream,
    target: File,
    maxBytes: Long = KernelPluginTrust.MAX_APK_BYTES,
    onChunk: (downloaded: Long) -> Unit,
): KernelPluginFailure? {
    var total = 0L
    return try {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) return KernelPluginFailure.Download
                output.write(buffer, 0, count)
                onChunk(total)
            }
        }
        if (total <= 0) KernelPluginFailure.Download else null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        KernelPluginFailure.Download
    }
}

package dev.zakocoder.app.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

internal fun updateSha256(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("DEPRECATION")
internal fun validateUpdateApk(context: Context, file: File, release: AppRelease, expectedHash: String?) {
        check(file.isFile && file.length() == release.apk.size && expectedHash != null && updateSha256(file) == expectedHash)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("apk")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName && archive.versionName == release.tag.removePrefix("v"))
        val candidateCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(candidateCode > installedCode)
        // Conservative exact current signers: key rotation requires a separately audited migration.
        val a = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val b = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!a.isNullOrEmpty() && !b.isNullOrEmpty() && a.toSet() == b.toSet())
        check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT)
    }

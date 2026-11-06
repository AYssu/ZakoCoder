package dev.zakocoder.app.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.zakocoder.app.BuildConfig
import org.json.JSONObject
import java.util.zip.ZipFile
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
        val installedDistribution = context.assets.open("distribution.json").bufferedReader().use { JSONObject(it.readText()) }
        val installedAbis = installedDistribution.getJSONArray("abis").let { values ->
            (0 until values.length()).map { values.getString(it) }
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in installedAbis } ?: error("abi")
        check(selectDistributionAsset(listOf(release.apk), release.tag, BuildConfig.DISTRIBUTION_EDITION, abi) == release.apk)
        ZipFile(file).use { zip ->
            val nativeAbis = zip.entries().asSequence().mapNotNull { entry ->
                Regex("^lib/([^/]+)/[^/]+\\.so$").matchEntire(entry.name)?.groupValues?.get(1)
            }.toSet()
            check(nativeAbis == setOf(abi))
            val marker = zip.getEntry("assets/distribution.json")
            if (marker == null) {
                // Historical unnamed ARM64 packages have no edition marker and are Lite only.
                check(BuildConfig.DISTRIBUTION_EDITION == "lite" && abi == "arm64-v8a" &&
                    release.apk.name == "ZakoCoder-${release.tag.removePrefix("v")}-arm64.apk")
            } else {
                check(marker.size in 1..4096)
                val distribution = zip.getInputStream(marker).bufferedReader().use { JSONObject(it.readText()) }
                val abis = distribution.getJSONArray("abis")
                check(distribution.getString("edition") == BuildConfig.DISTRIBUTION_EDITION &&
                    abis.length() == 1 && abis.getString(0) == abi)
            }
        }
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

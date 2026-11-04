package dev.zakocoder.app.kernel

/** UI-facing metadata for an installed, trusted kernel plugin. */
data class KernelPluginInfo(val versionName: String, val versionCode: Int)

sealed interface KernelPluginStatus {
    data object NotInstalled : KernelPluginStatus
    data class Installed(val info: KernelPluginInfo) : KernelPluginStatus

    /** Package present but untrusted, too old for this app, or speaking an unknown contract. */
    data object Incompatible : KernelPluginStatus
}

/** Stable failure categories; never carries provider text, paths or signer bytes. */
enum class KernelPluginFailure {
    Download,
    InvalidPackage,
    WrongSigner,
    WrongPackage,
    VersionTooOld,
    VersionDowngrade,
    Io,
    NotInstalled,
    ServiceUnavailable,
}

sealed interface KernelPluginInstallResult {
    data object InstallerOpened : KernelPluginInstallResult
    data class Failed(val reason: KernelPluginFailure) : KernelPluginInstallResult
    data object Cancelled : KernelPluginInstallResult
}

/**
 * Pure-JVM trust decision for the optional kernel plugin APK.
 *
 * The verifier deliberately takes every input explicitly (package name, signer digest, versions,
 * manifest metadata) so no caller can accidentally trust the plugin's own self-declared hashes: the
 * expected signer digest is the main app's own signing certificate digest, so every debug/release
 * signing variant pairs with a plugin signed by the same key. [verify] returns null only when every
 * check passes.
 */
object KernelPluginTrust {
    const val PLUGIN_PACKAGE = "dev.zakocoder.app.kernel"
    const val SERVICE_CLASS = "dev.zakocoder.app.kernel.KernelService"
    const val ACTIVATOR_CLASS = "dev.zakocoder.app.kernel.ActivateActivity"
    const val PERMISSION = "dev.zakocoder.app.permission.KERNEL"
    const val CONTRACT_VERSION = 1
    const val MIN_MAIN_VERSION_METADATA = "dev.zakocoder.app.kernel.MIN_MAIN_VERSION"
    const val CONTRACT_VERSION_METADATA = "dev.zakocoder.app.kernel.CONTRACT_VERSION"
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    /**
     * Returns null when the candidate plugin may be installed/bound, otherwise the first failure.
     * [installedVersionCode] may be null when nothing is installed yet; a same-version reinstall is
     * allowed, a downgrade is not.
     */
    fun verify(
        packageName: String?,
        signerSha256: String?,
        versionCode: Int,
        installedVersionCode: Int?,
        minMainVersion: Int?,
        mainVersionCode: Int,
        contractVersion: Int?,
        expectedSignerSha256: String,
    ): KernelPluginFailure? {
        if (packageName != PLUGIN_PACKAGE) return KernelPluginFailure.WrongPackage
        val expected = normalizeDigest(expectedSignerSha256) ?: return KernelPluginFailure.WrongSigner
        val actual = normalizeDigest(signerSha256) ?: return KernelPluginFailure.WrongSigner
        if (actual != expected) return KernelPluginFailure.WrongSigner
        if (versionCode <= 0) return KernelPluginFailure.InvalidPackage
        if (installedVersionCode != null && versionCode < installedVersionCode) return KernelPluginFailure.VersionDowngrade
        // Missing/negative metadata means the plugin cannot state its compatibility: reject it.
        if (minMainVersion == null || minMainVersion <= 0) return KernelPluginFailure.InvalidPackage
        if (minMainVersion > mainVersionCode) return KernelPluginFailure.VersionTooOld
        if (contractVersion != CONTRACT_VERSION) return KernelPluginFailure.InvalidPackage
        return null
    }

    /** Download/import size guard; a zero-byte or oversized APK is never handed to the installer. */
    fun verifyApkSize(bytes: Long, maxBytes: Long = MAX_APK_BYTES): KernelPluginFailure? = when {
        bytes <= 0 -> KernelPluginFailure.Download
        bytes > maxBytes -> KernelPluginFailure.Download
        else -> null
    }

    /**
     * Certificate digests are compared case-insensitively with `:`/whitespace separators removed,
     * so a BuildConfig value pasted from `apksigner --print-certs` still matches.
     */
    fun normalizeDigest(value: String?): String? {
        if (value == null) return null
        val compact = value.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        if (compact.length != 64 || compact.any { it !in "0123456789abcdef" }) return null
        return compact
    }
}

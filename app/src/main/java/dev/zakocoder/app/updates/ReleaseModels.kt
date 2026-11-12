package dev.zakocoder.app.updates

/** Release tags are ordered independently of GitHub's mutable publication dates. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val preview: String?, val sequence: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        if (preview == null || other.preview == null) return compareValues(preview == null, other.preview == null)
        return compareValuesBy(this, other, { when (it.preview) { "alpha" -> 0; "beta" -> 1; else -> 2 } }, { it.sequence })
    }
    companion object {
        fun parse(raw: String): ReleaseVersion? {
            val m = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?$").matchEntire(raw) ?: return null
            return ReleaseVersion(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null,
                m.groupValues[3].toIntOrNull() ?: return null, m.groupValues[4].ifEmpty { null }, m.groupValues[5].toIntOrNull() ?: 0)
        }
    }
}
enum class UpdateChannel { Stable, Beta }
data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String? = null)
data class AppRelease(val tag: String, val notes: String, val prerelease: Boolean, val apk: ReleaseAsset, val sums: ReleaseAsset,
    val versionCode: Long? = null)
enum class UpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, Failed }
data class UpdateState(val initialized: Boolean = false, val channel: UpdateChannel = UpdateChannel.Stable,
    val automatic: Boolean = true, val currentVersion: String = "", val phase: UpdatePhase = UpdatePhase.Idle,
    val release: AppRelease? = null, val received: Long = 0, val total: Long = 0, val error: Int? = null,
    val prompt: Boolean = false, val pendingInstall: Boolean = false, val currentVersionCode: Long = 0)

internal fun selectRelease(releases: List<AppRelease>, current: String, channel: UpdateChannel,
    currentVersionCode: Long = 0): AppRelease? {
    val installed = ReleaseVersion.parse(if (currentVersionCode > 0) current.removeSuffix("-dev") else current) ?: return null
    val eligible = releases.filter { ReleaseVersion.parse(it.tag) != null &&
        (channel == UpdateChannel.Beta || (!it.prerelease && ReleaseVersion.parse(it.tag)?.preview == null)) }
    // Calendar display names omit the year. Android's increasing code remains authoritative
    // across New Year and when upgrading a development build to a published release.
    if (currentVersionCode > 0) {
        eligible.filter { it.versionCode != null && it.versionCode > currentVersionCode }
            .maxByOrNull { it.versionCode!! }?.let { return it }
    }
    return eligible.filter { (currentVersionCode <= 0 || it.versionCode == null) &&
        ReleaseVersion.parse(it.tag)!! > installed }.maxByOrNull { ReleaseVersion.parse(it.tag)!! }
}

/** Values come only from the official GitHub release's schema-1 build record. */
internal fun releaseBuildVersionCode(tag: String, schema: Any?, packageName: Any?, version: Any?, code: Any?): Long? {
    if (schema != 1 || packageName != "dev.zakocoder.app" || version != tag.removePrefix("v")) return null
    if (code !is Int && code !is Long) return null
    return (code as Number).toLong().takeIf { it in 1..2_100_000_000L }
}
internal fun releaseChecksum(text: String, name: String): String? = text.lineSequence().mapNotNull { line ->
    val m = Regex("^([A-Fa-f0-9]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())
    if (m?.groupValues?.get(2) == name) m.groupValues[1].lowercase() else null
}.singleOrNull()

/** Update selection preserves the installed distribution; old unnamed ARM64 packages were Lite. */
internal fun selectDistributionAsset(assets: List<ReleaseAsset>, tag: String, edition: String, abi: String): ReleaseAsset? {
    if (edition !in setOf("lite", "full") || abi !in setOf("arm64-v8a", "x86_64")) return null
    val version = tag.removePrefix("v")
    val name = "ZakoCoder-$version-$edition-$abi.apk"
    val exact = assets.filter { it.name == name }
    if (exact.isNotEmpty()) return exact.singleOrNull()
    // Never substitute the legacy ARM64 Lite package for Full or x86_64.
    return if (edition == "lite" && abi == "arm64-v8a")
        assets.singleOrNull { it.name == "ZakoCoder-$version-arm64.apk" } else null
}

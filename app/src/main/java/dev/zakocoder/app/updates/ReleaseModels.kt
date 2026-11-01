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
data class AppRelease(val tag: String, val notes: String, val prerelease: Boolean, val apk: ReleaseAsset, val sums: ReleaseAsset)
enum class UpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, Failed }
data class UpdateState(val initialized: Boolean = false, val channel: UpdateChannel = UpdateChannel.Stable,
    val automatic: Boolean = true, val currentVersion: String = "", val phase: UpdatePhase = UpdatePhase.Idle,
    val release: AppRelease? = null, val received: Long = 0, val total: Long = 0, val error: Int? = null,
    val prompt: Boolean = false, val pendingInstall: Boolean = false)

internal fun selectRelease(releases: List<AppRelease>, current: String, channel: UpdateChannel): AppRelease? {
    val installed = ReleaseVersion.parse(current) ?: return null
    return releases.filter { channel == UpdateChannel.Beta || (!it.prerelease && ReleaseVersion.parse(it.tag)?.preview == null) }
        .filter { ReleaseVersion.parse(it.tag)?.let { version -> version > installed } == true }
        .maxByOrNull { ReleaseVersion.parse(it.tag)!! }
}
internal fun releaseChecksum(text: String, name: String): String? = text.lineSequence().mapNotNull { line ->
    val m = Regex("^([A-Fa-f0-9]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())
    if (m?.groupValues?.get(2) == name) m.groupValues[1].lowercase() else null
}.singleOrNull()

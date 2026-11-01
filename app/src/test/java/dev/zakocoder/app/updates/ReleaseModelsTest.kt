package dev.zakocoder.app.updates

import org.junit.Assert.*
import org.junit.Test

class ReleaseModelsTest {
    private fun release(tag: String, preview: Boolean = tag.contains('-')) = AppRelease(tag, "", preview,
        ReleaseAsset("app.apk", "https://github.com/AYssu/ZakoCoder/releases/download/$tag/app.apk", 10), ReleaseAsset("SHA256SUMS.txt", "", 10))
    @Test fun semanticOrderingDoesNotUseLexicographicOrDateOrdering() {
        val tags = listOf("0.1.0-beta.10", "0.1.0-alpha.1", "0.1.0", "0.1.0-rc.1", "0.1.0-beta.2", "0.2.0-beta.1")
        assertEquals(listOf(tags[1], tags[4], tags[0], tags[3], tags[2], tags[5]), tags.sortedBy { ReleaseVersion.parse(it) })
        assertNull(ReleaseVersion.parse("0.1.0-dev")); assertNull(ReleaseVersion.parse("1.2"))
    }
    @Test fun betaCanPromoteToStableAndStableDoesNotReceivePreviews() {
        val all = listOf(release("v0.1.0-beta.2"), release("v0.1.0"), release("v0.2.0-beta.1"))
        assertEquals("v0.2.0-beta.1", selectRelease(all, "0.1.0-beta.1", UpdateChannel.Beta)?.tag)
        assertEquals("v0.1.0", selectRelease(all, "0.1.0-beta.1", UpdateChannel.Stable)?.tag)
        assertNull(selectRelease(all, "0.1.0", UpdateChannel.Stable))
        assertNull(selectRelease(all, "0.1.0-dev", UpdateChannel.Beta))
    }
    @Test fun checksumMatchesExactAssetOnlyAndRejectsAmbiguity() {
        val digest = "a".repeat(64)
        assertEquals(digest, releaseChecksum("$digest  app.apk\n", "app.apk"))
        assertEquals(digest, releaseChecksum("$digest *app.apk\n", "app.apk"))
        assertNull(releaseChecksum("$digest  other.apk", "app.apk"))
        assertNull(releaseChecksum("$digest  app.apk\n$digest  app.apk", "app.apk"))
        assertNull(releaseChecksum("broken app.apk", "app.apk"))
    }
    @Test fun assetUrlMustBelongToTheActualRelease() {
        val base = "https://github.com/AYssu/ZakoCoder/releases/download/v0.1.0/"
        assertTrue(ReleaseClient.validAssetUrl(base + "app.apk", "v0.1.0"))
        assertFalse(ReleaseClient.validAssetUrl(base + "app.apk", "v0.2.0"))
        assertFalse(ReleaseClient.validAssetUrl(base.replace("github.com", "evil.example"), "v0.1.0"))
        assertFalse(ReleaseClient.validAssetUrl(base.replace("https:", "http:"), "v0.1.0"))
    }
}

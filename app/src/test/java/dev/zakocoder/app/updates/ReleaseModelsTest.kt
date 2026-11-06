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
    @Test fun rcIsOfferedOnlyInPreviewChannelAndCanUpgradeToStable() {
        val candidates = listOf(release("v0.1.0-beta.2"), release("v0.1.0-rc.1"))
        assertEquals("v0.1.0-rc.1", selectRelease(candidates, "0.1.0-beta.2", UpdateChannel.Beta)?.tag)
        assertNull(selectRelease(candidates, "0.1.0-beta.2", UpdateChannel.Stable))
        assertEquals("v0.1.0", selectRelease(candidates + release("v0.1.0"), "0.1.0-rc.1", UpdateChannel.Stable)?.tag)
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
    @Test fun distributionSelectionNeverSwitchesEditionOrArchitecture() {
        fun asset(name: String) = ReleaseAsset(name, "", 10)
        val all = listOf("lite", "full").flatMap { edition -> listOf("arm64-v8a", "x86_64").map { abi ->
            asset("ZakoCoder-1.2.3-$edition-$abi.apk")
        } }
        for (edition in listOf("lite", "full")) for (abi in listOf("arm64-v8a", "x86_64")) {
            assertEquals("ZakoCoder-1.2.3-$edition-$abi.apk", selectDistributionAsset(all, "v1.2.3", edition, abi)?.name)
        }
        val legacy = listOf(asset("ZakoCoder-1.2.3-arm64.apk"))
        assertNotNull(selectDistributionAsset(legacy, "v1.2.3", "lite", "arm64-v8a"))
        assertNull(selectDistributionAsset(legacy, "v1.2.3", "full", "arm64-v8a"))
        assertNull(selectDistributionAsset(legacy, "v1.2.3", "lite", "x86_64"))
        assertNull(selectDistributionAsset(all + all.first(), "v1.2.3", "lite", "arm64-v8a"))
    }
}

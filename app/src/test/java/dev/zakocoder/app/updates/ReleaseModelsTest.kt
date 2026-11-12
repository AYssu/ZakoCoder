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
    @Test fun publishedBuildCodeOrdersCalendarNamesAcrossNewYear() {
        val january = release("v0.1.2010100").copy(versionCode = 4_000_002)
        val december = release("v0.1.2123123").copy(versionCode = 4_000_001)
        assertEquals(january, selectRelease(listOf(december, january), "0.1.2123023", UpdateChannel.Stable, 4_000_000))
        assertNull(selectRelease(listOf(december), "0.1.2010100", UpdateChannel.Stable, 4_000_002))
    }
    @Test fun developmentBuildCanFindNewerOfficialReleaseByCode() {
        val stable = release("v0.1.2111216").copy(versionCode = 4_000_002)
        assertEquals(stable, selectRelease(listOf(stable), "0.1.2111216-dev", UpdateChannel.Stable, 4_000_001))
        assertNull(selectRelease(listOf(stable), "0.1.2111216-dev", UpdateChannel.Stable, 4_000_002))
        assertNull(selectRelease(listOf(stable), "0.1.2111216-dev", UpdateChannel.Stable, 4_000_003))
        assertEquals(stable, selectRelease(listOf(stable), "0.1.2111216-rc.1-dev", UpdateChannel.Stable, 4_000_001))
        assertNull(selectRelease(listOf(release("v0.1.2111217-dev").copy(versionCode = 4_000_003)),
            "0.1.2111216-dev", UpdateChannel.Beta, 4_000_001))
    }
    @Test fun codeOrderingStillHonorsStableAndPreviewChannels() {
        val stable = release("v0.1.2111216").copy(versionCode = 4_000_002)
        val beta = release("v0.1.2111216-beta.1").copy(versionCode = 4_000_003)
        val rc = release("v0.1.2111216-rc.1").copy(versionCode = 4_000_004)
        assertEquals(stable, selectRelease(listOf(stable, beta, rc), "0.1.2111215-dev", UpdateChannel.Stable, 4_000_001))
        assertEquals(rc, selectRelease(listOf(stable, beta, rc), "0.1.2111215-dev", UpdateChannel.Beta, 4_000_001))
        assertNull(selectRelease(listOf(beta, rc), "0.1.2111215-dev", UpdateChannel.Stable, 4_000_001))
    }
    @Test fun legacyReleasesWithoutBuildCodeRetainSemanticOrdering() {
        assertEquals("v0.1.2", selectRelease(listOf(release("v0.1.2")), "0.1.1", UpdateChannel.Stable, 1007)?.tag)
        val oldCode = release("v99.0.0").copy(versionCode = 1006)
        assertNull(selectRelease(listOf(oldCode), "0.1.1", UpdateChannel.Stable, 1007))
    }
    @Test fun buildRecordMustMatchItsReleaseAndContainBoundedIntegerCode() {
        fun code(value: Any?, tag: String = "v0.1.2111216", schema: Any? = 1,
            name: Any? = "dev.zakocoder.app", version: Any? = "0.1.2111216") =
            releaseBuildVersionCode(tag, schema, name, version, value)
        assertEquals(4_000_002L, code(4_000_002))
        assertEquals(2_100_000_000L, code(2_100_000_000L))
        for (invalid in listOf(null, 0, -1, 2_100_000_001L, "4000002", 4_000_002.0, true)) assertNull(code(invalid))
        assertNull(code(4_000_002, schema = 2))
        assertNull(code(4_000_002, name = "other.package"))
        assertNull(code(4_000_002, version = "0.1.2111215"))
        assertNull(code(4_000_002, tag = "v0.1.2111215"))
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

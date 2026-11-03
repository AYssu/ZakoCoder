package dev.zakocoder.app.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the plugin trust decision. No PackageManager, APK or network is involved: the
 * verifier receives every input explicitly, so each rejection path is deterministic.
 */
class KernelPluginTrustTest {
    private val signer = "329ae2b1ccc445d35fd3f2ca1904f058b0af897273fd3ca68eb8c68320f8a58e"

    private fun verify(
        packageName: String? = KernelPluginTrust.PLUGIN_PACKAGE,
        signerSha256: String? = signer,
        versionCode: Int = 2,
        installedVersionCode: Int? = null,
        minMainVersion: Int? = 1,
        mainVersionCode: Int = 5,
        contractVersion: Int? = 1,
        expectedSignerSha256: String = signer,
    ): KernelPluginFailure? = KernelPluginTrust.verify(
        packageName = packageName,
        signerSha256 = signerSha256,
        versionCode = versionCode,
        installedVersionCode = installedVersionCode,
        minMainVersion = minMainVersion,
        mainVersionCode = mainVersionCode,
        contractVersion = contractVersion,
        expectedSignerSha256 = expectedSignerSha256,
    )

    @Test fun acceptsTrustedCompatiblePlugin() {
        assertNull(verify())
        assertNull("Same-version reinstall is allowed", verify(versionCode = 2, installedVersionCode = 2))
        assertNull("minMainVersion == main version is allowed", verify(minMainVersion = 5, mainVersionCode = 5))
    }

    @Test fun rejectsWrongPackage() {
        assertEquals(KernelPluginFailure.WrongPackage, verify(packageName = "dev.zakocoder.app.kernel.probe"))
        assertEquals(KernelPluginFailure.WrongPackage, verify(packageName = null))
    }

    @Test fun rejectsWrongOrMissingSigner() {
        assertEquals(KernelPluginFailure.WrongSigner, verify(signerSha256 = "0".repeat(64)))
        assertEquals(KernelPluginFailure.WrongSigner, verify(signerSha256 = null))
        assertEquals(KernelPluginFailure.WrongSigner, verify(expectedSignerSha256 = ""))
        assertEquals(KernelPluginFailure.WrongSigner, verify(expectedSignerSha256 = "not-a-digest"))
        // A different digest of the same length is still a mismatch, not an accepted prefix.
        assertEquals(KernelPluginFailure.WrongSigner, verify(signerSha256 = signer.dropLast(1) + "f"))
    }

    @Test fun acceptsColonSeparatedAndUppercaseDigests() {
        val formatted = signer.uppercase().chunked(2).joinToString(":")
        assertNull(verify(signerSha256 = formatted, expectedSignerSha256 = signer))
        assertNull(verify(signerSha256 = signer, expectedSignerSha256 = " ${signer.uppercase()} "))
    }

    @Test fun rejectsDowngradeButAllowsUpgrade() {
        assertEquals(KernelPluginFailure.VersionDowngrade, verify(versionCode = 1, installedVersionCode = 2))
        assertNull(verify(versionCode = 3, installedVersionCode = 2))
    }

    @Test fun rejectsInvalidPluginVersion() {
        assertEquals(KernelPluginFailure.InvalidPackage, verify(versionCode = 0))
        assertEquals(KernelPluginFailure.InvalidPackage, verify(versionCode = -1))
    }

    @Test fun rejectsPluginThatNeedsNewerMainApp() {
        assertEquals(KernelPluginFailure.VersionTooOld, verify(minMainVersion = 6, mainVersionCode = 5))
    }

    @Test fun rejectsMissingOrInvalidMetadataAndContract() {
        assertEquals(KernelPluginFailure.InvalidPackage, verify(minMainVersion = null))
        assertEquals(KernelPluginFailure.InvalidPackage, verify(minMainVersion = 0))
        assertEquals(KernelPluginFailure.InvalidPackage, verify(contractVersion = 2))
        assertEquals(KernelPluginFailure.InvalidPackage, verify(contractVersion = null))
        assertEquals(KernelPluginFailure.InvalidPackage, verify(contractVersion = 0))
    }

    @Test fun apkSizeGuardRejectsEmptyAndOversizedFiles() {
        assertNull(KernelPluginTrust.verifyApkSize(1))
        assertNull(KernelPluginTrust.verifyApkSize(KernelPluginTrust.MAX_APK_BYTES))
        assertEquals(KernelPluginFailure.Download, KernelPluginTrust.verifyApkSize(0))
        assertEquals(KernelPluginFailure.Download, KernelPluginTrust.verifyApkSize(-1))
        assertEquals(KernelPluginFailure.Download, KernelPluginTrust.verifyApkSize(KernelPluginTrust.MAX_APK_BYTES + 1))
    }

    @Test fun digestNormalizationIsStrict() {
        assertEquals(signer, KernelPluginTrust.normalizeDigest(signer.uppercase()))
        assertEquals(signer, KernelPluginTrust.normalizeDigest(signer.chunked(4).joinToString(":")))
        assertNull(KernelPluginTrust.normalizeDigest(null))
        assertNull(KernelPluginTrust.normalizeDigest(""))
        assertNull(KernelPluginTrust.normalizeDigest("abc"))
        assertNull(KernelPluginTrust.normalizeDigest("z".repeat(64)))
    }

    @Test fun failureCategoriesAreStable() {
        // The UI maps the enum by name; a rename would silently change user-visible text.
        assertEquals(
            listOf(
                "Download", "InvalidPackage", "WrongSigner", "WrongPackage", "VersionTooOld",
                "VersionDowngrade", "Io", "NotInstalled", "ServiceUnavailable",
            ),
            KernelPluginFailure.entries.map { it.name },
        )
        assertEquals(1, KernelPluginTrust.CONTRACT_VERSION)
        assertEquals("dev.zakocoder.app.kernel", KernelPluginTrust.PLUGIN_PACKAGE)
        assertEquals("dev.zakocoder.app.kernel.KernelService", KernelPluginTrust.SERVICE_CLASS)
        assertEquals("dev.zakocoder.app.permission.KERNEL", KernelPluginTrust.PERMISSION)
    }
}

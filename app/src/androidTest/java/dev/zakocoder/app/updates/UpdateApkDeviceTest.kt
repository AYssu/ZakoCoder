package dev.zakocoder.app.updates

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Fixture APKs are built from this repository, then copied by the unified verification owner. */
class UpdateApkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun release(file: File, version: String): AppRelease = AppRelease("v$version", "", version.contains('-'),
        ReleaseAsset(file.name, "", file.length()), ReleaseAsset("SHA256SUMS.txt", "", 0))
    private fun rejected(block: () -> Unit) { try { block(); fail("Unsafe APK must be rejected") } catch (_: IllegalStateException) { } }
    @Test fun installedApkCannotBeUsedAsUpgradeAndWrongDigestIsRejected() {
        val file = File(context.applicationInfo.sourceDir)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        rejected { validateUpdateApk(context, file, release(file, version), updateSha256(file)) }
        rejected { validateUpdateApk(context, file, release(file, version), "0".repeat(64)) }
    }
    @Test fun differentPackageIsRejected() {
        val file = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)!!
        rejected { validateUpdateApk(context, file, release(file, archive.versionName.orEmpty()), updateSha256(file)) }
    }
    @Test fun sameSignerHigherVersionFixtureIsAcceptedAndWrongReleaseIsRejected() {
        val file = File(context.cacheDir, "update-upgrade-fixture.apk")
        assumeTrue("Unified owner supplies same-package same-signer higher-version fixture", file.isFile)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)!!
        validateUpdateApk(context, file, release(file, archive.versionName.orEmpty()), updateSha256(file))
        rejected { validateUpdateApk(context, file, release(file, "999.0.0"), updateSha256(file)) }
    }
    @Test fun differentSignerFixtureIsRejected() {
        val file = File(context.cacheDir, "update-wrong-signature-fixture.apk")
        assumeTrue("Unified owner supplies same-package higher-version differently-signed fixture", file.isFile)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)!!
        rejected { validateUpdateApk(context, file, release(file, archive.versionName.orEmpty()), updateSha256(file)) }
    }
}

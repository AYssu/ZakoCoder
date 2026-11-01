package dev.zakocoder.app.updates

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AppUpdateFlowDeviceTest {
    @Test fun developmentBuildCheckExplainsWhyComparisonIsUnavailable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        assumeTrue("This branch applies to the isolated development test build", ReleaseVersion.parse(version) == null)
        val prefsName = "update-dev-comparison-test"
        val prefs = context.getSharedPreferences(prefsName, 0)
        prefs.edit().clear().putBoolean("automatic", false).commit()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val updates = withContext(Dispatchers.Main) { AppUpdates(context, owner, ReleaseClient(), prefsName) }
            withTimeout(15000) { updates.state.first { it.initialized } }
            withContext(Dispatchers.Main) { updates.check() }
            assertEquals(UpdatePhase.Failed, updates.state.value.phase)
            assertEquals(dev.zakocoder.app.R.string.update_dev_version, updates.state.value.error)
            assertNull(updates.state.value.release)
        } finally { owner.cancel(); prefs.edit().clear().commit() }
    }
    @Test fun realSignedFixtureDownloadsThenSurvivesControllerRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "update-upgrade-fixture.apk")
        assumeTrue("Requires unified owner signed upgrade fixture", fixture.isFile)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val next = context.packageManager.getPackageArchiveInfo(fixture.absolutePath, 0)!!.versionName.orEmpty()
        assumeTrue("Requires base and upgrade release version names", ReleaseVersion.parse(installed) != null && ReleaseVersion.parse(next) != null)
        val name = "ZakoCoder-$next-arm64.apk"
        val tag = "v$next"
        val assetRoot = "https://github.com/AYssu/ZakoCoder/releases/download/$tag/"
        val bytes = fixture.readBytes()
        val checksum = "${updateSha256(fixture)}  $name\n"
        val assets = JSONArray().put(JSONObject().put("name", name).put("size", bytes.size).put("browser_download_url", assetRoot + name))
            .put(JSONObject().put("name", "SHA256SUMS.txt").put("size", checksum.toByteArray().size).put("browser_download_url", assetRoot + "SHA256SUMS.txt"))
        val releases = JSONArray().put(JSONObject().put("tag_name", tag).put("prerelease", next.contains('-')).put("draft", false).put("body", "真实签名升级夹具").put("assets", assets)).toString()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/repos/") -> MockResponse().setBody(releases)
                request.path.orEmpty().endsWith("SHA256SUMS.txt") -> MockResponse().setBody(checksum)
                else -> MockResponse().setBody(okio.Buffer().write(bytes))
            }
        }
        server.start()
        val prefsName = "update-flow-test"
        val prefs = context.getSharedPreferences(prefsName, 0)
        prefs.edit().clear().putBoolean("automatic", false).putString("channel", UpdateChannel.Beta.name).commit()
        fun client() = ReleaseClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))).build())
            }.build())
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val updates = withContext(Dispatchers.Main) { AppUpdates(context, firstScope, client(), prefsName) }
            withTimeout(15000) { updates.state.first { it.initialized } }
            withContext(Dispatchers.Main) { updates.check() }
            withTimeout(15000) { updates.state.first { it.phase == UpdatePhase.Available || it.phase == UpdatePhase.Failed } }
            assertEquals(UpdatePhase.Available, updates.state.value.phase)
            withContext(Dispatchers.Main) { updates.download() }
            withTimeout(30000) { updates.state.first { it.phase == UpdatePhase.Ready || it.phase == UpdatePhase.Failed } }
            assertEquals(UpdatePhase.Ready, updates.state.value.phase)
            assertEquals(bytes.size.toLong(), updates.state.value.received)
            firstScope.cancel()
            val restored = withContext(Dispatchers.Main) { AppUpdates(context, secondScope, client(), prefsName) }
            withTimeout(15000) { restored.state.first { it.initialized } }
            assertEquals(UpdatePhase.Ready, restored.state.value.phase)
            assertEquals(tag, restored.state.value.release?.tag)
            // APK remains private and can only be shared through the existing non-exported FileProvider.
            val apk = File(context.filesDir, "app-updates/update.apk")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.apk-files", apk)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.apk-files", uri.authority)
            withContext(Dispatchers.Main) { restored.configure(automatic = true) }
            assertEquals("Automatic-check preference must retain the verified APK", UpdatePhase.Ready, restored.state.value.phase)
            withContext(Dispatchers.Main) { restored.configure(automatic = false) }
            assertEquals(UpdatePhase.Ready, restored.state.value.phase)
            // Tamper after Ready to exercise the install-time check and recovery affordance.
            java.io.RandomAccessFile(apk, "rw").use { file -> val value = file.read(); file.seek(0); file.write(value xor 1) }
            withContext(Dispatchers.Main) { restored.install() }
            withTimeout(15000) {
                restored.state.first { it.phase == UpdatePhase.Failed }
                while (prefs.contains("ready") || apk.exists()) delay(25)
            }
            assertFalse("Invalid ready metadata must be cleared", prefs.contains("ready"))
            assertFalse("Invalid artifact cannot remain installable", apk.exists())
            assertNotNull("Failed state retains release for a new download", restored.state.value.release)
        } finally {
            firstScope.cancel(); secondScope.cancel(); server.shutdown()
            prefs.edit().clear().commit()
            File(context.filesDir, "app-updates/update.apk").delete()
        }
    }
}

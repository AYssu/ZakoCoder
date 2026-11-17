package dev.zakocoder.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.diagnostics.*
import dev.zakocoder.app.mcp.McpState
import dev.zakocoder.app.ssh.SshState
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

/** Only generated metadata and cache files; no model, configuration, history or user project is read. */
class DoctorDiagnosticsDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private inner class Fixture {
        val root = File(base.cacheDir, "doctor-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val output = File(base.cacheDir, "apk-install/doctor-${UUID.randomUUID()}.zip").apply { parentFile!!.mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getCacheDir() = root
        }
        val uri: Uri get() = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", output)
        fun close() { root.deleteRecursively(); output.delete() }
    }

    @Test(timeout = 30_000) fun runningTaskExportsAValidZipThroughContentUriWithoutStoppingOrRevealingPayloads() = runBlocking {
        val f = Fixture()
        try {
            val secret = "private-doctor-test-only"
            val state = AiState(active = true, phase = AiPhase.Receiving, taskPrompt = secret,
                requests = listOf(AiRequestLog(status = 200, events = 17, textChars = 150, response = secret)))
            val doctor = DoctorController(f.context) { DoctorInput(state, WorkbenchState(), McpState(), SshState()) }
            val token = doctor.prepare()
            assertTrue(doctor.state.value.ready)
            assertFalse(doctor.state.value.busy)
            doctor.writePrepared(f.uri, token)
            val entries = linkedMapOf<String, String>()
            withContext(Dispatchers.IO) {
                ZipInputStream(f.output.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
            assertEquals(setOf("README.txt", "report.json", "events.jsonl"), entries.keys)
            assertFalse(entries.values.any { it.contains(secret) })
            val ai = JSONObject(entries.getValue("report.json")).getJSONObject("ai")
            assertTrue(ai.getBoolean("active"))
            assertEquals("Receiving", ai.getString("phase"))
            assertEquals(17, ai.getJSONArray("requests").getJSONObject(0).getInt("events"))
            assertTrue(state.active)
            doctor.discard(token)
            assertFalse(File(f.root, "doctor-exports-v1/$token.zip").exists())
        } finally { f.close() }
    }

    @Test(timeout = 30_000) fun failedProviderWriteRetainsSnapshotAndInvalidTokenCannotTouchDestination() = runBlocking {
        val f = Fixture()
        try {
            val documents = DoctorDocuments(f.context)
            val token = documents.prepare("{}", "")
            try { documents.writePrepared(Uri.parse("content://dev.zakocoder.missing-doctor/document"), token); fail("Missing provider must fail") }
            catch (_: IOException) { }
            catch (_: IllegalArgumentException) { }
            assertTrue(File(f.root, "doctor-exports-v1/$token.zip").isFile)
            f.output.writeText("Preserve destination")
            try { documents.writePrepared(f.uri, "../escape"); fail("Invalid token must fail") }
            catch (_: IllegalArgumentException) { }
            assertEquals("Preserve destination", f.output.readText())
            documents.writePrepared(f.uri, token)
            assertTrue(f.output.readBytes().take(2) == listOf(80.toByte(), 75.toByte()))
            documents.discard(token)
        } finally { f.close() }
    }

    @Test(timeout = 30_000) fun preparedSnapshotsAreBoundedExpireAndRejectSymlinks(): Unit = runBlocking {
        val f = Fixture()
        try {
            val documents = DoctorDocuments(f.context)
            try { documents.prepare("a".repeat(DoctorDocuments.MAX_BYTES + 1), ""); fail("Oversize must fail") }
            catch (_: IOException) { }
            val expired = documents.prepare("{}", "")
            File(f.root, "doctor-exports-v1/$expired.zip").setLastModified(System.currentTimeMillis() - 3_600_001)
            try { documents.writePrepared(f.uri, expired); fail("Expired snapshot must fail") }
            catch (_: IOException) { }
            val token = documents.prepare("{}", "")
            assertFalse(File(f.root, "doctor-exports-v1/$expired.zip").exists())
            val cache = File(f.root, "doctor-exports-v1/$token.zip")
            cache.delete()
            f.output.writeText("Outside snapshot")
            java.nio.file.Files.createSymbolicLink(cache.toPath(), f.output.toPath())
            try { documents.writePrepared(f.uri, token); fail("Symlink must fail") }
            catch (_: IOException) { }
            assertEquals("Outside snapshot", f.output.readText())
            cache.delete()
        } finally { f.close() }
    }

    @Test(timeout = 30_000) fun actualTransportRecordsRetryAndFinalResultWithoutProviderPayloads() = runBlocking {
        val f = Fixture()
        try {
            AiDeviceTest.Fixture(f.context, maxAttempts = 2, retryDelay = {}).use { model ->
                val logger = DiagnosticEvents.forTests(File(f.root, "events"))
                val transport = model.transport.withDiagnostics(logger)
                val privateText = "private-network-fixture-never-export"
                model.server.enqueue(MockResponse().setResponseCode(503).setBody(privateText))
                model.enqueue(privateText)
                val config = withContext(Dispatchers.IO) { model.config.load() }
                transport.request(config, JSONArray().put(JSONObject().put("role", "user").put("content", privateText)),
                    JSONArray()) { }
                val jsonl = logger.exportSnapshot().jsonl
                val events = jsonl.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                assertFalse(jsonl.contains(privateText))
                assertFalse(jsonl.contains("test-only-placeholder"))
                assertFalse(jsonl.contains("localhost"))
                assertTrue(events.any { it.optString("outcome") == "Retry" && it.optInt("attempt") == 2 })
                assertTrue(events.any { it.optString("outcome") == "Failed" && it.optInt("httpStatus") == 503 })
                assertTrue(events.any { it.optString("outcome") == "Succeeded" && it.optInt("httpStatus") == 200 })
                assertTrue(events.any { it.optString("outcome") == "FirstByte" })
            }
        } finally { f.close() }
    }
}

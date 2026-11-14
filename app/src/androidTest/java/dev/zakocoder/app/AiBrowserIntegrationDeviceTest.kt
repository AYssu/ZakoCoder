package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Checks actual model image serialization separately from real WebView tests. No external services. */
class AiBrowserIntegrationDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(20) }

    @Test(timeout = 120000) fun screenshotsAttachActualPixelsOnlyWhenSharedAndNeverPersistDanglingImageHistory() = runBlocking {
        for (mode in listOf("off", "on", "revoke")) {
            val sharing = java.util.concurrent.atomic.AtomicBoolean(mode != "off")
            val share = mode != "off"
            AiDeviceTest.Fixture(base, maxAttempts = 2, retryDelay = { sharing.set(false) }).use { f ->
            val isolated = object : ContextWrapper(base) {
                override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            val image = File(base.cacheDir, "agent-browser/fixture-${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
            val bitmap = createBitmap(120, 80).apply { eraseColor(android.graphics.Color.BLUE) }
            try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            var cleanup: AiController? = null
            lateinit var ai: AiController
            var operations = 0
            try {
                main {
                    ai = AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No shell expected") }, {}, {}, f.transport, f.config, history = f.history,
                        capabilitySnapshot = { mapOf(CapabilityPackage.Browser to "fixture-browser") },
                        browserImagesAllowed = { sharing.get() }, browserTool = { _, _ ->
                            operations++
                            JSONObject().put("status", "captured").put("verified", true).put("image_path", image.absolutePath)
                        })
                    cleanup = ai
                    ai.select("browser-pixels")
                }
                await { ai.state.value.configured && !ai.state.value.restoring && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading }
                main { ai.setPolicy(AiPolicy(AiPermission.Allow)) }
                await { !ai.state.value.permissionsSaving }
                f.enqueue(calls = listOf(AiCall("browser-capture", "browser", JSONObject().put("action", "screenshot").toString())))
                if (mode == "revoke") f.enqueueResponse(okhttp3.mockwebserver.MockResponse().setResponseCode(429).setBody("retry fixture"))
                f.enqueue("Preview checked")
                main { assertTrue(ai.send("Inspect the preview")) }
                await { !ai.state.value.active }
                if (mode == "revoke") {
                    assertEquals(AiPhase.Failed, ai.state.value.phase)
                    assertEquals("Revocation must stop before another HTTP attempt", 2, f.server.requestCount)
                    assertTrue(ai.state.value.error.contains(base.getString(R.string.ai_image_sharing_revoked)))
                } else assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
                assertEquals(1, operations)
                f.server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing initial request")
                val followup = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val imageUrls = mutableListOf<String>()
                fun collect(value: Any?) {
                    when (value) {
                        is JSONObject -> value.keys().asSequence().forEach { collect(value.get(it)) }
                        is org.json.JSONArray -> repeat(value.length()) { collect(value.get(it)) }
                        is String -> if (value.startsWith("data:image/jpeg;base64,")) imageUrls += value
                    }
                }
                collect(followup)
                assertEquals(share, imageUrls.isNotEmpty())
                imageUrls.forEach { url ->
                    val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
                    val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { assertEquals(120, decoded.width); assertEquals(80, decoded.height) } finally { decoded.recycle() }
                }
                assertFalse(followup.toString().contains(image.absolutePath))
                val event = ai.state.value.events.single()
                assertEquals(AiToolStatus.Succeeded, event.status)
                assertEquals(share, JSONObject(event.output).getBoolean("image_attached"))
                val saved = withContext(Dispatchers.IO) { requireNotNull(f.history.load("browser-pixels").state) }
                assertFalse(saved.contextTurns.joinToString().contains("data:image"))
                assertFalse(saved.contextTurns.joinToString().contains(BROWSER_IMAGE_EVIDENCE))
                val storedImages = File(isolated.noBackupFilesDir, "ai-images-v1").listFiles().orEmpty()
                assertTrue("Task-only browser images must be deleted", storedImages.isEmpty())
            } finally {
                main { cleanup?.stop() }
                image.delete()
            }
        }
        }
    }
}

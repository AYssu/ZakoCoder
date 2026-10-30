package dev.zakocoder.app

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local TLS and isolated history only. The image fixtures contain no user photos or source URI. */
class AiVisionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private fun attachment(bytes: Int, name: String = "fixture.jpg", width: Int = 1, height: Int = 1) =
        AiImageAttachment(UUID.randomUUID().toString(), name, "image/jpeg", width, height, bytes)
    private fun user(text: String, images: List<AiImageAttachment>) = JSONObject().put("role", "user").put("content", text)
        .put("zako_images", JSONArray(images.map(::encodeAiImage)))

    @Test(timeout = 45000) fun imageRequestsUseMultimodalWireContentButDiagnosticsNeverRetainEncodedBytes() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val bytes = jpeg()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$encoded"
            val image = attachment(bytes.size)
            val messages = JSONArray().put(user("Describe this interface", listOf(image)))
            val original = messages.toString()
            val loaded = mutableListOf<String>()
            f.enqueue("fixture response")
            var log: AiRequestLog? = null
            val reply = f.transport.request(f.config.load(), messages, JSONArray(), imageData = {
                loaded += it.id; dataUrl
            }, observe = { log = it }) { }
            assertEquals("fixture response", reply.text)
            assertEquals(listOf(image.id), loaded)
            val body = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val wire = body.getJSONArray("messages").getJSONObject(0)
            assertFalse(wire.has("zako_images"))
            val parts = wire.getJSONArray("content")
            assertTrue((0 until parts.length()).any { parts.getJSONObject(it).optString("type") == "text" && parts.getJSONObject(it).getString("text").contains("Describe this interface") })
            val images = (0 until parts.length()).map(parts::getJSONObject).filter { it.optString("type") == "image_url" }
            assertEquals(1, images.size); assertEquals(dataUrl, images.single().getJSONObject("image_url").getString("url"))
            assertEquals("Caller messages remain metadata-only", original, messages.toString())
            val record = requireNotNull(log)
            assertFalse(record.request.contains(dataUrl)); assertFalse(record.request.contains(encoded))
            assertTrue(record.request.contains("[image omitted]"))
            assertFalse(record.encode().toString().contains(encoded))
        }
    }

    @Test(timeout = 45000) fun onlyNewestFourImagesAreResolvedAndOldImageOmissionsRemainExplicit() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val bytes = jpeg()
            val old = List(2) { attachment(bytes.size, "old-$it.jpg") }
            val recent = List(4) { attachment(bytes.size, "recent-$it.jpg") }
            val messages = JSONArray().put(user("Earlier screenshot", old))
                .put(JSONObject().put("role", "assistant").put("content", "Earlier answer"))
                .put(user("Current screenshots", recent))
            val loaded = mutableListOf<String>()
            f.enqueue("new images checked")
            f.transport.request(f.config.load(), messages, JSONArray(), imageData = {
                loaded += it.id; "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }) { }
            assertEquals(recent.map { it.id }.toSet(), loaded.toSet())
            assertEquals(4, loaded.size)
            val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            val imageParts = (0 until wire.length()).flatMap { index ->
                val parts = wire.getJSONObject(index).optJSONArray("content") ?: JSONArray()
                (0 until parts.length()).map(parts::getJSONObject).filter { it.optString("type") == "image_url" }
            }
            assertEquals(4, imageParts.size)
            assertTrue(wire.getJSONObject(0).get("content").toString().contains("omitted", ignoreCase = true))
            repeat(wire.length()) { assertFalse(wire.getJSONObject(it).has("zako_images")) }
        }
    }

    @Test(timeout = 45000) fun unavailableImageFailsBeforeSendingIncompleteModelRequest() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val image = attachment(1)
            var failed = false
            try {
                f.transport.request(f.config.load(), JSONArray().put(user("Inspect", listOf(image))), JSONArray(),
                    imageData = { throw IllegalStateException("fixture image unavailable") }) { }
            } catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun sentImageMetadataAndTallDimensionsSurviveHistoryRestartWithoutEncodedData() {
        AiDeviceTest.Fixture(context).use { f ->
            val image = attachment(100, "tall-screen.jpg", width = 80, height = 1600)
            val user = AiEntry("question", "Review screenshot", role = AiRole.User, turn = 1, images = listOf(image))
            val answer = AiEntry("answer", "Review complete", role = AiRole.Assistant, turn = 1)
            val state = AiState(turn = 1, timeline = listOf(user, answer))
            f.history.save("vision-history", state)
            val loaded = AiHistoryStore(File(f.root, "history")).load("vision-history").state!!
            assertEquals(listOf(image), loaded.timeline.first().images)
            assertEquals(1600, loaded.timeline.first().images.single().height)
            val record = File(f.root, "history").walkTopDown().single { it.name == "${state.conversationId}.json" }
            assertFalse(record.readText().contains("data:image"))
            assertFalse(record.readText().contains("content://"))
            val legacy = JSONObject(record.readText())
            repeat(legacy.getJSONArray("timeline").length()) { legacy.getJSONArray("timeline").getJSONObject(it).remove("images") }
            record.writeText(legacy.toString())
            assertTrue(AiHistoryStore(File(f.root, "history")).load("vision-history").state!!.timeline.all { it.images.isEmpty() })
        }
    }

    @Test(timeout = 45000) fun repeatedImageReferencesSendPixelsOnlyAtTheirLatestOccurrence() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val bytes = jpeg()
            val image = attachment(bytes.size)
            val messages = JSONArray()
            repeat(7) { turn ->
                messages.put(user("Screenshot reference $turn", listOf(image)))
                if (turn < 6) messages.put(JSONObject().put("role", "assistant").put("content", "Earlier review $turn"))
            }
            var resolutions = 0
            f.enqueue("latest reference reviewed")
            f.transport.request(f.config.load(), messages, JSONArray(), imageData = {
                resolutions++; "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }) { }
            assertEquals(1, resolutions)
            val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            var imageCount = 0
            repeat(wire.length()) { index ->
                val entry = wire.getJSONObject(index)
                val content = entry.optJSONArray("content") ?: JSONArray()
                val images = (0 until content.length()).map(content::getJSONObject).count { it.optString("type") == "image_url" }
                imageCount += images
                if (entry.optString("role") == "user" && index < wire.length() - 1) {
                    assertEquals(0, images)
                    assertTrue("Earlier occurrence explains where pixels remain available", content.toString().contains("later", ignoreCase = true))
                }
            }
            assertEquals(1, imageCount)
            val latest = wire.getJSONObject(wire.length() - 1).getJSONArray("content")
            assertTrue((0 until latest.length()).any { latest.getJSONObject(it).optString("type") == "image_url" })
        }
    }
}

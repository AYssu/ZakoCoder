package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real controller/import/store/history and local TLS; fixture paths never touch user sessions/photos. */
class AiMultimodalControllerDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(10) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private fun controller(f: AiDeviceTest.Fixture, history: AiHistoryStore = f.history): AiController {
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "controller-files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "controller-private").apply { mkdirs() }
        }
        return AiController(context, { id -> File(f.root, "project-$id").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("No shell expected") }, {}, {}, f.transport, f.config, history = history)
    }
    private fun screenshot(dir: File): Uri {
        val file = File(dir, "screen.png")
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(44, 90, 120))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return FileProvider.getUriForFile(base, "${base.packageName}.apk-files", file)
    }
    private fun stream(done: Boolean = true, tools: Boolean = false): MockResponse {
        val deltas = mutableListOf(JSONObject().put("reasoning_content", "public first "), JSONObject().put("reasoning_content", "public second"), JSONObject().put("content", "visible answer"))
        if (tools) deltas += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "incomplete-write")
            .put("function", JSONObject().put("name", "write_file").put("arguments", "{\"path\":\"must-not-write.txt\",\"content\":\"bad\",\"reason\":\"fixture\"}"))))
        val body = buildString {
            deltas.forEach { delta -> append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n") }
            append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (tools) "tool_calls" else "stop")))}\n\n")
            if (done) append("data: [DONE]\n\n")
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(48, 15, TimeUnit.MILLISECONDS)
    }
    private fun imageUrls(body: JSONObject): List<String> {
        val messages = body.getJSONArray("messages")
        return (0 until messages.length()).flatMap { index ->
            val content = messages.getJSONObject(index).optJSONArray("content") ?: JSONArray()
            (0 until content.length()).map(content::getJSONObject).filter { it.optString("type") == "image_url" }
                .map { it.getJSONObject("image_url").getString("url") }
        }
    }
    private suspend fun fixture(action: suspend (AiDeviceTest.Fixture, Uri) -> Unit) {
        AiDeviceTest.Fixture(base).use { f ->
            val source = File(base.cacheDir, "apk-install/${f.token}-multimodal").apply { mkdirs() }
            try { action(f, screenshot(source)) } finally { source.deleteRecursively() }
        }
    }
    private suspend fun attach(ai: AiController, uri: Uri) {
        val owner = ai.state.value
        main { ai.addImages(listOf(uri), owner.projectId, owner.conversationId) }
        await { !ai.state.value.imagesLoading }
        assertEquals(1, ai.state.value.pendingImages.size)
    }

    @Test(timeout = 120000) fun importedImageOnlyMessageAndPublicReasoningStreamSurviveControllerRestart() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("vision") }; ready(ai)
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            f.server.enqueue(stream())
            main { assertTrue(ai.send("")) }
            await { ai.state.value.timeline.any { it.role == AiRole.Assistant && it.reasoning.isNotEmpty() } }
            assertTrue(ai.state.value.active)
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            val answer = ai.state.value.timeline.last { it.role == AiRole.Assistant }
            assertEquals("public first public second", answer.reasoning); assertEquals("visible answer", answer.text)
            assertEquals(listOf(image), ai.state.value.timeline.first { it.role == AiRole.User }.images)
            assertTrue(ai.state.value.pendingImages.isEmpty())
            val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val url = imageUrls(wire).single()
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
            assertTrue(ai.state.value.requests.all { !it.request.contains(url.substringAfter(',')) && !it.request.contains("data:image") })
            val saved = f.history.load("vision").state!!
            assertEquals(answer.reasoning, saved.timeline.last { it.role == AiRole.Assistant }.reasoning)
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("vision") }; ready(reopened)
            assertFalse(reopened.state.value.historyError)
            assertEquals(listOf(image), reopened.state.value.timeline.first { it.role == AiRole.User }.images)
            assertEquals(answer, reopened.state.value.timeline.last { it.role == AiRole.Assistant })
        }
    }

    @Test(timeout = 120000) fun incompleteStreamRetainsPublicReasoningAndFailedTaskImageForContinuationWithoutToolReplay() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("interrupted-vision") }; ready(ai)
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            f.server.enqueue(stream(done = false, tools = true))
            main { assertTrue(ai.send("Inspect screenshot without editing")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Failed, ai.state.value.phase)
            assertEquals(AiFailure.Protocol, ai.state.value.failureKind)
            assertEquals("public first public second", ai.state.value.timeline.last { it.role == AiRole.Assistant }.reasoning)
            assertTrue(ai.state.value.events.none { it.callId == "incomplete-write" })
            assertFalse(File(f.root, "project-interrupted-vision/must-not-write.txt").exists())
            f.server.takeRequest(2, TimeUnit.SECONDS)
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("interrupted-vision") }; ready(reopened)
            assertEquals(listOf(image), reopened.state.value.timeline.first { it.role == AiRole.User }.images)
            assertEquals("public first public second", reopened.state.value.timeline.last { it.role == AiRole.Assistant }.reasoning)
            f.enqueue("continued safely")
            main { assertTrue(reopened.send("Continue")) }; await { !reopened.state.value.active }
            assertEquals(AiPhase.Completed, reopened.state.value.phase)
            assertEquals(1, imageUrls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).size)
            assertEquals(0, reopened.state.value.toolCount)
            assertFalse(File(f.root, "project-interrupted-vision/must-not-write.txt").exists())
        }
    }

    @Test(timeout = 120000) fun staleImagePickerTargetsCannotAttachToDifferentProjectOrConversation() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("project-a") }; ready(ai)
            val old = ai.state.value
            main { ai.select("project-b") }; ready(ai)
            main { ai.addImages(listOf(uri), old.projectId, old.conversationId) }
            assertFalse(ai.state.value.imagesLoading); assertTrue(ai.state.value.pendingImages.isEmpty())
            val beforeConversation = ai.state.value
            main { ai.conversation() }
            await { ai.state.value.conversationId != beforeConversation.conversationId && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            main { ai.addImages(listOf(uri), beforeConversation.projectId, beforeConversation.conversationId) }
            assertFalse(ai.state.value.imagesLoading); assertTrue(ai.state.value.pendingImages.isEmpty())
            assertEquals("project-b", ai.state.value.projectId)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 120000) fun failedPlainTextTaskDoesNotRecoverAnOlderSuccessfulTasksImage() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("unrelated-old-image") }; ready(ai)
            attach(ai, uri)
            f.enqueue("image reviewed successfully")
            main { assertTrue(ai.send("Inspect the screenshot")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(1, imageUrls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).size)
            f.server.enqueue(stream(done = false))
            main { assertTrue(ai.send("Now explain this unrelated text question")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Failed, ai.state.value.phase)
            // Context may still carry pixels from the successful earlier turn; the new user entry must not inherit them.
            f.server.takeRequest(2, TimeUnit.SECONDS)
            assertTrue(ai.state.value.timeline.last { it.role == AiRole.User }.images.isEmpty())
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("unrelated-old-image") }; ready(reopened)
            f.enqueue("text-only continuation")
            main { assertTrue(reopened.send("Continue the text question")) }; await { !reopened.state.value.active }
            assertEquals(AiPhase.Completed, reopened.state.value.phase)
            val body = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val latestUser = (0 until body.getJSONArray("messages").length()).map { body.getJSONArray("messages").getJSONObject(it) }
                .last { it.optString("role") == "user" }
            val parts = latestUser.optJSONArray("content") ?: JSONArray()
            assertTrue((0 until parts.length()).none { parts.getJSONObject(it).optString("type") == "image_url" })
            assertTrue(reopened.state.value.timeline.last { it.role == AiRole.User }.images.isEmpty())
        }
    }

    @Test(timeout = 120000) fun repeatedFailedImageContinuationsKeepTheirOwnImageAcrossRestarts() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("repeated-image-failure") }; ready(ai)
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            f.server.enqueue(stream(done = false))
            main { assertTrue(ai.send("Inspect this image")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Failed, ai.state.value.phase)
            assertEquals(1, imageUrls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).size)
            repeat(2) { retry ->
                lateinit var reopened: AiController
                main { reopened = controller(f); reopened.select("repeated-image-failure") }; ready(reopened)
                f.server.enqueue(stream(done = false))
                main { assertTrue(reopened.send("Continue image inspection attempt ${retry + 1}")) }; await { !reopened.state.value.active }
                assertEquals(AiPhase.Failed, reopened.state.value.phase)
                val body = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals(1, imageUrls(body).size)
                assertEquals(listOf(image), reopened.state.value.timeline.last { it.role == AiRole.User }.images)
                val saved = f.history.load("repeated-image-failure").state!!
                assertEquals(listOf(image), saved.timeline.last { it.role == AiRole.User }.images)
                assertEquals(0, reopened.state.value.toolCount)
            }
        }
    }

    @Test(timeout = 120000) fun inheritedFailedImageIsPinnedBeforeInitialHistoryFailureAndImmediateStop() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("early-image-failure") }; ready(ai)
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            f.server.enqueue(stream(done = false))
            main { assertTrue(ai.send("Inspect screenshot")) }; await { !ai.state.value.active }
            f.server.takeRequest(2, TimeUnit.SECONDS)
            var failNextSave = false
            val failingHistory = object : AiHistoryStore(File(f.root, "history")) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    if (failNextSave) { failNextSave = false; throw IllegalStateException("fixture initial history save failure") }
                    return super.save(projectId, state)
                }
            }
            lateinit var retry: AiController
            main { retry = controller(f, failingHistory); retry.select("early-image-failure") }; ready(retry)
            main {
                failNextSave = true
                assertTrue(retry.send("Continue despite earlier transport interruption"))
                assertEquals(listOf(image), retry.state.value.timeline.last { it.role == AiRole.User }.images)
            }
            await { !retry.state.value.active }
            assertEquals(AiPhase.Failed, retry.state.value.phase)
            assertEquals(1, f.server.requestCount)
            assertEquals(listOf(image), retry.state.value.timeline.last { it.role == AiRole.User }.images)
            assertEquals(listOf(image), failingHistory.load("early-image-failure").state!!.timeline.last { it.role == AiRole.User }.images)
            lateinit var stopped: AiController
            main { stopped = controller(f); stopped.select("early-image-failure") }; ready(stopped)
            main {
                assertTrue(stopped.send("Continue once more"))
                assertEquals(listOf(image), stopped.state.value.timeline.last { it.role == AiRole.User }.images)
                stopped.stop()
            }
            await { !stopped.state.value.active }
            assertEquals(AiPhase.Cancelled, stopped.state.value.phase)
            assertEquals(listOf(image), f.history.load("early-image-failure").state!!.timeline.last { it.role == AiRole.User }.images)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test(timeout = 120000) fun deletingCurrentProjectHistoryReleasesItsImageArchiveAndCannotRecacheTheOwner() = runBlocking {
        fixture { f, uri ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("delete-image-owner") }; ready(ai)
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            val archived = File(f.root, "controller-private/ai-images-v1/${image.id}.jpg")
            assertTrue(archived.isFile)
            f.enqueue("image review completed")
            main { assertTrue(ai.send("Review before deletion")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            f.server.takeRequest(2, TimeUnit.SECONDS)
            withContext(Dispatchers.Main) { ai.deleteProjectHistory("delete-image-owner") }
            assertTrue(ai.state.value.pendingImages.isEmpty())
            assertTrue(ai.state.value.timeline.none { it.images.isNotEmpty() })
            await { !archived.exists() }
            main { ai.select("another-owner") }; ready(ai)
            main { ai.select("delete-image-owner") }; ready(ai)
            assertTrue(ai.state.value.timeline.isEmpty())
            assertTrue(ai.state.value.pendingImages.isEmpty())
            assertTrue(f.history.load("delete-image-owner").conversations.isEmpty())
            assertFalse(archived.exists())
        }
    }

    @Test(timeout = 120000) fun completionCompressionPreservesImageMetadataForRestartedFollowup() = runBlocking {
        fixture { f, uri ->
            val profile = f.config.catalog().active!!
            f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                profile.parameters, AiContextOptions(64000, true, 80, 2000)))
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("compressed-image") }; ready(ai)
            for (skill in ai.state.value.enabledSkills.toList()) main { ai.enableSkill(skill, false) }
            attach(ai, uri)
            val image = ai.state.value.pendingImages.single()
            f.enqueue("Large image review " + "x".repeat(60000))
            f.enqueue("IMAGE_REVIEW_SUMMARY: screenshot inspected; retain it for the user's followup; no files modified.")
            main { assertTrue(ai.send("Inspect this screenshot carefully")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertTrue(ai.state.value.contextCompactions >= 1)
            assertTrue(ai.state.value.contextSummary.contains("IMAGE_REVIEW_SUMMARY"))
            val contextTurn = JSONArray(ai.state.value.contextTurns.single())
            assertEquals(listOf(image), decodeAiImages(contextTurn.getJSONObject(0).getJSONArray("zako_images")))
            val normalRequest = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(1, imageUrls(normalRequest).size)
            val summaryRequest = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(summaryRequest.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Summarize the supplied conversation/tool JSON"))
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("compressed-image") }; ready(reopened)
            assertFalse(reopened.state.value.historyError)
            assertEquals(listOf(image), decodeAiImages(JSONArray(reopened.state.value.contextTurns.single()).getJSONObject(0).getJSONArray("zako_images")))
            f.enqueue("followup image remains available")
            main { assertTrue(reopened.send("Continue reviewing the same screenshot")) }; await { !reopened.state.value.active }
            assertEquals(AiPhase.Completed, reopened.state.value.phase)
            assertEquals(1, imageUrls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).size)
        }
    }
}

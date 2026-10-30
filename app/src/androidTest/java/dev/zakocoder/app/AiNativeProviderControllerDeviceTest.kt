package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
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

/** Native providers through real Controller/file/history boundaries and isolated local HTTPS only. */
class AiNativeProviderControllerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(25) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.models.state.value.loading && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading
    }
    private fun controller(f: AiDeviceTest.Fixture, isolated: Context) = AiController(isolated,
        { File(f.root, "project") }, { _, _ -> false }, { _, _ -> error("No process belongs to a native provider fixture") }, {}, {},
        f.transport, f.config, permissionStore = AiPermissionStore(isolated, "native-provider-permissions"), history = f.history)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun anthropicResponse(blocks: JSONArray, tool: Boolean): MockResponse {
        val events = mutableListOf(JSONObject().put("type", "message_start").put("message", JSONObject()
            .put("id", "native-message").put("role", "assistant").put("content", JSONArray())
            .put("stop_reason", JSONObject.NULL).put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 1))))
        blocks.objects().forEachIndexed { index, block ->
            events += JSONObject().put("type", "content_block_start").put("index", index).put("content_block", block)
            events += JSONObject().put("type", "content_block_stop").put("index", index)
        }
        events += JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", if (tool) "tool_use" else "end_turn"))
            .put("usage", JSONObject().put("output_tokens", 20))
        events += JSONObject().put("type", "message_stop")
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events.joinToString("") {
            "event: ${it.getString("type")}\ndata: $it\n\n"
        })
    }
    private fun geminiResponse(parts: JSONArray): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0)
            .put("content", JSONObject().put("role", "model").put("parts", parts)).put("finishReason", "STOP")))
            .put("usageMetadata", JSONObject().put("promptTokenCount", 100).put("candidatesTokenCount", 20)) + "\n\n")
    private fun response(protocol: AiApiProtocol, text: String) = when (protocol) {
        AiApiProtocol.Anthropic -> anthropicResponse(JSONArray().put(JSONObject().put("type", "text").put("text", text)), false)
        AiApiProtocol.Gemini -> geminiResponse(JSONArray().put(JSONObject().put("text", text)))
        else -> error("Not a native fixture protocol")
    }

    @Test(timeout = 120000) fun anthropicSignedToolRoundTripPersistsAndReplaysAfterRestart() = runBlocking { exercise(AiApiProtocol.Anthropic) }
    @Test(timeout = 120000) fun geminiSignedToolRoundTripPersistsAndReplaysAfterRestart() = runBlocking { exercise(AiApiProtocol.Gemini) }

    private suspend fun exercise(protocol: AiApiProtocol) {
        AiDeviceTest.Fixture(context).use { f ->
            val projectFiles = File(f.root, "project").apply { mkdirs() }
            val appFiles = File(f.root, "native-files").apply { mkdirs() }
            val privateFiles = File(f.root, "native-private").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = appFiles
                override fun getNoBackupFilesDir() = privateFiles
            }
            val project = "native-${protocol.name}"
            val marker = "NATIVE_FILE_CONTENT_${protocol.name}"
            val signature = "opaque_native_signature_${protocol.name}_fixture"
            File(projectFiles, "sample.txt").writeText(marker)
            withContext(Dispatchers.IO) {
                val profile = requireNotNull(f.config.catalog().active)
                f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                    profile.parameters, profile.context, protocol = protocol))
            }
            val arguments = JSONObject().put("path", "sample.txt").put("reason", "Read the isolated fixture")
            val toolOutput = when (protocol) {
                AiApiProtocol.Anthropic -> anthropicResponse(JSONArray()
                    .put(JSONObject().put("type", "thinking").put("thinking", "Inspect the selected file.").put("signature", signature))
                    .put(JSONObject().put("type", "redacted_thinking").put("data", "redacted_$signature"))
                    .put(JSONObject().put("type", "tool_use").put("id", "native-read-call").put("name", "read_file").put("input", arguments)), true)
                // Missing native id intentionally exercises persistent synthetic call-id mapping.
                AiApiProtocol.Gemini -> geminiResponse(JSONArray().put(JSONObject().put("thoughtSignature", signature)
                    .put("functionCall", JSONObject().put("name", "read_file").put("args", arguments))))
                else -> error("Unexpected protocol")
            }
            f.enqueueResponse(toolOutput); f.enqueueResponse(response(protocol, "The selected file was inspected."))
            var active: AiController? = null
            try {
                main { active = controller(f, isolated); active!!.select(project) }
                val first = requireNotNull(active); ready(first)
                main { assertTrue(first.send("Read sample.txt and report its content.")) }; await { !first.state.value.active }
                assertEquals("", first.state.value.error); assertEquals(AiPhase.Completed, first.state.value.phase)
                val event = first.state.value.events.single { it.name == "read_file" }
                assertEquals(AiToolStatus.Succeeded, event.status); assertTrue(event.output.contains(marker))
                assertEquals(1, first.state.value.toolCount); assertEquals(2, f.server.requestCount)
                val initial = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                val continuation = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertNativeRequest(protocol, initial.path.orEmpty())
                assertNativeRequest(protocol, continuation.path.orEmpty())
                assertReplay(protocol, JSONObject(continuation.body.readUtf8()), signature, marker)
                assertFalse(first.state.value.requests.any { it.request.contains(signature) || it.response.contains(signature) })
                assertFalse(first.state.value.timeline.any { it.text.contains(signature) || it.reasoning.contains(signature) })
                val saved = withContext(Dispatchers.IO) { requireNotNull(f.history.load(project).state) }
                assertTrue(saved.contextTurns.joinToString().contains(signature))
                assertTrue(saved.contextTurns.joinToString().contains("zako_provider_output"))
                val conversation = saved.conversationId
                main { first.stop(); active = controller(f, isolated); active!!.select(project) }
                val restarted = requireNotNull(active); ready(restarted)
                assertFalse(restarted.state.value.historyError); assertEquals(conversation, restarted.state.value.conversationId)
                assertEquals(2, f.server.requestCount)
                assertEquals(1, restarted.state.value.events.count { it.name == "read_file" })
                f.enqueueResponse(response(protocol, "Saved native context is available."))
                main { assertTrue(restarted.send("Continue from that result without rereading the file.")) }; await { !restarted.state.value.active }
                assertEquals("", restarted.state.value.error); assertEquals(AiPhase.Completed, restarted.state.value.phase)
                assertEquals(3, f.server.requestCount)
                val request = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertNativeRequest(protocol, request.path.orEmpty())
                assertReplay(protocol, JSONObject(request.body.readUtf8()), signature, marker)
                assertEquals(1, restarted.state.value.events.count { it.name == "read_file" })
                assertEquals(marker, File(projectFiles, "sample.txt").readText())
                assertFalse(restarted.state.value.requests.any { it.request.contains(signature) || it.response.contains(signature) })
            } finally {
                main { active?.stop() }; await { active?.state?.value?.active != true }
            }
        }
    }
    private fun assertNativeRequest(protocol: AiApiProtocol, path: String) {
        if (protocol == AiApiProtocol.Anthropic) assertEquals("/v1/messages", path)
        else { assertTrue(path.startsWith("/v1/models/fixture-model:streamGenerateContent")); assertTrue(path.contains("alt=sse")) }
    }
    private fun assertReplay(protocol: AiApiProtocol, body: JSONObject, signature: String, marker: String) {
        if (protocol == AiApiProtocol.Anthropic) {
            val blocks = body.getJSONArray("messages").objects().flatMap { it.getJSONArray("content").objects() }
            assertEquals(signature, blocks.single { it.optString("type") == "thinking" }.getString("signature"))
            assertEquals("redacted_$signature", blocks.single { it.optString("type") == "redacted_thinking" }.getString("data"))
            val result = blocks.single { it.optString("type") == "tool_result" }
            assertEquals("native-read-call", result.getString("tool_use_id")); assertTrue(result.getString("content").contains(marker))
        } else {
            val parts = body.getJSONArray("contents").objects().flatMap { it.getJSONArray("parts").objects() }
            assertEquals(signature, parts.single { it.has("functionCall") }.getString("thoughtSignature"))
            val result = parts.single { it.has("functionResponse") }.getJSONObject("functionResponse")
            assertEquals("read_file", result.getString("name")); assertTrue(result.getJSONObject("response").toString().contains(marker))
        }
    }
}

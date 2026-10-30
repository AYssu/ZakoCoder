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

/** Entire controller against its own TLS server and project; no commercial API or user config. */
class AiResponsesControllerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(25) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.models.state.value.loading &&
            !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading
    }

    private fun controller(f: AiDeviceTest.Fixture, isolated: Context) = AiController(isolated,
        { File(f.root, "project") }, { _, _ -> false }, { _, _ -> error("No process may execute in this fixture") }, {}, {},
        f.transport, f.config, permissionStore = AiPermissionStore(isolated, "responses-permissions"), history = f.history)

    private fun completed(output: JSONArray) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "event: response.completed\ndata: " + JSONObject().put("type", "response.completed").put("response",
            JSONObject().put("id", "resp-fixture").put("status", "completed").put("output", output)
                .put("usage", JSONObject().put("input_tokens", 640).put("output_tokens", 48)
                    .put("input_tokens_details", JSONObject().put("cached_tokens", 128)))) + "\n\n")

    private fun message(id: String, text: String) = JSONObject().put("id", id).put("type", "message")
        .put("role", "assistant").put("status", "completed").put("content", JSONArray().put(
            JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))

    private fun configure(f: AiDeviceTest.Fixture, protocol: AiApiProtocol): AiModelDraft {
        val profile = requireNotNull(f.config.catalog().active)
        return AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
            profile.parameters, profile.context, protocol = protocol)
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test(timeout = 120000) fun approvedReadRetriesOnlyModelThenPersistsOpaqueContextAndSwitchesProtocol() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            val appFiles = File(f.root, "application-files").apply { mkdirs() }
            val projectFiles = File(f.root, "project").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = appFiles }
            val project = "responses-fixture"
            val marker = "READ_ONCE_MARKER"
            File(projectFiles, "sample.txt").writeText(marker)
            withContext(Dispatchers.IO) { f.config.saveProfile(configure(f, AiApiProtocol.Responses)) }
            val opaque = "encrypted_reasoning_fixture_opaque_not_a_credential"
            val reasoning = JSONObject().put("id", "reasoning-1").put("type", "reasoning")
                .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "Read the selected project file.")))
                .put("encrypted_content", opaque)
            val call = JSONObject().put("id", "function-item-1").put("type", "function_call")
                .put("status", "completed").put("call_id", "read-call-1").put("name", "read_file")
                .put("arguments", JSONObject().put("path", "sample.txt").put("reason", "Read fixture content").toString())
            f.enqueueResponse(completed(JSONArray().put(reasoning).put(call)))
            f.enqueueResponse(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("temporary fixture service failure"))
            f.enqueueResponse(completed(JSONArray().put(message("answer-1", "The file was read once."))))
            var ai: AiController? = null
            try {
                main { ai = controller(f, isolated); ai!!.select(project) }
                val first = ai!!
                ready(first)
                main { first.setPolicy(AiPolicy(overrides = mapOf(AiTool.ReadFile to AiPermission.Ask))) }
                await { !first.state.value.permissionsSaving && first.state.value.policy.permission(AiTool.ReadFile) == AiPermission.Ask }
                main { assertTrue(first.send("Read sample.txt after my approval.")) }
                await { first.state.value.approval != null }
                val approval = first.state.value.approval!!.eventId
                assertEquals(1, f.server.requestCount)
                assertEquals(AiToolStatus.Waiting, first.state.value.events.single { it.callId == "read-call-1" }.status)
                delay(200)
                assertEquals("approval must remain pending", approval, first.state.value.approval?.eventId)
                assertEquals("no continuation before approval", 1, f.server.requestCount)
                main { first.approve(true) }
                await { !first.state.value.active }
                assertEquals("", first.state.value.error)
                assertEquals(AiPhase.Completed, first.state.value.phase)
                assertEquals("retry must not duplicate tool execution", 1, first.state.value.events.count { it.callId == "read-call-1" })
                val event = first.state.value.events.single { it.callId == "read-call-1" }
                assertEquals(AiToolStatus.Succeeded, event.status)
                assertTrue(event.output.contains(marker))
                assertNull(first.state.value.approval)
                assertEquals(3, f.server.requestCount)
                val recorded = (1..3).map { requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
                assertTrue(recorded.all { it.path == "/v1/responses" })
                val bodies = recorded.map { JSONObject(it.body.readUtf8()) }
                assertEquals("retry repeats only the same model request", bodies[1].toString(), bodies[2].toString())
                for (body in bodies.drop(1)) {
                    val input = body.getJSONArray("input").objects()
                    assertEquals(opaque, input.single { it.optString("type") == "reasoning" }.getString("encrypted_content"))
                    val toolOutput = input.single { it.optString("type") == "function_call_output" }
                    assertEquals("read-call-1", toolOutput.getString("call_id"))
                    assertTrue(toolOutput.getString("output").contains(marker))
                }
                assertTrue(first.state.value.contextTurns.joinToString().contains(opaque))
                assertFalse(first.state.value.timeline.any { it.text.contains(opaque) || it.reasoning.contains(opaque) })
                assertFalse(first.state.value.requests.any { it.request.contains(opaque) || it.response.contains(opaque) })
                val persisted = withContext(Dispatchers.IO) { f.history.load(project).state!! }
                assertTrue("durable protocol context", persisted.contextTurns.joinToString().contains(opaque))
                val conversation = first.state.value.conversationId
                main { first.stop(); ai = controller(f, isolated); ai!!.select(project) }
                val restarted = ai!!
                ready(restarted)
                assertEquals(conversation, restarted.state.value.conversationId)
                assertEquals("restart must not replay a tool or model request", 3, f.server.requestCount)
                assertEquals(1, restarted.state.value.events.count { it.callId == "read-call-1" })
                assertEquals(AiPermission.Ask, restarted.state.value.policy.permission(AiTool.ReadFile))
                f.enqueueResponse(completed(JSONArray().put(message("answer-2", "Continued with saved context."))))
                main { assertTrue(restarted.send("Continue without rereading the file.")) }
                await { !restarted.state.value.active }
                assertEquals("", restarted.state.value.error)
                val continuedRequest = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/v1/responses", continuedRequest.path)
                val continued = JSONObject(continuedRequest.body.readUtf8()).getJSONArray("input").objects()
                assertEquals(opaque, continued.single { it.optString("type") == "reasoning" }.getString("encrypted_content"))
                assertEquals(1, continued.count { it.optString("type") == "function_call_output" && it.optString("call_id") == "read-call-1" })
                assertEquals(1, restarted.state.value.events.count { it.callId == "read-call-1" })
                val chatDraft = withContext(Dispatchers.IO) { configure(f, AiApiProtocol.ChatCompletions) }
                main { restarted.models.save(chatDraft) }
                await { !restarted.models.state.value.saving && restarted.models.state.value.catalog.active?.protocol == AiApiProtocol.ChatCompletions }
                f.enqueue("Chat continuation is independent of Responses metadata.")
                main { assertTrue(restarted.send("Continue using Chat Completions.")) }
                await { !restarted.state.value.active }
                assertEquals("", restarted.state.value.error)
                val chatRequest = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("/v1/chat/completions", chatRequest.path)
                val chatBody = chatRequest.body.readUtf8()
                assertFalse(chatBody.contains(opaque))
                assertFalse(chatBody.contains("zako_responses_output"))
                val chatMessages = JSONObject(chatBody).getJSONArray("messages").objects()
                assertTrue(chatMessages.any { it.optString("role") == "tool" && it.optString("tool_call_id") == "read-call-1" })
                assertEquals(5, f.server.requestCount)
                assertEquals(marker, File(projectFiles, "sample.txt").readText())
            } finally {
                main { ai?.stop() }
                await { ai?.state?.value?.active != true }
            }
        }
    }
}

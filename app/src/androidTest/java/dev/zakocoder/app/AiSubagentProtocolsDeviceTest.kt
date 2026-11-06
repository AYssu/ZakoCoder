package dev.zakocoder.app

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

/** The same real file/delegation loop through all four native wire adapters, local HTTPS only. */
class AiSubagentProtocolsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun response(protocol: AiApiProtocol, text: String = "", call: AiCall? = null): MockResponse {
        val body = when (protocol) {
            AiApiProtocol.ChatCompletions -> {
                val message = JSONObject().put("role", "assistant").put("content", text)
                if (call != null) message.put("tool_calls", JSONArray().put(JSONObject().put("id", call.id).put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))))
                JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", if (call == null) "stop" else "tool_calls")))
            }
            AiApiProtocol.Responses -> JSONObject().put("id", "response-fixture").put("status", "completed").put("output", JSONArray().put(
                if (call != null) JSONObject().put("type", "function_call").put("id", "item-" + call.id).put("call_id", call.id).put("name", call.name).put("arguments", call.arguments)
                else JSONObject().put("type", "message").put("id", "message-fixture").put("role", "assistant").put("status", "completed")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
            AiApiProtocol.Anthropic -> JSONObject().put("id", "message-fixture").put("type", "message").put("role", "assistant")
                .put("content", JSONArray().put(if (call != null) JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name).put("input", JSONObject(call.arguments))
                    else JSONObject().put("type", "text").put("text", text)))
                .put("stop_reason", if (call == null) "end_turn" else "tool_use")
                .put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 20))
            AiApiProtocol.Gemini -> JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(
                if (call != null) JSONObject().put("functionCall", JSONObject().put("name", call.name).put("args", JSONObject(call.arguments)))
                else JSONObject().put("text", text)))).put("finishReason", "STOP")))
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    @Test(timeout = 120000) fun eachProtocolDelegatesReadsAndReplaysResultsWithValidIndependentCacheKeys() = runBlocking {
        for (protocol in AiApiProtocol.entries) AiDeviceTest.Fixture(context).use { f ->
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "source.txt").writeText("REAL_${protocol.name}_CHILD_EVIDENCE")
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            val profile = f.config.catalog().active!!
            f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                parameters = AiParameters(service = AiServiceOptions(streaming = false)), protocol = protocol))
            val tasks = JSONObject().put("tasks", JSONArray().put(JSONObject().put("role", "scout").put("task", "Inspect source.txt")))
            f.enqueueResponse(response(protocol, call = AiCall("delegate", "delegate_readonly", tasks.toString())))
            f.enqueueResponse(response(protocol, call = AiCall("child-read", "read_file", "{\"path\":\"source.txt\"}")))
            f.enqueueResponse(response(protocol, "source.txt:1 has REAL_${protocol.name}_CHILD_EVIDENCE"))
            f.enqueueResponse(response(protocol, "Parent received findings"))
            lateinit var ai: AiController
            withContext(Dispatchers.Main) {
                ai = AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("No shell") }, {}, {}, f.transport, f.config, history = f.history)
                ai.select("protocol-${protocol.name}")
            }
            withTimeout(30000) { while (!ai.state.value.configured || ai.state.value.permissionsLoading || ai.state.value.skillsLoading) delay(20) }
            withContext(Dispatchers.Main) { ai.setAutomaticDelegation(true); assertTrue(ai.send("Delegate source inspection")) }
            withTimeout(30000) { while (ai.state.value.active) delay(20) }
            assertEquals("$protocol: ${ai.state.value.taskDiagnostic}", AiPhase.Completed, ai.state.value.phase)
            assertEquals(4, f.server.requestCount)
            val requests = (1..4).map { JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()) }
            assertTrue(requests[2].toString().contains("REAL_${protocol.name}_CHILD_EVIDENCE"))
            assertTrue(requests[3].toString().contains("REAL_${protocol.name}_CHILD_EVIDENCE"))
            if (protocol == AiApiProtocol.Responses) {
                val keys = requests.map { it.getString("prompt_cache_key") }
                assertTrue(keys.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
                assertEquals(keys[0], keys[3]); assertEquals(keys[1], keys[2]); assertNotEquals(keys[0], keys[1])
            }
            assertEquals(2, ai.state.value.requests.count { it.agent == "1:scout" })
            assertNotNull(f.history.load("protocol-${protocol.name}").state)
        }
    }
}

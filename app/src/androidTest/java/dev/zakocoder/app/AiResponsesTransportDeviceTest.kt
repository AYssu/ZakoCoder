package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real HTTPS transport against synthetic Responses SSE; no commercial model or user data. */
class AiResponsesTransportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun event(type: String, fields: JSONObject = JSONObject()): String =
        "event: $type\ndata: ${JSONObject(fields.toString()).put("type", type)}\n\n"
    private fun message(text: String) = JSONObject().put("id", "msg-fixture").put("type", "message")
        .put("role", "assistant").put("status", "completed").put("content", JSONArray().put(
            JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))
    private fun completed(text: String) = event("response.completed", JSONObject().put("response", JSONObject()
        .put("id", "resp-fixture").put("status", "completed").put("output", JSONArray().put(message(text)))
        .put("usage", JSONObject().put("input_tokens", 31).put("output_tokens", 7)
            .put("input_tokens_details", JSONObject().put("cached_tokens", 11)))))
    private fun delta(text: String) = event("response.output_text.delta", JSONObject().put("output_index", 0)
        .put("content_index", 0).put("item_id", "msg-fixture").put("delta", text))
    private fun added() = event("response.output_item.added", JSONObject().put("output_index", 0).put("item",
        message("").put("status", "in_progress").put("content", JSONArray())))
    private fun body(text: String) = added() + delta(text) + completed(text)
    private fun config(f: AiDeviceTest.Fixture) = AiConfig(f.server.url("/v1").toString(), "fixture-model", protocol = AiApiProtocol.Responses)
    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect project"))

    @Test(timeout = 30000) fun typedStreamCompletesWithoutDoneAndRecordsActualProtocolAndUsage() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            // Multiline SSE data is valid; parse the whole event before decoding JSON.
            val final = completed("Hello world").replace("data: {", "data: {\ndata: ")
            f.server.enqueue(MockResponse().setBody(added() + delta("Hello ") + delta("world") + final))
            var log: AiRequestLog? = null
            val displayed = StringBuilder()
            val reply = f.transport.request(config(f).copy(parameters = AiParameters(maxTokens = 4096)), messages(), JSONArray(),
                observe = { log = it }) { displayed.append(it) }
            assertEquals("Hello world", reply.text); assertEquals(reply.text, displayed.toString())
            assertNotNull(reply.responsesOutput)
            assertEquals("Responses", log!!.apiProtocol); assertTrue(log!!.complete); assertNull(log!!.failure)
            assertEquals(31L, log!!.inputTokens); assertEquals(7L, log!!.outputTokens); assertEquals(11L, log!!.cachedTokens)
            assertEquals(log, AiRequestLog.decode(log!!.encode()))
            val request = requireNotNull(f.server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/v1/responses", request.path)
            val wire = JSONObject(request.body.readUtf8())
            assertFalse(wire.has("messages")); assertFalse(wire.has("stream_options")); assertFalse(wire.has("max_tokens"))
            assertFalse(wire.getBoolean("store")); assertEquals(4096, wire.getInt("max_output_tokens"))
            assertEquals("reasoning.encrypted_content", wire.getJSONArray("include").getString(0))
        }
    }

    @Test(timeout = 30000) fun truncatedStreamRetriesSameInputAndReplacesOnlyPartialText() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            f.server.enqueue(MockResponse().setBody(added() + delta("partial")))
            f.server.enqueue(MockResponse().setBody(body("recovered")))
            val output = StringBuilder(); val logs = linkedMapOf<String, AiRequestLog>(); var resets = 0
            val reply = f.transport.request(config(f), messages(), JSONArray(), observe = { logs[it.id] = it },
                onRetryReset = { resets++; output.clear() }) { output.append(it) }
            assertEquals("recovered", reply.text); assertEquals("recovered", output.toString()); assertEquals(1, resets)
            assertEquals(2, logs.size); assertEquals("missing_done", logs.values.first().diagnostic)
            assertEquals(listOf(1, 2), logs.values.map { it.attempt })
            assertEquals(f.server.takeRequest().body.readUtf8(), f.server.takeRequest().body.readUtf8())
        }
    }

    @Test(timeout = 30000) fun incompleteOutputCannotBecomeACompletedReplyOrReplayTools() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            f.server.enqueue(MockResponse().setBody(added() + delta("unfinished") + event("response.incomplete",
                JSONObject().put("response", JSONObject().put("status", "incomplete").put("output", JSONArray().put(message("unfinished")))
                    .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))))))
            var log: AiRequestLog? = null
            try { f.transport.request(config(f), messages(), JSONArray(), observe = { log = it }) {}; fail("Expected incomplete response") }
            catch (error: AiTransportException) { assertEquals(AiFailure.OutputLimit, error.kind) }
            assertEquals(1, f.server.requestCount); assertEquals(AiFailure.OutputLimit, log!!.failure); assertTrue(log!!.complete)
        }
    }

    @Test(timeout = 30000) fun switchingToChatStripsResponsesMetadataAndUnsupportedParamsFailBeforeNetwork() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val opaque = JSONObject().put("type", "reasoning").put("id", "rs-fixture")
                .put("summary", JSONArray()).put("encrypted_content", "opaque-fixture-reasoning")
            val history = messages().put(AiReply("prior", emptyList(), responsesOutput = JSONArray().put(opaque).put(message("prior")).toString()).asAssistantMessage())
                .put(JSONObject().put("role", "user").put("content", "Continue"))
            f.enqueue("chat answer")
            assertEquals("chat answer", f.transport.request(config(f).copy(protocol = AiApiProtocol.ChatCompletions), history, JSONArray()) {}.text)
            val wire = f.server.takeRequest().body.readUtf8()
            assertFalse(wire.contains("zako_responses_output")); assertFalse(wire.contains("opaque-fixture-reasoning"))
            var log: AiRequestLog? = null
            try { f.transport.request(config(f).copy(parameters = AiParameters(seed = 1)), messages(), JSONArray(), observe = { log = it }) {}; fail("Expected configuration rejection") }
            catch (error: AiTransportException) { assertEquals(AiFailure.RequestConfiguration, error.kind) }
            assertEquals(1, f.server.requestCount); assertEquals(AiFailure.RequestConfiguration, log!!.failure); assertTrue(log!!.complete)
        }
    }
}

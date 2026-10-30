package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real shared HTTPS transport, synthetic native provider events, no external accounts. */
class AiProviderTransportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect project"))
    private fun event(value: JSONObject) = "data: $value\n\n"
    private fun anthropic(text: String) = listOf(
        JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "msg_fixture").put("type", "message").put("role", "assistant").put("content", JSONArray()).put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 0))),
        JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", JSONObject().put("type", "text").put("text", "")),
        JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", JSONObject().put("type", "text_delta").put("text", text)),
        JSONObject().put("type", "content_block_stop").put("index", 0),
        JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)).put("usage", JSONObject().put("output_tokens", 4)),
        JSONObject().put("type", "message_stop")
    ).joinToString("") { event(it) }
    private fun gemini(text: String) = event(JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0)
        .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text))))))) +
        event(JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")))
            .put("usageMetadata", JSONObject().put("promptTokenCount", 12).put("candidatesTokenCount", 4).put("totalTokenCount", 16)))

    @Test(timeout = 30000) fun nativeProvidersUseTheirOwnPathsAuthenticationAndSharedLogs() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            for (protocol in listOf(AiApiProtocol.Anthropic, AiApiProtocol.Gemini)) {
                f.server.enqueue(MockResponse().setBody(if (protocol == AiApiProtocol.Anthropic) anthropic("native answer") else gemini("native answer")))
                val config = AiConfig(f.server.url(if (protocol == AiApiProtocol.Gemini) "/v1beta" else "/v1").toString(), "fixture-model", "synthetic-provider-credential", protocol = protocol)
                var log: AiRequestLog? = null
                val text = StringBuilder()
                val reply = f.transport.request(config, messages(), JSONArray(), observe = { log = it }) { text.append(it) }
                assertEquals("native answer", reply.text); assertEquals(reply.text, text.toString())
                assertEquals(protocol.name, log!!.apiProtocol); assertTrue(log!!.complete); assertNull(log!!.failure)
                assertEquals(12L, log!!.inputTokens); assertEquals(4L, log!!.outputTokens)
                val request = f.server.takeRequest()
                if (protocol == AiApiProtocol.Anthropic) {
                    assertEquals("/v1/messages", request.path)
                    assertEquals(config.key, request.getHeader("x-api-key")); assertEquals("2023-06-01", request.getHeader("anthropic-version"))
                    assertEquals(8192, JSONObject(request.body.readUtf8()).getInt("max_tokens"))
                } else {
                    assertEquals("/v1beta/models/fixture-model:streamGenerateContent?alt=sse", request.path)
                    assertEquals(config.key, request.getHeader("x-goog-api-key")); assertFalse(requireNotNull(request.path).contains(config.key))
                    assertTrue(JSONObject(request.body.readUtf8()).has("contents"))
                }
                assertNull(request.getHeader("Authorization")); assertFalse(log!!.encode().toString().contains(config.key))
            }
        }
    }
    @Test(timeout = 30000) fun nativeModelCatalogsPaginateUsingProtocolHeadersAndFilterGeminiMethods() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            f.server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-fixture-a"}],"has_more":true,"last_id":"page-a"}"""))
            f.server.enqueue(MockResponse().setBody("""{"data":[{"id":"claude-fixture-b"}],"has_more":false}"""))
            val base = AiConfig(f.server.url("/v1").toString(), key = "synthetic-provider-credential", protocol = AiApiProtocol.Anthropic)
            assertEquals(listOf("claude-fixture-a", "claude-fixture-b"), f.transport.models(base))
            assertEquals("/v1/models", f.server.takeRequest().path)
            val second = f.server.takeRequest(); assertEquals("/v1/models?after_id=page-a", second.path); assertEquals(base.key, second.getHeader("x-api-key"))
            f.server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-fixture","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-fixture","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"page b"}"""))
            f.server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemini-other","supportedGenerationMethods":["generateContent"]}]}"""))
            assertEquals(listOf("gemini-fixture", "gemini-other"), f.transport.models(base.copy(protocol = AiApiProtocol.Gemini)))
            assertEquals("/v1/models", f.server.takeRequest().path)
            val last = f.server.takeRequest(); assertEquals("/v1/models?pageToken=page%20b", last.path); assertEquals(base.key, last.getHeader("x-goog-api-key"))
        }
    }
    @Test fun nativeDiagnosticsRemoveImageEncodingAndOpaqueProviderData() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(
            JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,QUJDRA==")))))
        val google = AiGeminiWire.request(AiConfig(model = "fixture-model", protocol = AiApiProtocol.Gemini), messages, JSONArray())
        val claude = AiAnthropicWire.request(AiConfig(model = "fixture-model", protocol = AiApiProtocol.Anthropic), messages, JSONArray())
        assertTrue(google.toString().contains("QUJDRA==")); assertFalse(AiGeminiWire.diagnostic(google).toString().contains("QUJDRA=="))
        assertTrue(claude.toString().contains("QUJDRA==")); assertFalse(AiAnthropicWire.diagnostic(claude).toString().contains("QUJDRA=="))
    }
    @Test(timeout = 30000) fun switchingProvidersStripsOpaqueHistoryBeforeChatAndResponses() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val history = messages().put(AiReply("previous", emptyList(), "public summary",
                providerOutput = JSONArray().put(JSONObject().put("type", "redacted_thinking").put("data", "opaque-provider-fixture")).toString(), providerProtocol = "Anthropic").asAssistantMessage())
            f.enqueue("chat reply")
            f.transport.request(AiConfig(f.server.url("/v1").toString(), "fixture-model"), history, JSONArray()) {}
            val body = f.server.takeRequest().body.readUtf8()
            assertFalse(body.contains("zako_provider_output")); assertFalse(body.contains("opaque-provider-fixture")); assertFalse(body.contains("reasoning_content"))
            val wire = AiResponsesWire.request(AiConfig(model = "fixture-model", protocol = AiApiProtocol.Responses), history, JSONArray())
            assertFalse(wire.toString().contains("opaque-provider-fixture"))
        }
    }
    @Test(timeout = 30000) fun invalidNativeModelFailsLocallyWithCompletedConfigurationLog() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var log: AiRequestLog? = null
            try {
                f.transport.request(AiConfig(f.server.url("/v1beta").toString(), "vendor/model", protocol = AiApiProtocol.Gemini),
                    messages(), JSONArray(), observe = { log = it }) {}
                fail("Expected local configuration rejection")
            } catch (error: AiTransportException) { assertEquals(AiFailure.RequestConfiguration, error.kind) }
            assertEquals(0, f.server.requestCount)
            assertTrue(log!!.complete); assertEquals("gemini_invalid_model_id", log!!.diagnostic)
        }
    }
    @Test(timeout = 30000) fun explicitResponsesServerFailureRetriesButUnknownErrorsDoNot() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            val transient = event(JSONObject().put("type", "error").put("code", "server_error").put("message", "untrusted error text"))
            f.server.enqueue(MockResponse().setBody(transient)); f.server.enqueue(MockResponse().setBody(transient)); f.server.enqueue(MockResponse().setBody(transient))
            val logs = linkedMapOf<String, AiRequestLog>()
            val config = AiConfig(f.server.url("/v1").toString(), "fixture-model", protocol = AiApiProtocol.Responses)
            try { f.transport.request(config, messages(), JSONArray(), observe = { logs[it.id] = it }) {}; fail("Expected retry exhaustion") }
            catch (error: AiTransportException) { assertEquals("responses_server_error", error.diagnostic) }
            assertEquals(3, f.server.requestCount); assertEquals(listOf(1, 2, 3), logs.values.map { it.attempt })
            assertTrue(logs.values.all { it.complete && !it.encode().toString().contains("untrusted error text") })
            f.server.enqueue(MockResponse().setBody(event(JSONObject().put("type", "error").put("code", "unsupported_feature"))))
            try { f.transport.request(config, messages(), JSONArray()) {}; fail("Expected permanent failure") } catch (_: AiTransportException) { }
            assertEquals(4, f.server.requestCount)
        }
    }
}

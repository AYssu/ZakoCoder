package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Local TLS fixtures only: no real service, user configuration, keys, or model charges. */
class DeepSeekTransportDeviceTest {
    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val transport: ChatTransport
        init {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            transport = ChatTransport(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                // Retain the provider URL seen by ChatTransport but route every test to this fixture.
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build()) }
                .callTimeout(15, TimeUnit.SECONDS).build())
        }
        fun enqueue(reasoning: List<String>?, content: String = "answer", tool: Boolean = false, done: Boolean = true) {
            val chunks = mutableListOf<JSONObject>()
            reasoning?.forEach { chunks += JSONObject().put("reasoning_content", it) }
            chunks += JSONObject().put("content", content)
            if (tool) chunks += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call-1")
                .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"main.kt\"}"))))
            val body = buildString {
                chunks.forEach { append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", it)))}\n\n") }
                append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (tool) "tool_calls" else "stop")))}\n\n")
                append("data: ${JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 12).put("prompt_cache_hit_tokens", 7))}\n\n")
                if (done) append("data: [DONE]\n\n")
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(47, 1, TimeUnit.MILLISECONDS))
        }
        fun request() = JSONObject(requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
        override fun close() { server.shutdown() }
    }
    private val tools get() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_file")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "inspect file"))

    @Test fun toolSubroundAndNextUserTurnRetainAllReasoningWithoutDisplayingIt() = runBlocking {
        Fixture().use { fixture ->
            // A compatible gateway can return this extension, without identifying itself as DeepSeek.
            val config = AiConfig("https://gateway.example/v1", "custom-model")
            val messages = messages()
            fixture.enqueue(listOf("first ", "reasoning"), tool = true)
            val display = StringBuilder()
            var observed: AiRequestLog? = null
            val first = fixture.transport.request(config, messages, tools, observe = { observed = it }) { display.append(it) }
            assertEquals("answer", display.toString())
            assertEquals("first reasoning", first.reasoningContent)
            assertEquals(7L, observed?.cachedTokens)
            assertFalse(fixture.request().has("thinking"))
            messages.put(first.asAssistantMessage())
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "file contents"))
            fixture.enqueue(listOf("final reasoning"), content = "done")
            val end = fixture.transport.request(config, messages, tools) { }
            val second = fixture.request().getJSONArray("messages")
            assertEquals("first reasoning", second.getJSONObject(1).getString("reasoning_content"))
            assertEquals("call-1", second.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            messages.put(end.asAssistantMessage())
            messages.put(JSONObject().put("role", "user").put("content", "continue"))
            fixture.enqueue(listOf("next reasoning"))
            fixture.transport.request(config, messages, tools) { }
            val next = fixture.request().getJSONArray("messages")
            assertEquals("first reasoning", next.getJSONObject(1).getString("reasoning_content"))
            assertEquals("final reasoning", next.getJSONObject(3).getString("reasoning_content"))
        }
    }

    @Test fun normalProviderDoesNotGainReasoningFieldsOrThinkingParameter() = runBlocking {
        Fixture().use { fixture ->
            fixture.enqueue(null)
            val reply = fixture.transport.request(AiConfig("https://unrelated.example/v1", "deepseek-compatible-name"), messages(), tools, forceTool = "read_file") { }
            assertNull(reply.reasoningContent)
            assertFalse(reply.asAssistantMessage().has("reasoning_content"))
            assertFalse(fixture.request().has("thinking"))
        }
    }

    @Test fun officialForcedToolProbeDisablesThinkingButRegularCodingDoesNot() = runBlocking {
        Fixture().use { fixture ->
            val config = AiConfig("https://api.deepseek.com/v1", "deepseek-flash")
            fixture.enqueue(null, tool = true)
            fixture.transport.request(config, messages(), tools, forceTool = "read_file") { }
            assertEquals("disabled", fixture.request().getJSONObject("thinking").getString("type"))
            fixture.enqueue(listOf("reasoning"))
            fixture.transport.request(config, messages(), tools) { }
            assertFalse(fixture.request().has("thinking"))
        }
    }

    @Test fun incompleteReasoningToolStreamNeverReturnsExecutableCalls() = runBlocking {
        Fixture().use { fixture ->
            fixture.enqueue(listOf("reasoning"), tool = true, done = false)
            try {
                fixture.transport.request(AiConfig("https://api.deepseek.com", "deepseek-flash"), messages(), tools) { }
                fail("Incomplete stream must fail before returning tool calls")
            } catch (error: AiTransportException) { assertEquals("missing_done", error.diagnostic) }
        }
    }

    @Test fun oversizedReasoningIsRejectedRatherThanSilentlyTruncated() = runBlocking {
        Fixture().use { fixture ->
            fixture.enqueue(listOf("x".repeat(256001)))
            try {
                fixture.transport.request(AiConfig("https://api.deepseek.com", "deepseek-flash"), messages(), tools) { }
                fail("Reasoning must fit its bounded context allocation")
            } catch (error: AiTransportException) { assertEquals("response_reasoning_limit", error.diagnostic) }
        }
    }
}

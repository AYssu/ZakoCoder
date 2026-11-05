package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local HTTPS and isolated history only; no model account, user source, or device settings. */
class AiServiceTransportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "fixture"))
    private fun config(f: AiDeviceTest.Fixture, protocol: AiApiProtocol, service: AiServiceOptions) =
        AiConfig(f.server.url("/v1").toString(), "fixture-model", protocol = protocol,
            parameters = AiParameters(service = service))
    private fun chat(text: String = "answer", details: JSONArray? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant")
            .put("content", text).apply { if (details != null) put("reasoning_details", details) })))
    private fun response() = JSONObject().put("status", "completed").put("output", JSONArray().put(
        JSONObject().put("type", "message").put("id", "message-1").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "answer")))))
    private fun anthropic() = JSONObject().put("type", "message").put("role", "assistant").put("stop_reason", "end_turn")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "answer")))
    private fun gemini() = JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")
        .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "answer"))))))
    private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    @Test fun fourExplicitJsonProtocolsUseTheirOwnRequestPathsAndPreserveUnknownUsage() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            for ((protocol, body) in listOf(AiApiProtocol.ChatCompletions to chat(), AiApiProtocol.Responses to response(),
                AiApiProtocol.Anthropic to anthropic(), AiApiProtocol.Gemini to gemini())) {
                f.server.enqueue(json(body))
                val display = StringBuilder(); var log: AiRequestLog? = null
                assertEquals("answer", f.transport.request(config(f, protocol, AiServiceOptions(streaming = false)), messages(), JSONArray(),
                    observe = { log = it }) { display.append(it) }.text)
                assertEquals("answer", display.toString()); assertNull(log!!.failure)
                assertEquals(1, log!!.events); assertNull(log!!.inputTokens); assertNull(log!!.cachedTokens)
                val request = f.server.takeRequest(); val sent = JSONObject(request.body.readUtf8())
                if (protocol == AiApiProtocol.Gemini) {
                    assertEquals("/v1/models/fixture-model:generateContent", request.path)
                    assertFalse(sent.has("stream"))
                } else assertFalse(sent.getBoolean("stream"))
                assertFalse(sent.has("stream_options"))
            }
        }
    }

    @Test fun explicitCapabilitiesChangeTheActualRequestWithoutGuessingFromModelName() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val base = config(f, AiApiProtocol.ChatCompletions, AiServiceOptions(streaming = false, streamUsage = false,
                samplingParameters = false, chatThinking = AiChatThinkingFormat.EnableThinking, chatTokenLimit = AiChatTokenLimit.MaxCompletionTokens))
                .copy(parameters = AiParameters(maxTokens = 2048, temperature = 1.6, topP = 0.8, thinking = AiThinking.Enabled,
                    service = AiServiceOptions(streaming = false, streamUsage = false, samplingParameters = false,
                        chatThinking = AiChatThinkingFormat.EnableThinking, chatTokenLimit = AiChatTokenLimit.MaxCompletionTokens)))
            f.server.enqueue(json(chat())); f.transport.request(base, messages(), JSONArray()) {}
            var sent = JSONObject(f.server.takeRequest().body.readUtf8())
            assertTrue(sent.getBoolean("enable_thinking")); assertFalse(sent.has("thinking"))
            assertFalse(sent.has("temperature")); assertFalse(sent.has("top_p")); assertFalse(sent.has("stream_options"))
            assertFalse(sent.has("max_tokens")); assertEquals(2048, sent.getInt("max_completion_tokens"))
            f.server.enqueue(json(chat())); f.transport.request(base.copy(parameters = base.parameters.copy(reasoningEffort = AiReasoningEffort.High,
                service = base.parameters.service.copy(chatThinking = AiChatThinkingFormat.ReasoningObject))), messages(), JSONArray()) {}
            sent = JSONObject(f.server.takeRequest().body.readUtf8())
            assertEquals("high", sent.getJSONObject("reasoning").getString("effort"))
            assertTrue(sent.getJSONObject("reasoning").getBoolean("enabled")); assertFalse(sent.has("reasoning_effort"))
            f.server.enqueue(json(chat())); f.transport.request(base.copy(parameters = base.parameters.copy(
                service = base.parameters.service.copy(chatThinking = AiChatThinkingFormat.None))), messages(), JSONArray()) {}
            sent = JSONObject(f.server.takeRequest().body.readUtf8())
            assertFalse(sent.has("thinking")); assertFalse(sent.has("enable_thinking")); assertFalse(sent.has("reasoning_effort"))
        }
    }

    @Test fun officialDeepSeekProbeKeepsThinkingDisabledAfterEveryFormatMapping() {
        for (format in AiChatThinkingFormat.entries) {
            val original = AiConfig("https://api.deepseek.com/v1", "fixture", parameters = AiParameters(thinking = AiThinking.Enabled,
                service = AiServiceOptions(chatThinking = format)))
            val probe = AiServiceRequestPolicy.effectiveConfig(original, "connection_probe")
            assertEquals(AiThinking.Disabled, probe.parameters.thinking)
            val body = JSONObject().put("thinking", JSONObject().put("type", "disabled"))
            AiServiceRequestPolicy.apply(probe, body, null)
            when (format) {
                AiChatThinkingFormat.ThinkingObject -> assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
                AiChatThinkingFormat.EnableThinking -> assertFalse(body.getBoolean("enable_thinking"))
                AiChatThinkingFormat.ReasoningObject -> assertFalse(body.getJSONObject("reasoning").getBoolean("enabled"))
                AiChatThinkingFormat.None -> assertFalse(body.has("thinking"))
            }
            assertEquals(AiThinking.Enabled, AiServiceRequestPolicy.effectiveConfig(original).parameters.thinking)
            assertEquals(AiThinking.Enabled, AiServiceRequestPolicy.effectiveConfig(original.copy(endpoint = "https://gateway.example/v1"),
                "connection_probe").parameters.thinking)
        }
    }

    @Test fun responsesUsageAliasesKeepStandardZeroAndLongValuesWithoutCoercion() {
        val large = Int.MAX_VALUE.toLong() + 50
        val usage = JSONObject().put("prompt_tokens", large).put("completion_tokens", 10).put("cached_tokens", 5)
        var stream = AiResponsesWire.completed(response().put("usage", usage))
        assertEquals(large, stream.inputTokens); assertEquals(10L, stream.outputTokens); assertEquals(5L, stream.cachedTokens)
        usage.put("input_tokens", 0).put("output_tokens", 0).put("input_tokens_details", JSONObject().put("cached_tokens", 0))
        stream = AiResponsesWire.completed(response().put("usage", usage))
        assertEquals(0L, stream.inputTokens); assertEquals(0L, stream.outputTokens); assertEquals(0L, stream.cachedTokens)
        for (invalid in listOf<Any>(-1, 1.5, "9")) {
            try { AiResponsesWire.completed(response().put("usage", JSONObject().put("cached_tokens", invalid))); fail() }
            catch (error: AiTransportException) { assertEquals("responses_invalid_usage", error.diagnostic) }
        }
    }

    @Test fun activeCachingIsOptInStableAndDoesNotIncludeProjectNames() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val key = AiServiceRequestPolicy.sessionCacheKey("private-project", "conversation-1")
            assertEquals(key, AiServiceRequestPolicy.sessionCacheKey("private-project", "conversation-1"))
            assertNotEquals(key, AiServiceRequestPolicy.sessionCacheKey("private-project", "conversation-2"))
            assertFalse(key.contains("private-project"))
            val config = config(f, AiApiProtocol.Responses, AiServiceOptions(streaming = false, responsesCacheKey = true,
                responsesSummary = false, responsesEncryptedReasoning = false)).copy(parameters = AiParameters(thinking = AiThinking.Enabled,
                    service = AiServiceOptions(streaming = false, responsesCacheKey = true, responsesSummary = false, responsesEncryptedReasoning = false)))
            repeat(2) {
                f.server.enqueue(json(response())); f.transport.request(config, messages(), JSONArray(), cacheKey = key) {}
                val sent = JSONObject(f.server.takeRequest().body.readUtf8())
                assertEquals(key, sent.getString("prompt_cache_key")); assertFalse(sent.has("include")); assertFalse(sent.has("reasoning"))
            }
            f.server.enqueue(json(response())); f.transport.request(config.copy(parameters = config.parameters.copy(
                service = config.parameters.service.copy(responsesCacheKey = false))), messages(), JSONArray(), cacheKey = key) {}
            assertFalse(JSONObject(f.server.takeRequest().body.readUtf8()).has("prompt_cache_key"))
            for ((setting, ttl) in listOf(AiAnthropicCache.FiveMinutes to "5m", AiAnthropicCache.OneHour to "1h")) {
                f.server.enqueue(json(anthropic()))
                f.transport.request(config(f, AiApiProtocol.Anthropic, AiServiceOptions(streaming = false, anthropicCache = setting)), messages(), JSONArray()) {}
                assertEquals(ttl, JSONObject(f.server.takeRequest().body.readUtf8()).getJSONObject("cache_control").getString("ttl"))
            }
        }
    }

    @Test fun reasoningDetailsSurviveDiskReplayAndNeverAppearInDiagnosticsOrOtherProtocols() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val opaque = "opaque-fixture-reasoning-not-a-credential"
            val details = JSONArray().put(JSONObject().put("index", 0).put("type", "reasoning.encrypted").put("data", opaque).put("format", "fixture"))
            val current = config(f, AiApiProtocol.ChatCompletions, AiServiceOptions(streaming = false))
            f.server.enqueue(json(chat(details = details)))
            val reply = f.transport.request(current, messages(), JSONArray()) {}
            val turn = messages().put(reply.asAssistantMessage())
            val state = AiState(projectId = "fixture", contextTurns = listOf(turn.toString()), turn = 1)
            f.history.save("fixture", state)
            val restored = AiHistoryStore(File(f.root, "history")).load("fixture").state!!
            val replay = JSONArray(restored.contextTurns.single()).put(JSONObject().put("role", "user").put("content", "continue"))
            f.server.takeRequest()
            f.server.enqueue(json(chat())); var log: AiRequestLog? = null
            f.transport.request(current, replay, JSONArray(), observe = { log = it }) {}
            val sent = JSONObject(f.server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1)
            assertEquals(opaque, sent.getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertFalse(sent.has(CHAT_REASONING_DETAILS_KEY)); assertFalse(log!!.request.contains(opaque)); assertFalse(log!!.response.contains(opaque))
            assertTrue(replay.getJSONObject(1).has(CHAT_REASONING_DETAILS_KEY))
            f.server.enqueue(json(response()))
            f.transport.request(current.copy(protocol = AiApiProtocol.Responses), replay, JSONArray()) {}
            val other = f.server.takeRequest().body.readUtf8()
            assertFalse(other.contains(opaque)); assertFalse(other.contains("reasoning_details"))
            assertTrue(replay.getJSONObject(1).has(CHAT_REASONING_DETAILS_KEY))
        }
    }

    @Test fun streamingNeverAutomaticallyFallsBackToJsonAndBadJsonNeverPublishesTools() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            f.server.enqueue(json(chat()))
            try { f.transport.request(config(f, AiApiProtocol.ChatCompletions, AiServiceOptions()), messages(), JSONArray()) {}; fail() }
            catch (_: AiTransportException) { }
            assertTrue(JSONObject(f.server.takeRequest().body.readUtf8()).getBoolean("stream"))
            assertEquals(1, f.server.requestCount)
            val incomplete = response().put("status", "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            f.server.enqueue(json(incomplete)); var drafts = 0; var visible = 0
            try { f.transport.request(config(f, AiApiProtocol.Responses, AiServiceOptions(streaming = false)), messages(), JSONArray(),
                toolDelta = { drafts += it.size }) { visible += it.length }; fail() }
            catch (error: AiTransportException) { assertEquals(AiFailure.OutputLimit, error.kind) }
            assertEquals(0, drafts); assertEquals(0, visible)
        }
    }
    @Test fun explicitGeminiJsonPublishesPublicThoughtOnceAfterFullValidation() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val body = gemini()
            body.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("parts", JSONArray()
                .put(JSONObject().put("thought", true).put("text", "public thought"))
                .put(JSONObject().put("text", "answer")))
            f.server.enqueue(json(body))
            val thought = StringBuilder(); val text = StringBuilder()
            val reply = f.transport.request(config(f, AiApiProtocol.Gemini, AiServiceOptions(streaming = false)),
                messages(), JSONArray(), reasoningDelta = { thought.append(it) }) { text.append(it) }
            assertEquals("public thought", thought.toString())
            assertEquals("answer", text.toString()); assertEquals("answer", reply.text)
            assertTrue(reply.providerOutput!!.contains("public thought"))
        }
    }

    @Test fun fourJsonFailuresKeepUsageButPublishNoTextThoughtOrExecutableDrafts() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val chat = chat("must not display")
                .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 25)
                    .put("prompt_tokens_details", JSONObject().put("cached_tokens", 20)))
            chat.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
                .getJSONObject("message").put("reasoning_content", "must not show thought")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_1").put("type", "function")
                    .put("function", JSONObject().put("name", "read_file").put("arguments", "{}"))))
            val responses = response().put("status", "incomplete")
                .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
                .put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 25)
                    .put("input_tokens_details", JSONObject().put("cached_tokens", 20)))
            val anthropic = anthropic().put("stop_reason", "max_tokens")
                .put("usage", JSONObject().put("input_tokens", 80).put("output_tokens", 25).put("cache_read_input_tokens", 20))
            val gemini = gemini().put("usageMetadata", JSONObject().put("promptTokenCount", 100)
                .put("candidatesTokenCount", 25).put("cachedContentTokenCount", 20))
            gemini.getJSONArray("candidates").getJSONObject(0).put("finishReason", "SAFETY")
            for ((protocol, body) in listOf(AiApiProtocol.ChatCompletions to chat, AiApiProtocol.Responses to responses,
                AiApiProtocol.Anthropic to anthropic, AiApiProtocol.Gemini to gemini)) {
                f.server.enqueue(json(body))
                var log: AiRequestLog? = null; var text = 0; var thought = 0; var tools = 0
                try {
                    f.transport.request(config(f, protocol, AiServiceOptions(streaming = false)), messages(), JSONArray(),
                        observe = { log = it }, reasoningDelta = { thought += it.length }, toolDelta = { tools += it.size }) { text += it.length }
                    fail("Invalid completion yielded success for $protocol")
                } catch (_: AiTransportException) { }
                assertEquals(protocol.name, 0, text); assertEquals(protocol.name, 0, thought); assertEquals(protocol.name, 0, tools)
                assertTrue(protocol.name, log!!.complete); assertNotNull(protocol.name, log!!.failure)
                assertEquals(protocol.name, 100L, log!!.inputTokens)
                assertEquals(protocol.name, 25L, log!!.outputTokens)
                assertEquals(protocol.name, 20L, log!!.cachedTokens)
                assertEquals(protocol.name, 1, log!!.events)
                assertTrue(protocol.name, log!!.bytes > 0)
                assertEquals(protocol.name, "", log!!.response)
            }
        }
    }

    @Test fun jsonToolValidationFailureKeepsUsageWithoutDeliveringPartialOutput() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val body = response().put("output", JSONArray().put(JSONObject().put("type", "function_call")
                .put("id", "fc_1").put("call_id", "call_1").put("name", "read_file").put("arguments", "{broken")))
                .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0)
                    .put("input_tokens_details", JSONObject().put("cached_tokens", 0)))
            f.server.enqueue(json(body))
            var log: AiRequestLog? = null; var callbacks = 0
            try {
                f.transport.request(config(f, AiApiProtocol.Responses, AiServiceOptions(streaming = false)), messages(), JSONArray(),
                    observe = { log = it }, reasoningDelta = { callbacks++ }, toolDelta = { callbacks++ }) { callbacks++ }
                fail("Invalid tool arguments yielded success")
            } catch (error: AiTransportException) { assertEquals("malformed_json", error.diagnostic) }
            assertEquals(0, callbacks)
            assertEquals(0L, log!!.inputTokens); assertEquals(0L, log!!.outputTokens); assertEquals(0L, log!!.cachedTokens)
            assertNotNull(log!!.failure)
        }
    }

}

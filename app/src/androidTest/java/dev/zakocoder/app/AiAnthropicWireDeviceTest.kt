package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native Messages protocol fixtures, no network, credentials, provider account or shell. */
class AiAnthropicWireDeviceTest {
    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun start() = JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "msg_fixture")
        .put("role", "assistant").put("content", JSONArray()).put("stop_reason", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 1)
            .put("cache_creation_input_tokens", 20).put("cache_read_input_tokens", 30)))
    private fun block(index: Int, value: JSONObject) = JSONObject().put("type", "content_block_start").put("index", index).put("content_block", value)
    private fun textBlock(value: String = "") = JSONObject().put("type", "text").put("text", value)
    private fun tool(id: String = "tool_1", input: JSONObject = JSONObject()) = JSONObject().put("type", "tool_use")
        .put("id", id).put("name", "read_file").put("input", input)
    private fun fragment(index: Int, type: String, field: String, value: String) = JSONObject().put("type", "content_block_delta")
        .put("index", index).put("delta", JSONObject().put("type", type).put(field, value))
    private fun stop(index: Int) = JSONObject().put("type", "content_block_stop").put("index", index)
    private fun finish(reason: String = "end_turn") = JSONObject().put("type", "message_delta")
        .put("delta", JSONObject().put("stop_reason", reason)).put("usage", JSONObject().put("output_tokens", 15))
    private fun failure(action: () -> Unit): AiTransportException = try { action(); error("Expected failure") }
        catch (error: AiTransportException) { error }

    @Test fun nativeRequestSeparatesSystemAndCombinesToolResultsWithoutChangingModel() {
        val calls = JSONArray().put(JSONObject().put("id", "tool_1").put("type", "function")
            .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"one\"}")))
            .put(JSONObject().put("id", "tool_2").put("type", "function")
                .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"two\"}")))
        val messages = JSONArray().put(message("system", "Native rules")).put(message("user", "Read both"))
            .put(message("assistant", "Inspecting").put("tool_calls", calls))
            .put(message("tool", "one body").put("tool_call_id", "tool_1"))
            .put(message("tool", "two body").put("tool_call_id", "tool_2"))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "read_file").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
        val body = AiAnthropicWire.request(AiConfig(model = "explicit-user-model"), messages, tools, "read_file")
        assertEquals("explicit-user-model", body.getString("model")); assertEquals(8192, body.getInt("max_tokens"))
        assertEquals("Native rules", body.getJSONArray("system").getJSONObject(0).getString("text"))
        assertEquals("tool", body.getJSONObject("tool_choice").getString("type"))
        assertTrue(body.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        val input = body.getJSONArray("messages")
        assertEquals(3, input.length()); assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals(3, input.getJSONObject(1).getJSONArray("content").length())
        val results = input.getJSONObject(2).getJSONArray("content")
        assertEquals(2, results.length()); assertEquals("tool_result", results.getJSONObject(0).getString("type"))
        assertEquals("tool_2", results.getJSONObject(1).getString("tool_use_id"))
        assertFalse(body.has("previous_response_id")); assertFalse(body.has("stream_options"))
    }

    @Test fun imagesMapToNativeBase64OrUrlAndOpaqueHistoryIsNotRepeatedOrLogged() {
        val thinking = JSONObject().put("type", "thinking").put("thinking", "Public summary").put("signature", "OPAQUE_SIGNATURE_FIXTURE")
        val redacted = JSONObject().put("type", "redacted_thinking").put("data", "OPAQUE_REDACTED_FIXTURE")
        val blocks = JSONArray().put(thinking).put(redacted).put(tool(input = JSONObject().put("path", "a.kt")))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,AAAA")))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://images.invalid/fixture.png")))))
            .put(message("assistant", "duplicate text").put(PROVIDER_OUTPUT_KEY, JSONObject().put("protocol", "Anthropic").put("output", blocks)))
        val body = AiAnthropicWire.request(AiConfig(), messages, JSONArray())
        val input = body.getJSONArray("messages")
        val images = input.getJSONObject(0).getJSONArray("content")
        assertEquals("base64", images.getJSONObject(0).getJSONObject("source").getString("type"))
        assertEquals("url", images.getJSONObject(1).getJSONObject("source").getString("type"))
        assertEquals(blocks.toString(), input.getJSONObject(1).getJSONArray("content").toString())
        assertFalse(body.toString().contains("duplicate text"))
        val diagnostic = AiAnthropicWire.diagnostic(body).toString()
        assertFalse(diagnostic.contains("OPAQUE_SIGNATURE_FIXTURE")); assertFalse(diagnostic.contains("OPAQUE_REDACTED_FIXTURE"))
        assertFalse(diagnostic.contains("AAAA")); assertTrue(body.toString().contains("OPAQUE_SIGNATURE_FIXTURE"))
    }

    @Test fun explicitNativeThinkingAndEffortNeverSilentlyDropUnsupportedOptions() {
        val enabled = AiAnthropicWire.request(AiConfig(parameters = AiParameters(thinking = AiThinking.Enabled,
            reasoningEffort = AiReasoningEffort.High)), JSONArray(), JSONArray())
        assertEquals(4096, enabled.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals("high", enabled.getJSONObject("output_config").getString("effort"))
        assertFalse(enabled.has("tool_choice"))
        val invalid = listOf(AiParameters(seed = 8), AiParameters(frequencyPenalty = 0.1), AiParameters(presencePenalty = 0.1),
            AiParameters(temperature = 1.1), AiParameters(thinking = AiThinking.Enabled, maxTokens = 1024),
            AiParameters(thinking = AiThinking.Enabled, temperature = 1.0), AiParameters(thinking = AiThinking.Enabled, topP = 0.9),
            AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.Max))
        invalid.forEach { parameters ->
            val error = failure { AiAnthropicWire.request(AiConfig(parameters = parameters), JSONArray(), JSONArray()) }
            assertEquals(AiFailure.RequestConfiguration, error.kind); assertEquals("anthropic_unsupported_parameters", error.diagnostic)
        }
        assertEquals(AiFailure.RequestConfiguration, failure {
            AiAnthropicWire.request(AiConfig(parameters = AiParameters(thinking = AiThinking.Enabled)), JSONArray(), JSONArray(), "read_file")
        }.kind)
    }

    @Test fun signedThinkingRedactionArgumentsAndUsageSurviveFullTypedStream() {
        val visible = StringBuilder(); val reasoning = StringBuilder(); var drafts = emptyList<AiCallDraft>()
        val stream = AiAnthropicStream({ visible.append(it) }, { reasoning.append(it) }, { drafts = it })
        stream.accept(start())
        stream.accept(block(0, JSONObject().put("type", "thinking").put("thinking", "").put("signature", "")))
        stream.accept(fragment(0, "thinking_delta", "thinking", "Public reasoning"))
        stream.accept(fragment(0, "signature_delta", "signature", "SIG_")); stream.accept(fragment(0, "signature_delta", "signature", "FIXTURE"))
        stream.accept(stop(0))
        stream.accept(block(1, JSONObject().put("type", "redacted_thinking").put("data", "REDACTED_FIXTURE"))); stream.accept(stop(1))
        stream.accept(block(2, tool()))
        stream.accept(fragment(2, "input_json_delta", "partial_json", "{\"path\":"))
        stream.accept(fragment(2, "input_json_delta", "partial_json", "\"a.kt\"}"))
        assertTrue(drafts.single().arguments.contains("a.kt")); assertFalse(stream.done)
        assertEquals("missing_done", failure { stream.reply() }.diagnostic)
        stream.accept(stop(2)); stream.accept(finish("tool_use")); stream.accept(JSONObject().put("type", "message_stop"))
        val reply = stream.reply()
        assertEquals("Public reasoning", reasoning.toString()); assertEquals("", visible.toString())
        assertEquals(60L, stream.inputTokens); assertEquals(30L, stream.cachedTokens); assertEquals(15L, stream.outputTokens)
        assertEquals("tool_calls", stream.finish); assertEquals("tool_1", reply.calls.single().id)
        assertEquals("{\"path\":\"a.kt\"}", reply.calls.single().arguments)
        assertEquals("Anthropic", reply.providerProtocol)
        val output = JSONArray(reply.providerOutput!!)
        assertEquals("SIG_FIXTURE", output.getJSONObject(0).getString("signature"))
        assertEquals("REDACTED_FIXTURE", output.getJSONObject(1).getString("data"))
        val replay = AiAnthropicWire.request(AiConfig(), JSONArray().put(reply.asAssistantMessage()), JSONArray())
        assertEquals(output.toString(), replay.getJSONArray("messages").getJSONObject(0).getJSONArray("content").toString())
    }

    @Test fun textDeltasAndInitialToolInputAreNotDuplicatedAtStop() {
        val text = StringBuilder(); val stream = AiAnthropicStream({ text.append(it) })
        stream.accept(start()); stream.accept(block(0, textBlock("Start ")))
        stream.accept(fragment(0, "text_delta", "text", "finish")); stream.accept(stop(0)); stream.accept(finish())
        assertFalse(stream.done); stream.accept(JSONObject().put("type", "message_stop"))
        assertEquals("Start finish", text.toString()); assertEquals("Start finish", stream.reply().text)
        assertEquals(15L, stream.outputTokens)
        val toolStream = AiAnthropicStream(); toolStream.accept(start())
        toolStream.accept(block(0, tool(input = JSONObject().put("path", "initial.kt"))))
        toolStream.accept(stop(0)); toolStream.accept(finish("tool_use")); toolStream.accept(JSONObject().put("type", "message_stop"))
        assertEquals("initial.kt", JSONObject(toolStream.reply().calls.single().arguments).getString("path"))
    }

    @Test fun permanentFailuresAndUnfinishedOrMismatchedToolsNeverReturnExecutableCalls() {
        val pending = AiAnthropicStream(); pending.accept(start()); pending.accept(block(0, tool()))
        pending.accept(fragment(0, "input_json_delta", "partial_json", "{\"path\":"))
        assertEquals("anthropic_invalid_event_order", failure { pending.accept(JSONObject().put("type", "message_stop")) }.diagnostic)
        assertEquals("missing_done", failure { pending.reply() }.diagnostic)
        val mismatch = AiAnthropicStream(); mismatch.accept(start()); mismatch.accept(block(0, textBlock()))
        assertEquals("anthropic_content_type_mismatch", failure { mismatch.accept(fragment(0, "input_json_delta", "partial_json", "{}")) }.diagnostic)
        assertEquals("anthropic_failed", failure { AiAnthropicStream().accept(JSONObject().put("type", "error")
            .put("error", JSONObject().put("type", "authentication_error").put("message", "DO_NOT_ECHO"))) }.diagnostic)
        assertEquals("anthropic_overloaded", failure { AiAnthropicStream().accept(JSONObject().put("type", "error")
            .put("error", JSONObject().put("type", "overloaded_error"))) }.diagnostic)
        assertEquals("anthropic_rate_limit", failure { AiAnthropicStream().accept(JSONObject().put("type", "error")
            .put("error", JSONObject().put("type", "rate_limit_error"))) }.diagnostic)
        val limited = AiAnthropicStream(); limited.accept(start()); limited.accept(finish("max_tokens"))
        assertEquals(AiFailure.OutputLimit, failure { limited.accept(JSONObject().put("type", "message_stop")) }.kind)
    }

    @Test fun serverToolsAndTextOrToolCountLimitsFailClosed() {
        val unknown = AiAnthropicStream(); unknown.accept(start())
        assertEquals("anthropic_unsupported_content", failure { unknown.accept(block(0, JSONObject().put("type", "server_tool_use"))) }.diagnostic)
        val text = AiAnthropicStream(); text.accept(start()); text.accept(block(0, textBlock()))
        assertEquals(AiFailure.OutputLimit, failure { text.accept(fragment(0, "text_delta", "text", "x".repeat(256001))) }.kind)
        val tools = AiAnthropicStream(); tools.accept(start())
        repeat(8) { index -> tools.accept(block(index, tool("tool_$index"))); tools.accept(stop(index)) }
        assertEquals(AiFailure.ToolLimit, failure { tools.accept(block(8, tool("tool_8"))) }.kind)
    }
}

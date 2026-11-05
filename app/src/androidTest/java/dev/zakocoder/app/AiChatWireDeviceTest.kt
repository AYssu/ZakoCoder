package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiChatWireDeviceTest {
    private fun chunk(delta: JSONObject = JSONObject(), finish: String? = null) = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))
    private fun failure(block: () -> Unit): AiTransportException = try { block(); error("Expected failure") }
        catch (error: AiTransportException) { error }
    private fun tool(arguments: String = "{}", id: String = "call_1") = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
        .put("index", 0).put("id", id).put("type", "function").put("function", JSONObject().put("name", "read_file").put("arguments", arguments))))

    @Test fun reasoningAliasesHaveStablePrecedenceAndCacheZeroIsKnown() {
        val stream = AiChatStream()
        stream.accept(chunk(JSONObject().put("reasoning", "alias")))
        stream.accept(chunk(JSONObject().put("reasoning_content", "primary").put("reasoning", "ignored").put("content", "ok"), "stop"))
        stream.accept(JSONObject().put("usage", JSONObject().put("cached_tokens", 12)))
        stream.accept(JSONObject().put("usage", JSONObject().put("prompt_tokens_details", JSONObject().put("cached_tokens", 0))))
        assertEquals(0L, stream.cachedTokens)
        assertNull(stream.inputTokens)
        stream.markDone()
        assertEquals("aliasprimary", stream.reply().reasoningContent)
        assertEquals("ok", stream.reply().text)
    }

    @Test fun reasoningDetailsMergeAndReplayOpaqueFieldsWithoutLogging() {
        val stream = AiChatStream()
        stream.accept(chunk(JSONObject().put("reasoning_details", JSONArray().put(JSONObject().put("index", 7)
            .put("type", "reasoning.encrypted").put("id", "reason_1").put("data", "opaque-").put("signature", "sig-")))))
        stream.accept(chunk(JSONObject().put("reasoning_details", JSONArray().put(JSONObject().put("index", 7)
            .put("data", "tail").put("signature", "tail"))).put("content", "done"), "stop"))
        stream.markDone()
        val array = JSONArray(stream.reply().chatReasoningDetails)
        assertEquals("opaque-tail", array.getJSONObject(0).getString("data"))
        assertEquals("sig-tail", array.getJSONObject(0).getString("signature"))
        AiChatReasoning.validateReplay(array)
        val sanitized = AiChatReasoning.diagnostic(JSONObject().put("messages", JSONArray().put(JSONObject()
            .put(CHAT_REASONING_DETAILS_KEY, array).put("reasoning_details", array)))).toString()
        assertFalse(sanitized.contains("opaque-tail")); assertFalse(sanitized.contains("sig-tail"))
    }

    @Test fun identityChangesAndInvalidDetailsAreRejected() {
        val details = AiChatReasoning()
        details.accept(JSONArray().put(JSONObject().put("index", 1).put("type", "reasoning.text").put("id", "a").put("text", "start")))
        assertEquals("chat_invalid_reasoning_details", failure {
            details.accept(JSONArray().put(JSONObject().put("index", 1).put("id", "b").put("text", "tail")))
        }.diagnostic)
        assertEquals("chat_invalid_reasoning_details", failure {
            AiChatReasoning.validateReplay(JSONArray().put(JSONObject().put("type", "reasoning.text").put("text", JSONObject())))
        }.diagnostic)
        assertEquals("chat_invalid_reasoning_details", failure {
            AiChatReasoning.validateReplay(JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", "x".repeat(1024 * 1024))))
        }.diagnostic)
    }

    @Test fun manySmallOpaqueFragmentsRetainOrderAndBoundEscapedReplay() {
        val details = AiChatReasoning()
        details.accept(JSONArray().put(JSONObject().put("index", 9).put("type", "reasoning.encrypted")))
        val piece = "opaque-chunk-"
        repeat(20000) {
            details.accept(JSONArray().put(JSONObject().put("index", 9).put("data", piece)))
        }
        val replay = details.snapshot()
        assertEquals(piece.repeat(20000), replay.getJSONObject(0).getString("data"))
        assertEquals(9, replay.getJSONObject(0).getInt("index"))
        val escaped = AiChatReasoning()
        escaped.accept(JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", "\\".repeat(600000))))
        assertEquals("chat_invalid_reasoning_details", failure { escaped.snapshot() }.diagnostic)
        val bounded = AiChatReasoning()
        bounded.accept(JSONArray().put(JSONObject().put("type", "reasoning.encrypted")))
        assertEquals("chat_invalid_reasoning_details", failure {
            bounded.accept(JSONArray().put(JSONObject().put("data", "x".repeat(1024 * 1024))))
        }.diagnostic)
    }

    @Test fun nullableReasoningFieldsRetainNullAndDoNotEraseLaterStrings() {
        val details = AiChatReasoning()
        details.accept(JSONArray().put(JSONObject().put("index", 3).put("type", "reasoning.text")
            .put("id", JSONObject.NULL).put("format", JSONObject.NULL).put("signature", JSONObject.NULL).put("text", JSONObject.NULL)))
        assertTrue(details.snapshot().getJSONObject(0).isNull("text"))
        details.accept(JSONArray().put(JSONObject().put("index", 3).put("id", "r_3").put("text", "public-").put("signature", "opaque")))
        details.accept(JSONArray().put(JSONObject().put("index", 3).put("id", JSONObject.NULL).put("text", JSONObject.NULL).put("signature", JSONObject.NULL)))
        details.accept(JSONArray().put(JSONObject().put("index", 3).put("text", "tail")))
        val saved = details.snapshot().getJSONObject(0)
        assertEquals("r_3", saved.getString("id")); assertEquals("public-tail", saved.getString("text"))
        assertEquals("opaque", saved.getString("signature")); assertTrue(saved.isNull("format"))
    }

    @Test fun explicitJsonNullReasoningFieldsReplayWithoutStringCoercion() {
        val raw = JSONArray().put(JSONObject().put("type", "reasoning.summary").put("summary", "summary")
            .put("id", JSONObject.NULL).put("format", JSONObject.NULL))
            .put(JSONObject().put("type", "reasoning.encrypted").put("data", "opaque")
                .put("id", JSONObject.NULL).put("format", JSONObject.NULL))
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("role", "assistant").put("content", "answer").put("reasoning_details", raw))))
        val replay = JSONArray(AiChatWire.complete(body).reply().chatReasoningDetails)
        assertTrue(replay.getJSONObject(0).has("id")); assertSame(JSONObject.NULL, replay.getJSONObject(0).get("id"))
        assertSame(JSONObject.NULL, replay.getJSONObject(1).get("format"))
        assertEquals("opaque", replay.getJSONObject(1).getString("data"))
        AiChatReasoning.validateReplay(replay)
        for ((type, required) in listOf("reasoning.summary" to "summary", "reasoning.encrypted" to "data")) {
            assertEquals("chat_invalid_reasoning_details", failure {
                AiChatReasoning().accept(JSONArray().put(JSONObject().put("type", type).put(required, JSONObject.NULL)))
            }.diagnostic)
            assertEquals("chat_invalid_reasoning_details", failure {
                AiChatReasoning.validateReplay(JSONArray().put(JSONObject().put("type", type)))
            }.diagnostic)
        }
    }

    @Test fun toolsRequireTerminatorMatchingFinishAndObjectArguments() {
        val stream = AiChatStream()
        stream.accept(chunk(tool(), "tool_calls"))
        assertEquals("missing_done", failure { stream.reply() }.diagnostic)
        stream.markDone(); assertEquals("call_1", stream.reply().calls.single().id)
        val truncated = AiChatStream().apply { accept(chunk(tool(), "length")) }
        assertEquals(AiFailure.OutputLimit, failure { truncated.markDone() }.kind)
        val malformed = AiChatStream().apply { accept(chunk(tool("{broken"), "tool_calls")) }
        assertEquals("invalid_tool_arguments", failure { malformed.markDone() }.diagnostic)
        val wrongFinish = AiChatStream().apply { accept(chunk(tool(), "stop")) }
        assertEquals("unexpected_finish_reason", failure { wrongFinish.markDone() }.diagnostic)
    }

    @Test fun explicitJsonCompletionUsesMessageAndValidatesFinish() {
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "JSON response")
            .put("reasoning", "public thought")).put("finish_reason", "stop")))
        assertEquals("JSON response", AiChatWire.complete(body).reply().text)
        body.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        assertEquals(AiFailure.OutputLimit, failure { AiChatWire.complete(body) }.kind)
    }

    @Test fun malformedCacheNumbersAndDuplicateToolsCannotBecomeSuccess() {
        assertEquals("invalid_usage", failure {
            AiChatStream().accept(JSONObject().put("usage", JSONObject().put("cached_tokens", "12")))
        }.diagnostic)
        val stream = AiChatStream()
        val tools = tool().getJSONArray("tool_calls")
        tools.put(JSONObject(tools.getJSONObject(0).toString()).put("index", 1))
        stream.accept(chunk(JSONObject().put("tool_calls", tools), "tool_calls"))
        assertEquals("duplicate_tool_id", failure { stream.markDone() }.diagnostic)
    }
}

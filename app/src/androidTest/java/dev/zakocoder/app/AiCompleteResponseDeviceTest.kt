package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Complete JSON fixtures only: no device state, network access or model credentials. */
class AiCompleteResponseDeviceTest {
    private fun json(value: String) = JSONObject(value)
    private fun decode(protocol: AiApiProtocol, body: JSONObject) = AiCompleteResponse.decode(protocol, body)
    private fun chat(tool: Boolean = false) = json(if (tool) """{
        "choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
        "tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}}]}}]}
        """ else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hello"}}]}""")
    private fun responses(tool: Boolean = false) = json(if (tool) """{
        "status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":"call_1",
        "name":"read_file","arguments":"{\"path\":\"a.kt\"}","status":"completed"}]}
        """ else """{"status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant",
        "content":[{"type":"output_text","text":"Hello"}],"status":"completed"}]}""")
    private fun anthropic(tool: Boolean = false) = json(if (tool) """{
        "type":"message","role":"assistant","stop_reason":"tool_use",
        "content":[{"type":"tool_use","id":"call_1","name":"read_file","input":{"path":"a.kt"}}]}
        """ else """{"type":"message","role":"assistant","stop_reason":"end_turn",
        "content":[{"type":"text","text":"Hello"}]}""")
    private fun gemini(tool: Boolean = false) = json(if (tool) """{
        "candidates":[{"index":0,"finishReason":"STOP","content":{"role":"model",
        "parts":[{"functionCall":{"id":"call_1","name":"read_file","args":{"path":"a.kt"}}}]}}]}
        """ else """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"Hello"}]}}]}""")
    private fun fixtures(tool: Boolean = false) = listOf(
        AiApiProtocol.ChatCompletions to chat(tool), AiApiProtocol.Responses to responses(tool),
        AiApiProtocol.Anthropic to anthropic(tool), AiApiProtocol.Gemini to gemini(tool))
    private fun rejected(protocol: AiApiProtocol, body: JSONObject): AiTransportException {
        try {
            decode(protocol, body).reply()
            fail("Invalid completion yielded a reply for $protocol")
        } catch (error: AiTransportException) { return error }
        throw AssertionError("Expected protocol failure")
    }

    @Test fun allFourProtocolsDecodeTextAndKeepAbsentUsageUnknown() {
        fixtures().forEach { (protocol, body) ->
            val stream = decode(protocol, body)
            assertTrue(protocol.name, stream.done)
            assertEquals(protocol.name, "Hello", stream.reply().text)
            assertTrue(protocol.name, stream.reply().calls.isEmpty())
            assertEquals(protocol.name, "stop", stream.finish)
            assertNull(protocol.name, stream.inputTokens)
            assertNull(protocol.name, stream.outputTokens)
            assertNull(protocol.name, stream.cachedTokens)
        }
    }

    @Test fun allFourProtocolsReturnValidatedToolIdentityAndJsonArguments() {
        fixtures(true).forEach { (protocol, body) ->
            val stream = decode(protocol, body)
            val call = stream.reply().calls.single()
            assertEquals(protocol.name, "call_1", call.id)
            assertEquals(protocol.name, "read_file", call.name)
            assertEquals(protocol.name, "a.kt", JSONObject(call.arguments).getString("path"))
            assertEquals(protocol.name, "tool_calls", stream.finish)
        }
    }

    @Test fun explicitZeroUsageStaysZeroAcrossAllProtocols() {
        val bodies = listOf(
            AiApiProtocol.ChatCompletions to chat().put("usage", json("""{"prompt_tokens":0,"completion_tokens":0,"prompt_tokens_details":{"cached_tokens":0}}""")),
            AiApiProtocol.Responses to responses().put("usage", json("""{"input_tokens":0,"output_tokens":0,"input_tokens_details":{"cached_tokens":0}}""")),
            AiApiProtocol.Anthropic to anthropic().put("usage", json("""{"input_tokens":0,"output_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}""")),
            AiApiProtocol.Gemini to gemini().put("usageMetadata", json("""{"promptTokenCount":0,"candidatesTokenCount":0,"thoughtsTokenCount":0,"cachedContentTokenCount":0}""")))
        bodies.forEach { (protocol, body) ->
            val stream = decode(protocol, body)
            assertEquals(protocol.name, 0L, stream.inputTokens)
            assertEquals(protocol.name, 0L, stream.outputTokens)
            assertEquals(protocol.name, 0L, stream.cachedTokens)
        }
    }

    @Test fun responsesCompleteReasoningContentSurvivesSnapshotAndReplay() {
        val item = json("""{"type":"reasoning","id":"rs_1","summary":[],
            "content":[{"type":"reasoning_text","text":"Visible reasoning"}],"encrypted_content":"OPAQUE_FIXTURE"}""")
        val body = responses().put("output", JSONArray().put(item).put(responses().getJSONArray("output").getJSONObject(0)))
        val reply = decode(AiApiProtocol.Responses, body).reply()
        assertEquals("Visible reasoning", reply.reasoningContent)
        val replay = AiResponsesWire.request(AiConfig(), JSONArray().put(JSONObject().put("role", "assistant")
            .put(RESPONSES_OUTPUT_KEY, JSONArray(reply.responsesOutput))), JSONArray())
        assertEquals(item.toString(), replay.getJSONArray("input").getJSONObject(0).toString())
        assertFalse(AiResponsesWire.diagnostic(replay).toString().contains("OPAQUE_FIXTURE"))
        assertFalse(AiResponsesWire.diagnostic(replay).toString().contains("Visible reasoning"))
    }

    @Test fun anthropicThinkingSignatureAndRedactedContentStayInProviderHistory() {
        val body = anthropic().put("content", JSONArray()
            .put(json("""{"type":"thinking","thinking":"Visible thought","signature":"SIGNATURE_FIXTURE"}"""))
            .put(json("""{"type":"redacted_thinking","data":"REDACTED_FIXTURE"}"""))
            .put(json("""{"type":"text","text":"Hello"}""")))
        val reply = decode(AiApiProtocol.Anthropic, body).reply()
        assertEquals("Visible thought", reply.reasoningContent)
        assertEquals("Hello", reply.text)
        assertEquals("Anthropic", reply.providerProtocol)
        val blocks = JSONArray(reply.providerOutput)
        assertEquals("SIGNATURE_FIXTURE", blocks.getJSONObject(0).getString("signature"))
        assertEquals("REDACTED_FIXTURE", blocks.getJSONObject(1).getString("data"))
        assertEquals("anthropic_missing_thinking_signature", rejected(AiApiProtocol.Anthropic,
            anthropic().put("content", JSONArray().put(json("""{"type":"thinking","thinking":"Thought","signature":""}""")))).diagnostic)
    }

    @Test fun chatReasoningDetailsRemainOpaqueAndSeparateFromVisibleReasoning() {
        val body = chat()
        body.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .put("reasoning_content", "Visible thought")
            .put("reasoning_details", JSONArray().put(json("""{"type":"reasoning.encrypted","id":"r_1","data":"OPAQUE_CHAT_FIXTURE","signature":"SIGNATURE_CHAT_FIXTURE"}""")))
        val reply = decode(AiApiProtocol.ChatCompletions, body).reply()
        assertEquals("Visible thought", reply.reasoningContent)
        assertFalse(reply.text.contains("OPAQUE_CHAT_FIXTURE"))
        val details = JSONArray(reply.chatReasoningDetails).getJSONObject(0)
        assertEquals("OPAQUE_CHAT_FIXTURE", details.getString("data"))
        assertEquals("SIGNATURE_CHAT_FIXTURE", details.getString("signature"))
        assertFalse(AiChatReasoning.diagnostic(body).toString().contains("OPAQUE_CHAT_FIXTURE"))
    }

    @Test fun truncatedAndUnfinishedCompletionsCannotReturnTools() {
        val chat = chat(true)
        chat.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        assertEquals(AiFailure.OutputLimit, rejected(AiApiProtocol.ChatCompletions, chat).kind)
        chat.getJSONArray("choices").getJSONObject(0).put("finish_reason", JSONObject.NULL)
        assertEquals("unexpected_finish_reason", rejected(AiApiProtocol.ChatCompletions, chat).diagnostic)
        val responses = responses(true).put("status", "incomplete")
            .put("incomplete_details", json("""{"reason":"max_output_tokens"}"""))
        assertEquals(AiFailure.OutputLimit, rejected(AiApiProtocol.Responses, responses).kind)
        responses.put("status", "in_progress")
        assertEquals("responses_invalid_completion", rejected(AiApiProtocol.Responses, responses).diagnostic)
        assertEquals(AiFailure.OutputLimit, rejected(AiApiProtocol.Anthropic, anthropic(true).put("stop_reason", "max_tokens")).kind)
        val gemini = gemini(true)
        gemini.getJSONArray("candidates").getJSONObject(0).put("finishReason", "MAX_TOKENS")
        assertEquals(AiFailure.OutputLimit, rejected(AiApiProtocol.Gemini, gemini).kind)
        gemini.getJSONArray("candidates").getJSONObject(0).remove("finishReason")
        assertEquals("missing_done", rejected(AiApiProtocol.Gemini, gemini).diagnostic)
    }

    @Test fun malformedToolArgumentsNeverProduceRepliesForAnyProtocol() {
        val chat = chat(true)
        chat.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", "{broken")
        val responses = responses(true)
        responses.getJSONArray("output").getJSONObject(0).put("arguments", "{broken")
        val anthropic = anthropic(true)
        anthropic.getJSONArray("content").getJSONObject(0).put("input", "not-an-object")
        val gemini = gemini(true)
        gemini.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall").put("args", "not-an-object")
        listOf(AiApiProtocol.ChatCompletions to chat, AiApiProtocol.Responses to responses,
            AiApiProtocol.Anthropic to anthropic, AiApiProtocol.Gemini to gemini).forEach { (protocol, body) ->
            try {
                decode(protocol, body).reply()
                fail("Malformed tool yielded a reply for $protocol")
            } catch (_: AiTransportException) {
                // Typed protocol failure: no executable reply escapes.
            } catch (_: org.json.JSONException) {
                // Strict Android JSON shape validation also rejects invalid tool payloads.
            }
        }
    }

    @Test fun geminiSafetyBlocksRejectBothPromptAndCandidateDespitePresentTools() {
        assertEquals("gemini_prompt_blocked", rejected(AiApiProtocol.Gemini, gemini(true)
            .put("promptFeedback", json("""{"blockReason":"SAFETY"}"""))).diagnostic)
        val candidate = gemini(true)
        candidate.getJSONArray("candidates").getJSONObject(0).put("finishReason", "SAFETY")
        assertEquals("gemini_finish_safety", rejected(AiApiProtocol.Gemini, candidate).diagnostic)
    }
}

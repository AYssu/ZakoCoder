package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native JSON codec fixtures only. No endpoint, credentials, process or user storage. */
class AiGeminiWireDeviceTest {
    private fun config(parameters: AiParameters = AiParameters()) = AiConfig("https://example.invalid/v1beta", "fixture-gemini", "fixture-not-a-key", parameters)
    private fun tool(name: String = "read_file") = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("description", "Fixture read").put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("path", JSONObject().put("type", "string"))).put("required", JSONArray().put("path"))
            .put("additionalProperties", false)))
    private fun event(parts: JSONArray = JSONArray(), finish: String? = null, usage: JSONObject? = null) = JSONObject()
        .put("candidates", JSONArray().put(JSONObject().put("index", 0).put("content", JSONObject().put("role", "model").put("parts", parts))
            .apply { finish?.let { put("finishReason", it) } }))
        .apply { usage?.let { put("usageMetadata", it) } }
    private fun call(id: String? = null, name: String = "read_file", path: String = "main.kt", signature: String? = null) = JSONObject()
        .put("functionCall", JSONObject().put("name", name).put("args", JSONObject().put("path", path)).apply { id?.let { put("id", it) } })
        .apply { signature?.let { put("thoughtSignature", it) } }
    private fun rejection(code: String? = null, block: () -> Unit): AiTransportException {
        try { block(); throw AssertionError("Expected explicit protocol/config rejection") }
        catch (error: AiTransportException) { if (code != null) assertEquals(code, error.diagnostic); return error }
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test fun nativeRequestMapsSystemImagesParametersAndForcedTool() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Project rules"))
            .put(JSONObject().put("role", "developer").put("content", "More rules"))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "Inspect image"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,YWJj")))))
        val body = AiGeminiWire.request(config(AiParameters(maxTokens = 1024, temperature = 0.5, topP = 0.9,
            seed = 42, frequencyPenalty = 0.25, presencePenalty = 0.5, thinking = AiThinking.Enabled,
            reasoningEffort = AiReasoningEffort.High)), messages, JSONArray().put(tool()), "read_file")
        assertFalse(body.has("model")); assertFalse(body.has("messages"))
        assertEquals(2, body.getJSONObject("systemInstruction").getJSONArray("parts").length())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("Inspect image", parts.getJSONObject(0).getString("text"))
        assertEquals("image/png", parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("YWJj", parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        val generation = body.getJSONObject("generationConfig")
        assertEquals(1024, generation.getInt("maxOutputTokens")); assertEquals(42, generation.getInt("seed"))
        assertEquals(0.25, generation.getDouble("frequencyPenalty"), 0.0)
        assertEquals(0.5, generation.getDouble("presencePenalty"), 0.0)
        assertTrue(generation.getJSONObject("thinkingConfig").getBoolean("includeThoughts"))
        assertEquals("HIGH", generation.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val declaration = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertFalse(declaration.getJSONObject("parametersJsonSchema").getBoolean("additionalProperties"))
        assertEquals("ANY", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertEquals("read_file", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getJSONArray("allowedFunctionNames").getString(0))
    }

    @Test fun signedThoughtAndFunctionPartsReplayUnchangedWithNativeResultIdentity() {
        val delivered = StringBuilder(); val thoughts = StringBuilder()
        val decoder = AiGeminiStream({ delivered.append(it) }, { thoughts.append(it) })
        val thought = JSONObject().put("text", "Public provider summary").put("thought", true).put("thoughtSignature", "THOUGHT_SIGNATURE")
        val function = call("native-1", signature = "FUNCTION_SIGNATURE")
        decoder.accept(event(JSONArray().put(thought)))
        decoder.accept(event(JSONArray().put(function), "STOP", JSONObject().put("promptTokenCount", 100).put("candidatesTokenCount", 12)
            .put("thoughtsTokenCount", 7).put("cachedContentTokenCount", 40)))
        val reply = decoder.reply()
        assertEquals("", delivered.toString()); assertEquals("Public provider summary", thoughts.toString())
        assertEquals(100L, decoder.inputTokens); assertEquals(19L, decoder.outputTokens); assertEquals(40L, decoder.cachedTokens)
        assertEquals("tool_calls", decoder.finish)
        val assistant = reply.asAssistantMessage()
        val saved = assistant.getJSONObject("zako_provider_output")
        assertEquals("Gemini", saved.getString("protocol"))
        assertEquals(thought.toString(), saved.getJSONArray("output").getJSONObject(0).toString())
        assertEquals(function.toString(), saved.getJSONArray("output").getJSONObject(1).toString())
        val body = AiGeminiWire.request(config(), JSONArray().put(JSONObject().put("role", "user").put("content", "Read main.kt"))
            .put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", reply.calls.single().id).put("content", "actual file content")), JSONArray().put(tool()))
        val contents = body.getJSONArray("contents")
        val replay = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals(saved.getJSONArray("output").toString(), replay.toString())
        val result = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("native-1", result.getString("id")); assertEquals("read_file", result.getString("name"))
        assertEquals("actual file content", result.getJSONObject("response").getString("output"))
    }

    @Test fun missingNativeCallIdGetsUniqueLocalIdentityWithoutInventingNativeId() {
        val a = AiGeminiStream(); val b = AiGeminiStream()
        a.accept(event(JSONArray().put(call()), "STOP")); b.accept(event(JSONArray().put(call()), "STOP"))
        assertNotEquals(a.reply().calls.single().id, b.reply().calls.single().id)
        val reply = a.reply()
        val body = AiGeminiWire.request(config(), JSONArray().put(reply.asAssistantMessage()).put(JSONObject().put("role", "tool")
            .put("tool_call_id", reply.calls.single().id).put("content", "{\"content\":\"read\"}")), JSONArray())
        val result = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertFalse(result.has("id")); assertEquals("read", result.getJSONObject("response").getString("content"))
    }

    @Test fun chatToolHistoryConvertsParallelResultsAndPreservesSeparateRounds() {
        val calls = JSONArray().put(JSONObject().put("id", "first").put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"a\"}")))
            .put(JSONObject().put("id", "second").put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"b\"}")))
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("content", "Reading").put("tool_calls", calls))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "first").put("content", "A"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "second").put("content", "B"))
            .put(JSONObject().put("role", "assistant").put("content", "Done"))
        val contents = AiGeminiWire.request(config(), messages, JSONArray()).getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals(3, contents.getJSONObject(0).getJSONArray("parts").length())
        val responses = contents.getJSONObject(1).getJSONArray("parts").objects().map { it.getJSONObject("functionResponse") }
        assertEquals(listOf("first", "second"), responses.map { it.getString("id") })
        rejection("gemini_unknown_tool_result") { AiGeminiWire.request(config(), JSONArray().put(JSONObject().put("role", "tool").put("tool_call_id", "unknown").put("content", "x")), JSONArray()) }
    }

    @Test fun noFinishNeverExposesExecutableReplyAndNonStopFinishesFail() {
        val incomplete = AiGeminiStream()
        incomplete.accept(event(JSONArray().put(call())))
        assertFalse(incomplete.done)
        rejection("missing_done") { incomplete.reply() }
        val length = rejection("finish_length") { AiGeminiStream().accept(event(JSONArray().put(call()), "MAX_TOKENS")) }
        assertEquals(AiFailure.OutputLimit, length.kind)
        rejection("gemini_finish_safety") { AiGeminiStream().accept(event(finish = "SAFETY")) }
        rejection("gemini_invalid_finish_reason") { AiGeminiStream().accept(event(finish = "unexpected arbitrary provider content")) }
        rejection("gemini_prompt_blocked") { AiGeminiStream().accept(JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))) }
    }

    @Test fun streamCapsDuplicateIdsArgumentsTextAndUnsupportedNativeParts() {
        val duplicate = AiGeminiStream()
        duplicate.accept(event(JSONArray().put(call("same"))))
        rejection("duplicate_tool_id") { duplicate.accept(event(JSONArray().put(call("same")))) }
        rejection("tool_call_limit") { AiGeminiStream().accept(event(JSONArray().apply { repeat(9) { put(call("call-$it")) } })) }
        rejection("gemini_text_limit") { AiGeminiStream().accept(event(JSONArray().put(JSONObject().put("text", "a".repeat(256001))))) }
        rejection("gemini_output_limit") { AiGeminiStream().accept(event(JSONArray().put(call(path = "x".repeat(8 * 1024 * 1024))))) }
        rejection("gemini_unsupported_output_part") { AiGeminiStream().accept(event(JSONArray().put(JSONObject().put("inlineData", JSONObject().put("data", "secret"))))) }
        rejection("gemini_partial_tool_arguments_unsupported") { AiGeminiStream().accept(event(JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "read_file").put("args", JSONObject()).put("willContinue", true))))) }
    }

    @Test fun unsignedTextFragmentsCoalesceButSignedPartsStayExact() {
        val decoder = AiGeminiStream()
        decoder.accept(event(JSONArray().put(JSONObject().put("text", "hello "))))
        decoder.accept(event(JSONArray().put(JSONObject().put("text", "world"))))
        val signed = JSONObject().put("text", "!").put("thoughtSignature", "signed")
        decoder.accept(event(JSONArray().put(signed), "STOP"))
        assertEquals("hello world!", decoder.text)
        val parts = JSONArray(decoder.reply().providerOutput!!)
        assertEquals(2, parts.length()); assertEquals("hello world", parts.getJSONObject(0).getString("text"))
        assertEquals(signed.toString(), parts.getJSONObject(1).toString())
    }

    @Test fun fineFragmentsUseBoundedReplayPartsAndPreserveSignedBoundaries() {
        val decoder = AiGeminiStream()
        val fragment = event(JSONArray().put(JSONObject().put("text", "x")))
        repeat(10000) { decoder.accept(fragment) }
        assertEquals(10000, decoder.textChars)
        val signed = JSONObject().put("text", "!").put("thoughtSignature", "opaque-fixture")
        decoder.accept(event(JSONArray().put(signed).put(signed)))
        val thought = event(JSONArray().put(JSONObject().put("text", "r").put("thought", true)))
        repeat(10000) { decoder.accept(thought) }
        decoder.accept(event(JSONArray().put(JSONObject().put("text", "end")), "STOP"))

        assertEquals(10005, decoder.textChars)
        assertEquals(10000, decoder.reasoningChars)
        val reply = decoder.reply()
        val parts = JSONArray(reply.providerOutput!!)
        assertEquals(5, parts.length())
        assertEquals("x".repeat(10000), parts.getJSONObject(0).getString("text"))
        assertEquals(signed.toString(), parts.getJSONObject(1).toString())
        assertEquals(signed.toString(), parts.getJSONObject(2).toString())
        assertEquals("r".repeat(10000), parts.getJSONObject(3).getString("text"))
        assertTrue(parts.getJSONObject(3).getBoolean("thought"))
        assertEquals("end", parts.getJSONObject(4).getString("text"))
        assertEquals("x".repeat(10000) + "!!end", reply.text)
        assertEquals(reply.providerOutput, decoder.reply().providerOutput)
    }

    @Test fun pendingTextStillCountsTowardReplayPartLimit() {
        val decoder = AiGeminiStream()
        repeat(8192) { index ->
            decoder.accept(event(JSONArray().put(JSONObject().put("text", "x").put("thought", index % 2 == 0))))
        }
        rejection("gemini_part_limit") {
            decoder.accept(event(JSONArray().put(JSONObject().put("text", "overflow").put("thought", true))))
        }
    }

    @Test fun diagnosticsScrubThoughtsAndSignaturesWithoutMutatingReplay() {
        val original = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "private-looking thought fixture")
            .put("thought", true).put("thoughtSignature", "opaque-fixture")).put(JSONObject().put("text", "public answer")))
        val diagnostic = AiGeminiWire.diagnostic(original).toString()
        assertFalse(diagnostic.contains("opaque-fixture")); assertFalse(diagnostic.contains("private-looking thought fixture"))
        assertTrue(diagnostic.contains("public answer")); assertTrue(original.toString().contains("opaque-fixture"))
    }

    @Test fun unsupportedConfigurationAndExternalImagesFailExplicitly() {
        assertEquals(AiFailure.RequestConfiguration, rejection("gemini_unsupported_thinking_parameters") {
            AiGeminiWire.request(config(AiParameters(reasoningEffort = AiReasoningEffort.Max)), JSONArray(), JSONArray())
        }.kind)
        rejection("gemini_unsupported_thinking_parameters") { AiGeminiWire.request(config(AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.Low)), JSONArray(), JSONArray()) }
        assertEquals(AiFailure.Image, rejection("gemini_inline_image_required") { AiGeminiWire.request(config(), JSONArray()
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "https://example.invalid/image.png"))))), JSONArray()) }.kind)
        rejection("gemini_invalid_tool_choice") { AiGeminiWire.request(config(), JSONArray(), JSONArray().put(tool()), "not_declared") }
        val disabled = AiGeminiWire.request(config(AiParameters(thinking = AiThinking.Disabled)), JSONArray(), JSONArray())
        assertEquals(0, disabled.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test fun actualUsageKeepsUnknownAndRejectsNegativeOrOverflowValues() {
        val unknown = AiGeminiStream(); unknown.accept(event(finish = "STOP"))
        assertNull(unknown.inputTokens); assertNull(unknown.outputTokens)
        rejection("gemini_invalid_usage") { AiGeminiStream().accept(event(usage = JSONObject().put("promptTokenCount", -1))) }
        rejection("gemini_invalid_usage") { AiGeminiStream().accept(event(usage = JSONObject().put("candidatesTokenCount", Long.MAX_VALUE).put("thoughtsTokenCount", 1))) }
    }
}

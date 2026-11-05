package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure protocol fixtures on Android's real JSON implementation; no external service or secrets. */
class AiResponsesWireDeviceTest {
    private fun callItem(id: String = "fc_1", callId: String = "call_1", arguments: String = "{\"path\":\"a.kt\"}") = JSONObject()
        .put("type", "function_call").put("id", id).put("status", "completed")
        .put("call_id", callId).put("name", "read_file").put("arguments", arguments)
    private fun messageItem(text: String = "Hello") = JSONObject().put("type", "message").put("id", "msg_1")
        .put("status", "completed").put("role", "assistant").put("content", JSONArray()
            .put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))
    private fun reasoningItem() = JSONObject().put("type", "reasoning").put("id", "rs_1")
        .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "Public summary")))
        .put("encrypted_content", "OPAQUE_FIXTURE_NOT_A_CREDENTIAL")
    private fun completed(vararg items: JSONObject) = JSONObject().put("type", "response.completed")
        .put("response", JSONObject().put("status", "completed").put("output", JSONArray(items.toList()))
            .put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 25)
                .put("input_tokens_details", JSONObject().put("cached_tokens", 40))))
    private fun added(index: Int, item: JSONObject) = JSONObject().put("type", "response.output_item.added")
        .put("output_index", index).put("item", item)
    private fun fragment(type: String, index: Int, id: String, value: String) = JSONObject().put("type", type)
        .put("output_index", index).put("item_id", id).put("delta", value)
    private fun failure(block: () -> Unit): AiTransportException = try { block(); error("Expected protocol failure") }
        catch (error: AiTransportException) { error }

    @Test fun requestMapsMessagesImagesToolsAndExplicitGenerationOptions() {
        val config = AiConfig(model = "fixture", parameters = AiParameters(maxTokens = 2048, temperature = 0.5,
            topP = 0.9, thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.Max))
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "Rules"))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "Inspect image"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,AAAA").put("detail", "low")))))
            .put(JSONObject().put("role", "assistant").put("content", "Inspecting").put("tool_calls", JSONArray()
                .put(JSONObject().put("id", "call_1").put("type", "function").put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "file content"))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_file")
            .put("description", "Read file").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
        val body = AiResponsesWire.request(config, messages, tools, "read_file")
        assertFalse(body.getBoolean("store")); assertFalse(body.has("previous_response_id")); assertFalse(body.has("messages"))
        assertEquals("reasoning.encrypted_content", body.getJSONArray("include").getString(0))
        assertEquals(2048, body.getInt("max_output_tokens")); assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))
        assertEquals("read_file", body.getJSONObject("tool_choice").getString("name"))
        assertFalse(body.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
        val input = body.getJSONArray("input")
        assertEquals("input_image", input.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("low", input.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("detail"))
        assertEquals("function_call", input.getJSONObject(3).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(4).getString("type"))
        assertEquals("call_1", input.getJSONObject(4).getString("call_id"))
    }

    @Test fun opaqueHistoryReplaysExactlyOnceAndDiagnosticOmitsItsContent() {
        val output = JSONArray().put(reasoningItem()).put(callItem())
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("content", "duplicate text must not replay")
            .put(RESPONSES_OUTPUT_KEY, output).put("tool_calls", JSONArray()))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "actual result"))
        val body = AiResponsesWire.request(AiConfig(), messages, JSONArray())
        val input = body.getJSONArray("input")
        assertEquals(3, input.length()); assertEquals(output.getJSONObject(0).toString(), input.getJSONObject(0).toString())
        assertFalse(body.toString().contains("duplicate text must not replay"))
        assertFalse(AiResponsesWire.diagnostic(body).toString().contains("OPAQUE_FIXTURE_NOT_A_CREDENTIAL"))
        assertTrue(body.toString().contains("OPAQUE_FIXTURE_NOT_A_CREDENTIAL"))
    }

    @Test fun unsupportedParametersAndConflictingThinkingFailWithoutEchoingValues() {
        val parameters = listOf(AiParameters(seed = 77), AiParameters(frequencyPenalty = 0.1), AiParameters(presencePenalty = 0.1),
            AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.High))
        parameters.forEach { options ->
            val error = failure { AiResponsesWire.request(AiConfig(parameters = options), JSONArray(), JSONArray()) }
            assertEquals("responses_unsupported_parameters", error.diagnostic)
            assertEquals(AiFailure.RequestConfiguration, error.kind)
        }
        assertEquals("none", AiResponsesWire.request(AiConfig(parameters = AiParameters(thinking = AiThinking.Disabled)),
            JSONArray(), JSONArray()).getJSONObject("reasoning").getString("effort"))
    }

    @Test fun interleavedReasoningTextAndArgumentsCompleteOnlyAtResponseCompleted() {
        val text = StringBuilder(); val reasoning = StringBuilder(); var drafts = emptyList<AiCallDraft>()
        val decoder = AiResponsesStream({ text.append(it) }, { reasoning.append(it) }, { drafts = it })
        decoder.accept(added(0, JSONObject().put("id", "rs_1").put("type", "reasoning").put("summary", JSONArray())))
        decoder.accept(fragment("response.reasoning_summary_text.delta", 0, "rs_1", "Public ").put("summary_index", 0))
        decoder.accept(fragment("response.reasoning_summary_text.delta", 0, "rs_1", "summary").put("summary_index", 0))
        decoder.accept(added(1, callItem(arguments = "").put("status", "in_progress")))
        decoder.accept(fragment("response.function_call_arguments.delta", 1, "fc_1", "{\"path\":"))
        decoder.accept(fragment("response.function_call_arguments.delta", 1, "fc_1", "\"a.kt\"}"))
        assertTrue(drafts.single().arguments.contains("a.kt")); assertFalse(decoder.done)
        assertEquals("missing_done", failure { decoder.reply() }.diagnostic)
        decoder.accept(added(2, messageItem("").put("status", "in_progress")))
        decoder.accept(fragment("response.output_text.delta", 2, "msg_1", "Hel").put("content_index", 0))
        decoder.accept(fragment("response.output_text.delta", 2, "msg_1", "lo").put("content_index", 0))
        decoder.accept(completed(reasoningItem(), callItem(), messageItem()))
        val reply = decoder.reply()
        assertEquals("Hello", text.toString()); assertEquals("Public summary", reasoning.toString())
        assertEquals("Hello", reply.text); assertEquals("call_1", reply.calls.single().id)
        assertEquals(100L, decoder.inputTokens); assertEquals(25L, decoder.outputTokens); assertEquals(40L, decoder.cachedTokens)
        assertEquals("tool_calls", decoder.finish); assertTrue(decoder.done)
        assertTrue(reply.responsesOutput!!.contains("OPAQUE_FIXTURE_NOT_A_CREDENTIAL"))
    }

    @Test fun gatewayMetadataDoesNotCompleteOrDiscardOutputAndTools() {
        val decoder = AiResponsesStream()
        decoder.accept(added(0, callItem(arguments = "").put("status", "in_progress")))
        decoder.accept(fragment("response.function_call_arguments.delta", 0, "fc_1", "{\"path\":\"a.kt\"}"))
        decoder.accept(JSONObject().put("type", "response.metadata").put("metadata", JSONObject().put("gateway", "fixture")))
        assertFalse(decoder.done)
        assertEquals("missing_done", failure { decoder.reply() }.diagnostic)
        decoder.accept(completed(callItem()))
        assertEquals("call_1", decoder.reply().calls.single().id)
        assertEquals("{\"path\":\"a.kt\"}", decoder.reply().calls.single().arguments)
        val unknown = AiResponsesStream()
        assertEquals("responses_unsupported_event", failure {
            unknown.accept(JSONObject().put("type", "response.execute_unknown"))
        }.diagnostic)
    }

    @Test fun completedSnapshotOnlyPublishesTextAndDoneSnapshotsDoNotDuplicateDeltas() {
        val visible = StringBuilder(); val decoder = AiResponsesStream({ visible.append(it) })
        decoder.accept(completed(messageItem("snapshot-only")))
        assertEquals("snapshot-only", decoder.reply().text); assertEquals("snapshot-only", visible.toString())
        val toolDecoder = AiResponsesStream()
        toolDecoder.accept(added(0, callItem(arguments = "").put("status", "in_progress")))
        toolDecoder.accept(fragment("response.function_call_arguments.delta", 0, "fc_1", "{\"path\":\"a.kt\"}"))
        toolDecoder.accept(JSONObject().put("type", "response.function_call_arguments.done").put("output_index", 0)
            .put("item_id", "fc_1").put("arguments", "{\"path\":\"a.kt\"}"))
        toolDecoder.accept(completed(callItem()))
        assertEquals("{\"path\":\"a.kt\"}", toolDecoder.reply().calls.single().arguments)
    }

    @Test fun mismatchedIdentityIncompleteOutputsBuiltinsAndInvalidArgumentsNeverExecute() {
        val decoder = AiResponsesStream()
        decoder.accept(added(0, callItem(arguments = "").put("status", "in_progress")))
        assertEquals("responses_item_identity_mismatch", failure {
            decoder.accept(fragment("response.function_call_arguments.delta", 0, "wrong", "{}"))
        }.diagnostic)
        assertEquals("responses_unsupported_output_item", failure {
            AiResponsesStream().accept(completed(JSONObject().put("id", "web_1").put("type", "web_search_call")))
        }.diagnostic)
        assertEquals("responses_incomplete_output_item", failure {
            AiResponsesStream().accept(completed(callItem().put("status", "in_progress")))
        }.diagnostic)
        assertEquals(AiFailure.OutputLimit, failure {
            AiResponsesStream().accept(JSONObject().put("type", "response.incomplete").put("response", JSONObject()
                .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))))
        }.kind)
        assertEquals("responses_failed", failure { AiResponsesStream().accept(JSONObject().put("type", "error").put("message", "DO_NOT_ECHO")) }.diagnostic)
        assertEquals("responses_server_error", failure { AiResponsesStream().accept(JSONObject().put("type", "response.failed")
            .put("response", JSONObject().put("error", JSONObject().put("code", "server_error").put("message", "DO_NOT_ECHO")))) }.diagnostic)
        assertEquals("responses_rate_limit", failure { AiResponsesStream().accept(JSONObject().put("type", "error").put("code", "rate_limit_exceeded")) }.diagnostic)
        assertEquals("responses_invalid_output_role", failure {
            AiResponsesWire.request(AiConfig(), JSONArray().put(JSONObject().put("role", "assistant")
                .put(RESPONSES_OUTPUT_KEY, JSONArray().put(messageItem().put("role", "system")))), JSONArray())
        }.diagnostic)
        try { AiResponsesStream().accept(completed(callItem(arguments = "{broken"))); fail("Invalid arguments") }
        catch (_: org.json.JSONException) { /* No AiReply can escape. */ }
    }

    @Test fun textAndFunctionCountLimitsApplyBeforeReturningAnyTool() {
        val textDecoder = AiResponsesStream()
        textDecoder.accept(added(0, messageItem("").put("status", "in_progress")))
        assertEquals(AiFailure.OutputLimit, failure { textDecoder.accept(fragment("response.output_text.delta", 0, "msg_1",
            "x".repeat(256001)).put("content_index", 0)) }.kind)
        val decoder = AiResponsesStream()
        repeat(8) { decoder.accept(added(it, callItem("fc_$it", "call_$it", "").put("status", "in_progress"))) }
        assertEquals(AiFailure.ToolLimit, failure { decoder.accept(added(8, callItem("fc_8", "call_8", ""))) }.kind)
    }
    @Test fun publicReasoningContentStreamsReconcilesReplaysAndRedactsSeparatelyFromSummary() {
        val shown = StringBuilder()
        val decoder = AiResponsesStream(reasoningDelta = { shown.append(it) })
        val item = reasoningItem().put("content", JSONArray().put(JSONObject()
            .put("type", "reasoning_text").put("text", "Public reasoning")))
        decoder.accept(added(0, JSONObject().put("type", "reasoning").put("id", "rs_1").put("summary", JSONArray())))
        decoder.accept(fragment("response.reasoning_text.delta", 0, "rs_1", "Public ").put("content_index", 0))
        decoder.accept(JSONObject().put("type", "response.reasoning_text.done").put("output_index", 0)
            .put("item_id", "rs_1").put("content_index", 0).put("text", "Public reasoning"))
        decoder.accept(fragment("response.reasoning_summary_text.delta", 0, "rs_1", "Public summary").put("summary_index", 0))
        decoder.accept(completed(item))
        assertEquals("Public reasoning", shown.toString())
        assertEquals("Public reasoning", decoder.reply().reasoningContent)
        val history = JSONArray().put(JSONObject().put("role", "assistant")
            .put(RESPONSES_OUTPUT_KEY, JSONArray(decoder.reply().responsesOutput)))
        val request = AiResponsesWire.request(AiConfig(), history, JSONArray())
        assertEquals("Public reasoning", request.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text"))
        val diagnostic = AiResponsesWire.diagnostic(request).toString()
        assertFalse(diagnostic.contains("Public reasoning"))
        assertFalse(diagnostic.contains("OPAQUE_FIXTURE_NOT_A_CREDENTIAL"))
        assertTrue(diagnostic.contains("Public summary"))
        assertEquals("responses_output_snapshot_mismatch", failure {
            val broken = AiResponsesStream()
            broken.accept(added(0, item))
            broken.accept(fragment("response.reasoning_text.delta", 0, "rs_1", "wrong").put("content_index", 0))
            broken.accept(completed(item))
        }.diagnostic)
    }

    @Test fun summaryAndReasoningContentDoNotDoubleEmitRegardlessOfArrivalOrder() {
        val shown = StringBuilder()
        val decoder = AiResponsesStream(reasoningDelta = { shown.append(it) })
        decoder.accept(added(0, reasoningItem()))
        decoder.accept(fragment("response.reasoning_summary_text.delta", 0, "rs_1", "Public summary").put("summary_index", 0))
        decoder.accept(fragment("response.reasoning_text.delta", 0, "rs_1", "Detailed content").put("content_index", 0))
        decoder.accept(completed(reasoningItem().put("content", JSONArray().put(JSONObject()
            .put("type", "reasoning_text").put("text", "Detailed content")))))
        assertEquals("Public summary", shown.toString())
        assertEquals("Public summary", decoder.reply().reasoningContent)
        assertEquals("Public summary".length + "Detailed content".length, decoder.reasoningChars)
    }

    @Test fun toolDeltasCanUseUniqueCallIdentityOrIndexWithoutRelaxingExplicitConflicts() {
        val decoder = AiResponsesStream()
        decoder.accept(added(0, callItem(arguments = "")))
        decoder.accept(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("call_id", "call_1").put("delta", "{\"path\":"))
        decoder.accept(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("output_index", 0).put("delta", "\"a.kt\"}"))
        decoder.accept(completed(callItem()))
        assertEquals("{\"path\":\"a.kt\"}", decoder.reply().calls.single().arguments)
        val conflict = AiResponsesStream()
        conflict.accept(added(0, callItem(arguments = "")))
        conflict.accept(added(1, callItem("fc_2", "call_2", "")))
        assertEquals("responses_item_identity_mismatch", failure {
            conflict.accept(JSONObject().put("type", "response.function_call_arguments.delta")
                .put("output_index", 0).put("call_id", "call_2").put("delta", "{}"))
        }.diagnostic)
        assertEquals("responses_item_identity_mismatch", failure {
            conflict.accept(fragment("response.function_call_arguments.delta", 0, "", "{}"))
        }.diagnostic)
    }

    @Test fun explicitCompleteAdapterRetainsUsageAndRejectsIncompleteErrorsAndUnsupportedOutput() {
        val decoder = AiResponsesWire.completed(completed(messageItem(), callItem()).getJSONObject("response"))
        assertEquals("Hello", decoder.reply().text)
        assertEquals(100L, decoder.inputTokens)
        assertEquals(25L, decoder.outputTokens)
        assertEquals(40L, decoder.cachedTokens)
        assertEquals(1, decoder.reply().calls.size)
        assertEquals("responses_rate_limit", failure {
            AiResponsesStream().accept(JSONObject().put("type", "response.error")
                .put("error", JSONObject().put("code", "rate_limit_exceeded").put("message", "PRIVATE")))
        }.diagnostic)
        assertEquals("responses_server_error", failure {
            AiResponsesWire.completed(JSONObject().put("status", "failed")
                .put("error", JSONObject().put("code", "server_error")))
        }.diagnostic)
        assertEquals(AiFailure.OutputLimit, failure {
            AiResponsesWire.completed(JSONObject().put("status", "incomplete")
                .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")))
        }.kind)
        assertEquals("responses_unsupported_output_item", failure {
            AiResponsesWire.completed(completed(JSONObject().put("id", "search_1")
                .put("type", "web_search_call")).getJSONObject("response"))
        }.diagnostic)
    }

    @Test fun reasoningContentCountsTowardSharedLimitAndNotificationsNeverComplete() {
        val decoder = AiResponsesStream()
        decoder.accept(added(0, reasoningItem()))
        assertEquals(AiFailure.OutputLimit, failure {
            decoder.accept(fragment("response.reasoning_text.delta", 0, "rs_1", "x".repeat(256001)).put("content_index", 0))
        }.kind)
        listOf("response.created", "response.in_progress", "response.queued", "response.metadata",
            "response.content_part.added", "response.reasoning_summary_part.done", "response.output_text.annotation.added").forEach { type ->
            val notification = AiResponsesStream()
            notification.accept(JSONObject().put("type", type))
            assertEquals("missing_done", failure { notification.reply() }.diagnostic)
        }
    }

    @Test fun callsWithoutOutputItemIdsStreamCompleteAndReplayTheirOriginalShape() {
        val noId = callItem().apply { remove("id") }
        val stream = AiResponsesStream()
        stream.accept(added(0, callItem(arguments = "").apply { remove("id") }))
        stream.accept(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("call_id", "call_1").put("delta", "{\"path\":\"a.kt\"}"))
        stream.accept(completed(noId))
        assertEquals("call_1", stream.reply().calls.single().id)
        val complete = AiResponsesWire.completed(completed(noId).getJSONObject("response"))
        assertEquals("call_1", complete.reply().calls.single().id)
        val original = JSONArray(complete.reply().responsesOutput).getJSONObject(0)
        assertFalse(original.has("id"))
        val request = AiResponsesWire.request(AiConfig(), JSONArray().put(JSONObject().put("role", "assistant")
            .put(RESPONSES_OUTPUT_KEY, JSONArray().put(original))), JSONArray())
        assertEquals(noId.toString(), request.getJSONArray("input").getJSONObject(0).toString())
        assertFalse(request.getJSONArray("input").getJSONObject(0).has("id"))
    }

    @Test fun lateExplicitOutputItemIdentityBindsOnceToTheSameCall() {
        val stream = AiResponsesStream()
        stream.accept(added(0, callItem(arguments = "").apply { remove("id") }))
        stream.accept(fragment("response.function_call_arguments.delta", 0, "fc_1", "{\"path\":\"a.kt\"}"))
        stream.accept(completed(callItem()))
        assertEquals("call_1", stream.reply().calls.single().id)
        val snapshotBound = AiResponsesStream()
        snapshotBound.accept(added(0, callItem(arguments = "").apply { remove("id") }))
        snapshotBound.accept(completed(callItem()))
        assertEquals("call_1", snapshotBound.reply().calls.single().id)
        val conflict = AiResponsesStream()
        conflict.accept(added(0, callItem(arguments = "").apply { remove("id") }))
        conflict.accept(fragment("response.function_call_arguments.delta", 0, "fc_1", "{}"))
        assertEquals("responses_item_identity_mismatch", failure {
            conflict.accept(fragment("response.function_call_arguments.delta", 0, "different", ""))
        }.diagnostic)
        assertEquals("responses_item_identity_mismatch", failure {
            conflict.accept(completed(callItem(callId = "different_call", arguments = "{}")))
        }.diagnostic)
    }

    @Test fun missingItemIdsNeverRelaxUniqueCallIdentityOrCrossItemConflicts() {
        val stream = AiResponsesStream()
        stream.accept(added(0, callItem(arguments = "").apply { remove("id") }))
        stream.accept(added(1, callItem("fc_2", "call_2", "")))
        assertEquals("responses_duplicate_item_id", failure {
            stream.accept(fragment("response.function_call_arguments.delta", 0, "fc_2", "{}"))
        }.diagnostic)
        assertEquals("responses_item_identity_mismatch", failure {
            stream.accept(JSONObject().put("type", "response.function_call_arguments.delta")
                .put("output_index", 0).put("call_id", "call_2").put("delta", "{}"))
        }.diagnostic)
        assertEquals("duplicate_tool_id", failure {
            AiResponsesWire.completed(completed(callItem().apply { remove("id") },
                callItem("fc_2", "call_1")).getJSONObject("response"))
        }.diagnostic)
        assertEquals("responses_item_identity_mismatch", failure {
            AiResponsesWire.request(AiConfig(), JSONArray().put(JSONObject().put("role", "assistant")
                .put(RESPONSES_OUTPUT_KEY, JSONArray().put(callItem().apply { remove("id") })
                    .put(callItem().apply { remove("id") }))), JSONArray())
        }.diagnostic)
    }

}

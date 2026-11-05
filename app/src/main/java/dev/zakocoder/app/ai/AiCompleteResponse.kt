package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Explicit non-streaming mode only. A broken SSE response is never reinterpreted as JSON success. */
internal object AiCompleteResponse {
    fun decode(protocol: AiApiProtocol, body: JSONObject, observe: (AiProviderStream) -> Unit = {}): AiProviderStream {
        val stream: AiProviderStream = when (protocol) {
            AiApiProtocol.ChatCompletions -> AiChatStream()
            AiApiProtocol.Responses -> AiResponsesStream()
            AiApiProtocol.Gemini -> AiGeminiStream()
            AiApiProtocol.Anthropic -> AiAnthropicStream()
        }
        try {
            // Usage is independent of executable output: retain validated counters even when
            // terminal status or tool validation fails. No stream has visible callbacks here.
            when (stream) {
                is AiChatStream -> {
                    stream.accept(JSONObject().put("usage", body.optJSONObject("usage")))
                    AiChatWire.complete(body, stream)
                }
                is AiResponsesStream -> { stream.readUsage(body); AiResponsesWire.completed(body, stream) }
                is AiGeminiStream -> {
                    stream.accept(JSONObject().put("usageMetadata", body.optJSONObject("usageMetadata")))
                    stream.accept(body); stream.reply()
                }
                is AiAnthropicStream -> { stream.usage(body.optJSONObject("usage")); anthropic(body, stream) }
            }
            return stream
        } catch (_: org.json.JSONException) {
            throw AiTransportException(diagnostic = "malformed_json")
        } finally {
            observe(stream)
        }
    }

    private fun anthropic(body: JSONObject, stream: AiAnthropicStream): AiAnthropicStream = stream.apply {
        if (body.optString("type") == "error") { accept(body); return@apply }
        if (body.optString("type") != "message" || body.optString("role") != "assistant")
            throw AiTransportException(diagnostic = "anthropic_invalid_message")
        val content = body.getJSONArray("content")
        AiAnthropicWire.validateOutput(content)
        val start = JSONObject(body.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL)
        accept(JSONObject().put("type", "message_start").put("message", start))
        repeat(content.length()) { index ->
            accept(JSONObject().put("type", "content_block_start").put("index", index).put("content_block", content.getJSONObject(index)))
            accept(JSONObject().put("type", "content_block_stop").put("index", index))
        }
        accept(JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", body.get("stop_reason")))
            .put("usage", body.optJSONObject("usage")))
        accept(JSONObject().put("type", "message_stop"))
        reply()
    }
}

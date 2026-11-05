package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Work on copies: changing protocols or preparing diagnostics must never mutate saved history. */
internal object AiProtocolHistory {
    fun messages(source: JSONArray, protocol: AiApiProtocol): JSONArray = JSONArray().apply {
        repeat(source.length()) { index ->
            val message = JSONObject(source.getJSONObject(index).toString())
            if (protocol != AiApiProtocol.Responses && message.remove(RESPONSES_OUTPUT_KEY) != null)
                message.remove("reasoning_content")
            val metadata = message.optJSONObject(PROVIDER_OUTPUT_KEY)
            if (metadata != null && metadata.optString("protocol") != protocol.name) {
                message.remove(PROVIDER_OUTPUT_KEY)
                message.remove("reasoning_content")
            }
            val details = message.remove(CHAT_REASONING_DETAILS_KEY)
            if (protocol == AiApiProtocol.ChatCompletions) {
                if (details != null && details != JSONObject.NULL) {
                    val array = details as? JSONArray ?: throw AiTransportException(diagnostic = "chat_invalid_reasoning_details")
                    AiChatReasoning.validateReplay(array)
                    if (message.has("reasoning_details") && message.get("reasoning_details").toString() != array.toString())
                        throw AiTransportException(diagnostic = "chat_invalid_reasoning_details")
                    message.put("reasoning_details", array)
                }
                message.optJSONArray("reasoning_details")?.let(AiChatReasoning::validateReplay)
            } else {
                message.remove("reasoning_details")
                if (details != null) message.remove("reasoning_content")
            }
            put(message)
        }
    }
}

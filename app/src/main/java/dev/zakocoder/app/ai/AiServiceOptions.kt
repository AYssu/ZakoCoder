package dev.zakocoder.app.ai

import org.json.JSONObject

/** Explicit endpoint capabilities; never inferred from a model name or an API credential. */
enum class AiChatThinkingFormat { ThinkingObject, EnableThinking, ReasoningObject, None }
enum class AiChatTokenLimit { MaxTokens, MaxCompletionTokens }
enum class AiAnthropicCache { Disabled, FiveMinutes, OneHour }

data class AiServiceOptions(
    val streaming: Boolean = true,
    val streamUsage: Boolean = true,
    val samplingParameters: Boolean = true,
    val responsesSummary: Boolean = true,
    val responsesEncryptedReasoning: Boolean = true,
    val chatThinking: AiChatThinkingFormat = AiChatThinkingFormat.ThinkingObject,
    val chatTokenLimit: AiChatTokenLimit = AiChatTokenLimit.MaxTokens,
    val responsesCacheKey: Boolean = false,
    val anthropicCache: AiAnthropicCache = AiAnthropicCache.Disabled,
)

/** Additive profile field: missing fields preserve the behavior of configurations saved before it. */
internal object AiServiceOptionsJson {
    fun decode(json: JSONObject?): AiServiceOptions {
        if (json == null) return AiServiceOptions()
        val defaults = AiServiceOptions()
        return AiServiceOptions(
            boolean(json, "streaming", defaults.streaming),
            boolean(json, "streamUsage", defaults.streamUsage),
            boolean(json, "samplingParameters", defaults.samplingParameters),
            boolean(json, "responsesSummary", defaults.responsesSummary),
            boolean(json, "responsesEncryptedReasoning", defaults.responsesEncryptedReasoning),
            if (json.has("chatThinking")) AiChatThinkingFormat.valueOf(json.getString("chatThinking")) else defaults.chatThinking,
            if (json.has("chatTokenLimit")) AiChatTokenLimit.valueOf(json.getString("chatTokenLimit")) else defaults.chatTokenLimit,
            boolean(json, "responsesCacheKey", defaults.responsesCacheKey),
            if (json.has("anthropicCache")) AiAnthropicCache.valueOf(json.getString("anthropicCache")) else defaults.anthropicCache,
        )
    }
    private fun boolean(json: JSONObject, key: String, default: Boolean): Boolean =
        if (json.has(key)) json.getBoolean(key) else default
    fun encode(options: AiServiceOptions): JSONObject = JSONObject()
        .put("streaming", options.streaming).put("streamUsage", options.streamUsage)
        .put("samplingParameters", options.samplingParameters).put("responsesSummary", options.responsesSummary)
        .put("responsesEncryptedReasoning", options.responsesEncryptedReasoning)
        .put("chatThinking", options.chatThinking.name).put("chatTokenLimit", options.chatTokenLimit.name)
        .put("responsesCacheKey", options.responsesCacheKey).put("anthropicCache", options.anthropicCache.name)
}

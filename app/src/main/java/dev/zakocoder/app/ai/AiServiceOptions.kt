package dev.zakocoder.app.ai

import org.json.JSONObject

/** Manual wire overrides, or an opt-in preset matched by the exact service endpoint. */
enum class AiChatThinkingFormat { ThinkingObject, EnableThinking, ReasoningObject, None, EffortOnly }
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
    // Old profiles remain explicit; only new editor drafts opt into presets.
    val usePreset: Boolean = false,
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
            boolean(json, "usePreset", false),
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
        .put("usePreset", options.usePreset)
}

/** Match the complete known endpoint, never a model name or a lookalike host. */
internal object AiServicePresets {
    // Official schemas: developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
    // alibabacloud.com/help/en/model-studio/deep-thinking
    // openrouter.ai/docs/guides/best-practices/reasoning-tokens
    fun provider(endpoint: String, protocol: AiApiProtocol): AiProviderPreset? = runCatching {
        val address = java.net.URI(endpoint.trim().trimEnd('/')).normalize()
        AiProviderPresets.all.firstOrNull {
            it.protocol == protocol && java.net.URI(it.endpoint) == address
        }
    }.getOrNull()

    fun resolve(options: AiServiceOptions, endpoint: String, protocol: AiApiProtocol): AiServiceOptions {
        if (!options.usePreset) return options
        // Unknown gateways use protocol defaults and omit nonstandard thinking controls.
        val thinking = when (provider(endpoint, protocol)?.id) {
            "openai-chat" -> AiChatThinkingFormat.EffortOnly
            "qwen" -> AiChatThinkingFormat.EnableThinking
            "openrouter" -> AiChatThinkingFormat.ReasoningObject
            "deepseek" -> AiChatThinkingFormat.ThinkingObject
            else -> AiChatThinkingFormat.None
        }
        return AiServiceOptions(chatThinking = thinking,
            chatTokenLimit = if (thinking == AiChatThinkingFormat.EffortOnly) AiChatTokenLimit.MaxCompletionTokens else AiChatTokenLimit.MaxTokens,
            usePreset = true)
    }
}

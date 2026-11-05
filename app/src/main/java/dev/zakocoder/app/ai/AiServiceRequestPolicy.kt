package dev.zakocoder.app.ai

import org.json.JSONObject
import java.security.MessageDigest

/** Explicit service capabilities; model names never select a protocol or silently trigger a retry. */
internal object AiServiceRequestPolicy {
    fun effectiveConfig(config: AiConfig, forceTool: String? = null): AiConfig {
        var parameters = config.parameters.copy(service = AiServicePresets.resolve(config.parameters.service, config.endpoint, config.protocol))
        if (!parameters.service.samplingParameters) parameters = parameters.copy(temperature = null, topP = null)
        // The official DeepSeek probe requires thinking disabled with a forced tool. Apply this
        // before formatting the parameter so every explicit format retains the same probe behavior.
        if (config.protocol == AiApiProtocol.ChatCompletions && forceTool != null &&
            java.net.URI(config.endpoint).host.equals("api.deepseek.com", ignoreCase = true))
            parameters = parameters.copy(thinking = AiThinking.Disabled)
        return config.copy(parameters = parameters)
    }

    fun sessionCacheKey(project: String, conversation: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$project\u0000$conversation".toByteArray(Charsets.UTF_8))
        return "zako-" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun apply(config: AiConfig, body: JSONObject, cacheKey: String?) {
        val options = config.parameters.service
        if (config.protocol != AiApiProtocol.Gemini) body.put("stream", options.streaming)
        if (!options.streaming || !options.streamUsage) body.remove("stream_options")
        if (!options.samplingParameters) { body.remove("temperature"); body.remove("top_p") }
        when (config.protocol) {
            AiApiProtocol.ChatCompletions -> {
                if (options.chatTokenLimit == AiChatTokenLimit.MaxCompletionTokens && body.has("max_tokens"))
                    body.put("max_completion_tokens", body.remove("max_tokens"))
                when (options.chatThinking) {
                    AiChatThinkingFormat.ThinkingObject -> Unit
                    AiChatThinkingFormat.EffortOnly -> body.remove("thinking")
                    AiChatThinkingFormat.EnableThinking -> {
                        body.remove("thinking")
                        if (config.parameters.thinking != AiThinking.Default)
                            body.put("enable_thinking", config.parameters.thinking == AiThinking.Enabled)
                    }
                    AiChatThinkingFormat.ReasoningObject -> {
                        body.remove("thinking"); body.remove("reasoning_effort")
                        val reasoning = JSONObject()
                        config.parameters.reasoningEffort?.let { reasoning.put("effort", it.wire) }
                        if (config.parameters.thinking != AiThinking.Default)
                            reasoning.put("enabled", config.parameters.thinking == AiThinking.Enabled)
                        if (reasoning.length() > 0) body.put("reasoning", reasoning)
                    }
                    AiChatThinkingFormat.None -> { body.remove("thinking"); body.remove("reasoning_effort") }
                }
            }
            AiApiProtocol.Responses -> {
                if (!options.responsesEncryptedReasoning) body.remove("include")
                if (!options.responsesSummary) body.optJSONObject("reasoning")?.let {
                    it.remove("summary")
                    if (it.length() == 0) body.remove("reasoning")
                }
                if (options.responsesCacheKey && cacheKey != null) {
                    if (!cacheKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                        throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "invalid_prompt_cache_key")
                    body.put("prompt_cache_key", cacheKey)
                }
            }
            AiApiProtocol.Anthropic -> if (options.anthropicCache != AiAnthropicCache.Disabled) {
                body.put("cache_control", JSONObject().put("type", "ephemeral")
                    .put("ttl", if (options.anthropicCache == AiAnthropicCache.OneHour) "1h" else "5m"))
            }
            AiApiProtocol.Gemini -> Unit
        }
    }
}

package dev.zakocoder.app.ai

/** Apply only on an explicit protocol/preset change, never silently rewrite a saved profile. */
internal object AiProtocolDefaults {
    fun parameters(value: AiParameters, protocol: AiApiProtocol): AiParameters {
        var result = value.copy(service = AiServiceOptions(usePreset = true))
        if (result.thinking == AiThinking.Disabled) result = result.copy(reasoningEffort = null)
        when (protocol) {
            AiApiProtocol.Responses -> result = result.copy(seed = null, frequencyPenalty = null, presencePenalty = null)
            AiApiProtocol.Anthropic -> {
                result = result.copy(seed = null, frequencyPenalty = null, presencePenalty = null,
                    temperature = result.temperature?.coerceAtMost(1.0))
                if (result.thinking == AiThinking.Enabled) result = result.copy(temperature = null, topP = null,
                    maxTokens = result.maxTokens?.coerceAtLeast(2048))
            }
            AiApiProtocol.Gemini -> if (result.reasoningEffort == AiReasoningEffort.Max) result = result.copy(reasoningEffort = AiReasoningEffort.High)
            AiApiProtocol.ChatCompletions -> Unit
        }
        return result
    }
}

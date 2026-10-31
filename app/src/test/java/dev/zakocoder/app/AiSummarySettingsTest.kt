package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiSummarySettingsTest {
    @Test fun officialDeepSeekSummaryDisablesThinkingWithoutChangingNormalProfile() {
        val original = AiConfig(endpoint = "https://api.deepseek.com/v1", model = "deepseek-flash",
            parameters = AiParameters(thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.High,
                temperature = 0.5, topP = 0.97, frequencyPenalty = 0.1, presencePenalty = 0.2, seed = 42),
            context = AiContextOptions(summaryMaxChars = 6000, contextWindowTokens = 128000))
        val summary = aiSummarySettings(original)
        assertEquals(AiThinking.Disabled, summary.parameters.thinking)
        assertNull(summary.parameters.reasoningEffort)
        assertEquals(8192, summary.parameters.maxTokens)
        assertEquals(original.parameters.copy(maxTokens = 8192, thinking = AiThinking.Disabled, reasoningEffort = null), summary.parameters)
        assertEquals(AiThinking.Enabled, original.parameters.thinking)
        assertEquals(AiReasoningEffort.High, original.parameters.reasoningEffort)
        assertNull(original.parameters.maxTokens)
        assertEquals(original.context, summary.context)
        assertEquals(original.endpoint, summary.endpoint)
        assertEquals(original.protocol, summary.protocol)
        assertEquals(original.model, summary.model)
    }

    @Test fun defaultDeepSeekThinkingIsExplicitlyDisabledForSummariesOnly() {
        for (endpoint in listOf("https://api.deepseek.com", "https://API.DEEPSEEK.COM:443/v1")) {
            val original = AiConfig(endpoint = endpoint)
            assertEquals(AiThinking.Default, original.parameters.thinking)
            assertEquals(AiThinking.Disabled, aiSummarySettings(original).parameters.thinking)
        }
    }

    @Test fun otherProtocolsAndCompatibleGatewaysReceiveNoNewThinkingParameters() {
        val endpoints = listOf("https://deepseek.example.invalid/v1", "https://api.deepseek.com.example.invalid/v1",
            "https://api.deepseek.com@proxy.example.invalid/v1", "https://proxy.example.invalid/api.deepseek.com",
            "http://api.deepseek.com", "https://api.deepseek.com:8443", "https://name@api.deepseek.com", "not a URI")
        for (endpoint in endpoints) {
            val original = AiConfig(endpoint = endpoint, parameters = AiParameters(reasoningEffort = AiReasoningEffort.High))
            val summary = aiSummarySettings(original)
            assertEquals(endpoint, original.endpoint)
            assertEquals(original.parameters.copy(maxTokens = aiOutputReserve(original)), summary.parameters)
        }
        for (protocol in AiApiProtocol.entries.filter { it != AiApiProtocol.ChatCompletions }) {
            val original = AiConfig(endpoint = "https://api.deepseek.com", protocol = protocol,
                parameters = AiParameters(thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.High))
            assertEquals(original.parameters.copy(maxTokens = aiOutputReserve(original)), aiSummarySettings(original).parameters)
        }
    }

    @Test fun summaryOutputAlwaysFitsTheExistingNormalOutputReservation() {
        for (window in listOf(8192, 128000, 1048576)) {
            for (explicit in listOf(null, 512, 4000, 32768)) {
                val original = AiConfig(endpoint = "https://api.deepseek.com",
                    parameters = AiParameters(maxTokens = explicit), context = AiContextOptions(contextWindowTokens = window))
                val summary = aiSummarySettings(original)
                assertEquals(aiOutputReserve(original), summary.parameters.maxTokens)
                assertEquals(aiOutputReserve(original), aiOutputReserve(summary))
                summary.parameters.validate()
                assertEquals(original.context, summary.context)
            }
        }
    }
}

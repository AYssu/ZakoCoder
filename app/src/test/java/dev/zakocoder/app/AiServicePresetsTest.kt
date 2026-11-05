package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ModelOptionsDraft
import org.junit.Assert.*
import org.junit.Test

class AiServicePresetsTest {
    @Test fun newDraftUsesPresetAndExistingManualProfileRemainsClean() {
        val fresh = ModelOptionsDraft(null)
        assertTrue(fresh.service.usePreset)
        assertFalse(fresh.dirty(null))
        val manual = AiServiceOptions(streaming = false, chatThinking = AiChatThinkingFormat.EnableThinking,
            chatTokenLimit = AiChatTokenLimit.MaxCompletionTokens)
        val profile = AiProfile("saved", "Saved", "https://example.invalid/v1", "model", false, AiParameters(service = manual))
        val reopened = ModelOptionsDraft(profile)
        assertFalse(reopened.service.usePreset)
        assertEquals(manual, reopened.parameters(AiParameters()).service)
        assertFalse(reopened.dirty(profile))
    }

    @Test fun presetsFollowExactEndpointAndProtocolRatherThanModelNames() {
        val automatic = AiServiceOptions(usePreset = true)
        fun resolve(endpoint: String, protocol: AiApiProtocol = AiApiProtocol.ChatCompletions) =
            AiServicePresets.resolve(automatic, endpoint, protocol)
        assertEquals(AiChatThinkingFormat.EnableThinking, resolve("https://dashscope.aliyuncs.com/compatible-mode/v1/").chatThinking)
        assertEquals(AiChatThinkingFormat.ReasoningObject, resolve("https://openrouter.ai/api/v1").chatThinking)
        assertEquals(AiChatThinkingFormat.ThinkingObject, resolve("https://api.deepseek.com/v1").chatThinking)
        val openai = resolve("https://api.openai.com/v1")
        assertEquals(AiChatThinkingFormat.EffortOnly, openai.chatThinking)
        assertEquals(AiChatTokenLimit.MaxCompletionTokens, openai.chatTokenLimit)
        for (endpoint in listOf("https://api.deepseek.com.evil.invalid/v1", "http://api.deepseek.com/v1",
            "https://api.deepseek.com/other", "https://api.deepseek.com/v1?other=1", "invalid")) {
            assertEquals(endpoint, AiChatThinkingFormat.None, resolve(endpoint).chatThinking)
        }
        assertEquals(AiChatThinkingFormat.None, resolve("https://api.deepseek.com/v1", AiApiProtocol.Responses).chatThinking)
    }

    @Test fun requestResolutionRetainsOrdinaryParametersAndDoesNotMutateManualOptions() {
        val manual = AiServiceOptions(streaming = false, samplingParameters = false,
            chatThinking = AiChatThinkingFormat.ReasoningObject)
        val config = AiConfig("https://api.deepseek.com/v1", "fixture", parameters = AiParameters(
            temperature = .5, maxTokens = 4096, service = manual))
        val explicit = AiServiceRequestPolicy.effectiveConfig(config)
        assertEquals(manual, explicit.parameters.service)
        assertNull(explicit.parameters.temperature)
        val preset = AiServiceRequestPolicy.effectiveConfig(config.copy(parameters = config.parameters.copy(service = manual.copy(usePreset = true))))
        assertTrue(preset.parameters.service.streaming)
        assertEquals(.5, preset.parameters.temperature)
        assertEquals(4096, preset.parameters.maxTokens)
        assertEquals(AiChatThinkingFormat.ThinkingObject, preset.parameters.service.chatThinking)
        assertEquals(manual, config.parameters.service)
    }
}

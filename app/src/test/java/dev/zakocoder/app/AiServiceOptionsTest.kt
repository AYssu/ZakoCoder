package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ModelOptionsDraft
import org.junit.Assert.*
import org.junit.Test

class AiServiceOptionsTest {
    @Test fun defaultsPreserveExistingRequestsAndCacheIsOptIn() {
        val options = AiParameters().service
        assertTrue(options.streaming)
        assertTrue(options.streamUsage)
        assertTrue(options.samplingParameters)
        assertTrue(options.responsesSummary)
        assertTrue(options.responsesEncryptedReasoning)
        assertEquals(AiChatThinkingFormat.ThinkingObject, options.chatThinking)
        assertEquals(AiChatTokenLimit.MaxTokens, options.chatTokenLimit)
        assertFalse(options.responsesCacheKey)
        assertEquals(AiAnthropicCache.Disabled, options.anthropicCache)
    }
    @Test fun editingOrdinaryParametersRetainsAllExplicitCapabilities() {
        val service = AiServiceOptions(false, false, false, false, false,
            AiChatThinkingFormat.ReasoningObject, AiChatTokenLimit.MaxCompletionTokens, true, AiAnthropicCache.OneHour)
        assertEquals(service, AiParameters(service = service).copy(temperature = .2, maxTokens = 100).service)
    }
    @Test fun openingAndEditingModelFormRetainsCapabilitiesWithoutFalseDirtyState() {
        val service = AiServiceOptions(streaming = false, responsesCacheKey = true,
            chatThinking = AiChatThinkingFormat.ReasoningObject, anthropicCache = AiAnthropicCache.OneHour)
        val profile = AiProfile("fixture", "Fixture", "https://example.invalid", "model", false,
            AiParameters(temperature = .7, service = service))
        val draft = ModelOptionsDraft(profile)
        assertFalse(draft.dirty(profile))
        assertEquals(service, draft.parameters(AiParameters(maxTokens = 100)).service)
        draft.service = service.copy(streaming = true)
        assertTrue(draft.dirty(profile))
        assertTrue(draft.parameters(AiParameters()).service.streaming)
        draft.modelChanged("different-model")
        assertEquals(service.copy(streaming = true), draft.service)
    }
}

package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiProtocolDefaultsTest {
    @Test fun protocolChangeAppliesAutomaticCompatibilityWithoutMutatingOriginal() {
        val original = AiParameters(maxTokens = 512, temperature = 1.8, topP = 0.9,
            thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.Max,
            frequencyPenalty = 1.0, presencePenalty = 0.2, seed = 42,
            service = AiServiceOptions(streaming = false, usePreset = false))
        val anthropic = AiProtocolDefaults.parameters(original, AiApiProtocol.Anthropic)
        assertTrue(anthropic.service.usePreset); assertTrue(anthropic.service.streaming)
        assertNull(anthropic.temperature); assertNull(anthropic.topP)
        assertNull(anthropic.seed); assertNull(anthropic.frequencyPenalty); assertNull(anthropic.presencePenalty)
        assertEquals(2048, anthropic.maxTokens)
        val responses = AiProtocolDefaults.parameters(original, AiApiProtocol.Responses)
        assertNull(responses.seed); assertNull(responses.frequencyPenalty)
        assertEquals(AiReasoningEffort.High, AiProtocolDefaults.parameters(original, AiApiProtocol.Gemini).reasoningEffort)
        assertFalse(original.service.usePreset); assertEquals(42, original.seed)
    }
    @Test fun disabledThinkingNeverKeepsConflictingEffortAndEmptyDraftHasDefaultsForEveryProtocol() {
        AiApiProtocol.entries.forEach { protocol ->
            val value = AiProtocolDefaults.parameters(AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.High), protocol)
            assertNull(value.reasoningEffort)
            assertTrue(AiProtocolDefaults.parameters(AiParameters(), protocol).service.usePreset)
        }
    }
}

package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiModelProtocolDefaultsTest {
    @Test fun editorStateAndCodecValidationAgreeAfterSwitchingEveryProtocol() {
        val original = AiParameters(maxTokens = 512, temperature = 1.8, topP = 0.8, thinking = AiThinking.Enabled,
            reasoningEffort = AiReasoningEffort.Max, frequencyPenalty = 0.2, presencePenalty = 0.1, seed = 7,
            service = AiServiceOptions(streaming = false, usePreset = false))
        AiApiProtocol.entries.forEach { protocol ->
            val profile = AiProfile("id", "test", "https://example.invalid/v1", "model", false, original)
            val draft = ModelOptionsDraft(profile)
            assertFalse(draft.service.usePreset) // Opening never overwrites saved manual choices.
            val next = draft.applyProtocol(original, protocol)
            assertEquals(next, draft.parameters(next))
            assertTrue(protocolParametersSupported(protocol, next))
            assertTrue(draft.service.usePreset)
            assertEquals(original, profile.parameters)
        }
    }
    @Test fun freshModelDraftHasAutomaticDefaultsWithoutSelectingAnyAdvancedFields() {
        val draft = ModelOptionsDraft(null)
        assertTrue(draft.service.usePreset)
        assertTrue(draft.valid())
        assertEquals(AiThinking.Default, draft.thinking)
        assertNull(draft.effort)
    }
}

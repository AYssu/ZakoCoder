package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ModelOptionsDraft
import org.junit.Assert.*
import org.junit.Test

class ModelOptionsDraftTest {
    @Test fun defaultDraftRetainsHeadersWithoutReturningValues() {
        val profile = AiProfile("fixture", "Fixture", "https://example.invalid", "model", true, AiParameters(), headerNames = listOf("X-Api-Key"))
        val draft = ModelOptionsDraft(profile)
        assertTrue(draft.valid()); assertFalse(draft.dirty(profile)); assertNull(draft.headers())
        draft.replaceHeaders = true
        assertEquals(emptyMap<String, String>(), draft.headers()); assertTrue(draft.dirty(profile))
        draft.headersText = "X-Api-Key: test:with:colons\nHTTP-Referer: https://example.invalid"
        assertTrue(draft.valid()); assertEquals("test:with:colons", draft.headers()!!["X-Api-Key"])
        draft.headersText = "X-Api-Key: one\nx-api-key: two"
        assertFalse(draft.valid())
    }
    @Test fun invalidInputsAreNotSilentlyChangedIntoProviderDefaults() {
        val draft = ModelOptionsDraft(null)
        draft.frequency = "NaN"; assertFalse(draft.valid())
        draft.frequency = ""; draft.seed = "1.5"; assertFalse(draft.valid())
        draft.seed = ""; draft.windowTokens = "100"; assertFalse(draft.valid())
        draft.windowTokens = "1000000"; assertTrue(draft.valid())
    }
    @Test fun customLegacyBudgetRemainsCleanUntilWindowChanges() {
        val context = AiContextOptions(maxContextChars = 180000, compressionThresholdPercent = 80, contextWindowTokens = 60000)
        val profile = AiProfile("legacy", "Legacy", "https://example.invalid", "custom", true, AiParameters(maxTokens = 2048), context = context)
        val draft = ModelOptionsDraft(profile)
        assertFalse(draft.dirty(profile))
        assertEquals(context, draft.context())
        draft.windowTokens = "1000000"
        assertTrue(draft.dirty(profile))
        assertEquals(1000000, draft.context().contextWindowTokens)
        assertEquals(80, draft.context().compressionThresholdPercent)
        assertEquals(profile.parameters, draft.parameters(profile.parameters))
    }
    @Test fun selectingModelsAppliesReferencesButReopeningAndReselectingRetainsManualLimits() {
        val profile = AiProfile("saved", "Saved", "https://example.invalid", "gpt-6.1-sol", false,
            AiParameters(), context = AiContextOptions(contextWindowTokens = 256000, compressionThresholdPercent = 80))
        val draft = ModelOptionsDraft(profile)
        assertEquals("256000", draft.windowTokens)
        assertFalse(draft.dirty(profile))
        draft.modelChanged(" GPT-6.1-SOL ")
        assertEquals("256000", draft.windowTokens)
        draft.modelChanged("grok-4.7")
        assertEquals("500000", draft.windowTokens)
        draft.windowTokens = "64000"
        draft.modelChanged("grok-4.7")
        assertEquals("64000", draft.windowTokens)
        draft.modelChanged("gpt-6.1-sol")
        assertEquals("1050000", draft.windowTokens)
        assertEquals(80, draft.context().compressionThresholdPercent)
        draft.modelChanged("unknown-large-model")
        assertEquals("1000000", draft.windowTokens)
    }
    @Test fun newUnknownModelUsesMillionTokensWhileSavedOverridesStayUntouched() {
        val fresh = ModelOptionsDraft(null)
        assertEquals("1000000", fresh.windowTokens)
        fresh.modelChanged("custom-service/unlisted-model")
        assertEquals(1_000_000, fresh.context().contextWindowTokens)

        for (tokens in listOf(64_000, 128_000, 256_000)) {
            val profile = AiProfile("saved-$tokens", "Saved", "https://example.invalid", "unlisted-model", false,
                AiParameters(), context = AiContextOptions(contextWindowTokens = tokens))
            val draft = ModelOptionsDraft(profile)
            assertEquals(tokens.toString(), draft.windowTokens)
            draft.modelChanged("UNLISTED-MODEL")
            assertEquals(tokens, draft.context().contextWindowTokens)
            assertFalse(draft.dirty(profile))
        }
    }
    @Test fun newlyCataloguedModelsApplyTheirOwnWindowWhenSelected() {
        val draft = ModelOptionsDraft(null)
        mapOf(
            "openai/gpt-6.2-sol" to 1_050_000,
            "openai/gpt-6.2-astra" to 1_050_000,
            "google/gemini-4-carbon" to 1_048_576,
            "anthropic/claude-sonnet-5-5" to 1_000_000,
            "anthropic/claude-sonnet-4-5" to 200_000,
            "x-ai/grok-4.6" to 500_000,
            "z-ai/glm-5.3" to 1_000_000,
            "moonshot/kimi-k3.1-pro" to 1_048_576,
            "unlisted-next-model" to 1_000_000,
        ).forEach { (model, tokens) ->
            draft.modelChanged(model)
            assertEquals(model, tokens, draft.context().contextWindowTokens)
            assertTrue(model, draft.valid())
            draft.windowTokens = "256000"
            draft.modelChanged(model.uppercase(java.util.Locale.ROOT))
            assertEquals(model, 256_000, draft.context().contextWindowTokens)
        }
    }

    @Test fun newModelUsesMatchingWindowAndPromptParticipatesInValidationAndDirtyState() {
        val draft = ModelOptionsDraft(null)
        draft.modelChanged("openai/gpt-6.1-sol")
        assertEquals(1050000, draft.context().contextWindowTokens)
        draft.systemPrompt = "Use concise Chinese.\nPreserve public APIs."
        assertTrue(draft.valid())
        val profile = AiProfile("id", "Fixture", "https://example.invalid", "gpt-6.1-sol", false,
            AiParameters(), context = draft.context(), systemPrompt = draft.systemPrompt)
        val saved = ModelOptionsDraft(profile)
        assertFalse(saved.dirty(profile))
        saved.systemPrompt = ""
        assertTrue(saved.dirty(profile))
        saved.systemPrompt = "x".repeat(AiSystemPrompt.MAX_CHARS + 1)
        assertFalse(saved.valid())
    }

}

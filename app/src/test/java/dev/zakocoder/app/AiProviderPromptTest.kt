package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiProviderPromptTest {
    @Test fun optionalPromptKeepsWhitespaceAndLineBreaksWithinBound() {
        assertEquals("", AiSystemPrompt.normalize(" \n\t\r "))
        val prompt = "  使用中文。\nKeep public APIs stable.\n"
        assertEquals(prompt, AiSystemPrompt.normalize(prompt))
        assertEquals(16000, AiSystemPrompt.normalize("x".repeat(16000)).length)
        assertThrows(IllegalArgumentException::class.java) { AiSystemPrompt.normalize("x".repeat(16001)) }
    }

    @Test fun promptSupplementsAllDefaultAndOptionalPackageRules() {
        val prompt = "PROVIDER_PROMPT_FIXTURE\nUse concise explanations."
        for (screen in listOf(false, true)) for (ssh in listOf(false, true)) {
            val original = AiController.systemForPackages(screen, ssh)
            assertEquals(original, AiController.systemForPackages(screen, ssh, " \n\t"))
            val configured = AiController.systemForPackages(screen, ssh, prompt)
            assertTrue(configured.startsWith(original))
            assertTrue(configured.endsWith(prompt))
            assertTrue(configured.contains("Follow the app tool permission policy"))
            assertTrue(configured.contains("approval requirements and safety rules above remain mandatory"))
            assertEquals(1, Regex("PROVIDER_PROMPT_FIXTURE").findAll(configured).count())
        }
    }

    @Test fun editableAndResolvedModelsNeverPrintPromptInSummaries() {
        val marker = "CUSTOM_PROVIDER_PROMPT_PRIVATE_FIXTURE"
        val profile = AiProfile("id", "name", "https://example.invalid", "model", false, AiParameters(), systemPrompt = marker)
        val draft = AiModelDraft(null, "name", "https://example.invalid", "model", null, systemPrompt = marker)
        val config = AiConfig(systemPrompt = marker)
        assertEquals(marker, profile.systemPrompt)
        assertEquals(marker, draft.systemPrompt)
        assertEquals(marker, config.systemPrompt)
        for (summary in listOf(profile.toString(), draft.toString(), config.toString(), AiCatalog("id", listOf(profile)).toString()))
            assertFalse(summary.contains(marker))
        assertEquals(marker, aiSummarySettings(config).systemPrompt)
    }
}

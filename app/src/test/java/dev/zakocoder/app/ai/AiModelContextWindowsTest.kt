package dev.zakocoder.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AiModelContextWindowsTest {
    @Test fun allExistingPresetIdsHaveOneSharedReference() {
        mapOf(
            "deepseek-flash" to 1_000_000,
            "deepseek-v4-pro" to 1_000_000,
            "gpt-6.1-sol" to 1_050_000,
            "gpt-6.1-terra" to 1_050_000,
            "gpt-6-astra" to 1_050_000,
            "gpt-6-luna" to 1_050_000,
            "gemini-4-argon" to 1_000_000,
            "grok-4.7" to 500_000,
        ).forEach { (model, expected) ->
            assertEquals(model, expected, AiModelContextWindows.recommendedTokens(model))
            assertEquals(model, expected, AiModelContextWindows.defaultTokens(model))
        }
    }

    @Test fun exactKnownIdsCanHaveProviderRoutingPrefixesAndOuterWhitespace() {
        mapOf(
            "  GPT-6.1-SOL\n" to 1_050_000,
            "openai/gpt-6.1-sol" to 1_050_000,
            "gateway/openai/gpt-6.1-terra" to 1_050_000,
            "deepseek/DEEPSEEK-FLASH" to 1_000_000,
            "models/gemini-4-argon" to 1_000_000,
            " x-ai/GROK-4.7 " to 500_000,
        ).forEach { (model, expected) ->
            assertEquals(model, expected, AiModelContextWindows.recommendedTokens(model))
        }
    }

    @Test fun unknownVariantsAndMalformedIdsDoNotInheritAnotherModelsLimit() {
        listOf("", " ", "unknown", "gpt-6.1-sol-mini", "gpt-6.1-sol-2027-01-01",
            "gpt-6.1", "gpt-6.2-sol", "gemini-4-argon-preview", "deepseek-v4-pro-fast",
            "grok-4.70", "prefix-gpt-6.1-sol", "openai:gpt-6.1-sol", "gpt-6.1-sol/",
            "openai/ gpt-6.1-sol", "openai/gpt-6.1-sol?foo=bar").forEach { model ->
            assertNull(model, AiModelContextWindows.recommendedTokens(model))
            assertEquals(model, 128_000, AiModelContextWindows.defaultTokens(model))
        }
    }

    @Test fun matchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1_000_000, AiModelContextWindows.recommendedTokens("DEEPSEEK/DEEPSEEK-FLASH"))
            assertEquals(1_000_000, AiModelContextWindows.recommendedTokens("GOOGLE/GEMINI-4-ARGON"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

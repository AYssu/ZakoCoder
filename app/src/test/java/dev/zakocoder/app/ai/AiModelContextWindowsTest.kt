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
            "gemini-4-argon" to 1_048_576,
            "grok-4.7" to 500_000,
        ).forEach { (model, expected) ->
            assertEquals(model, expected, AiModelContextWindows.recommendedTokens(model))
            assertEquals(model, expected, AiModelContextWindows.defaultTokens(model))
        }
    }

    @Test fun recentOfficialModelIdsUseTheirDocumentedContextInsteadOfFallback() {
        mapOf(
            1_050_000 to listOf("gpt-6.2-sol", "gpt-6.2-astra"),
            1_048_576 to listOf(
                "gemini-4-carbon", "gemini-4-neon", "gemini-4-hydrogen", "gemini-4-argon",
                "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
                "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k3",
            ),
            1_000_000 to listOf(
                "deepseek-v4-flash", "deepseek-v4-flash-vision-exp",
                "claude-fable-5-2", "claude-fable-5-1", "claude-fable-5",
                "claude-opus-5-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
                "claude-sonnet-5-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-5-5",
                "grok-4.3", "grok-4.3-latest", "glm-5.3", "glm-5.3-flash", "glm-5.3-flashx",
                "glm-5.3-flashx-legal", "glm-5.2",
                "qwen4-max", "qwen3.8-flash", "qwen3.7-plus", "qwen3.7-plus-2026-05-26", "qwen3.6-plus",
                "minimax-m3.1-flash", "minimax-m3",
            ),
            500_000 to listOf("grok-4.7", "grok-4.6", "grok-4.5", "grok-4.5-latest", "grok-build-latest"),
            262_144 to listOf("kimi-k2.7-code", "kimi-k2.7-code-highspeed", "kimi-k2.6"),
            200_000 to listOf(
                "claude-opus-4-5", "claude-sonnet-4-5", "claude-sonnet-4-5-20250929",
                "claude-haiku-4-5", "claude-haiku-4-5-20251001", "glm-5.1", "glm-5", "glm-4.7", "glm-4.6",
            ),
        ).forEach { (expected, models) ->
            models.forEach { model ->
                // recommendedTokens must be non-null even when its value equals the 1M fallback.
                assertEquals(model, expected, AiModelContextWindows.recommendedTokens(model))
                assertEquals(model, expected, AiModelContextWindows.defaultTokens(model))
            }
        }
    }

    @Test fun exactKnownIdsCanHaveProviderRoutingPrefixesAndOuterWhitespace() {
        mapOf(
            "  GPT-6.1-SOL\n" to 1_050_000,
            "openai/gpt-6.1-sol" to 1_050_000,
            "gateway/openai/gpt-6.1-terra" to 1_050_000,
            "deepseek/DEEPSEEK-FLASH" to 1_000_000,
            "models/gemini-4-argon" to 1_048_576,
            " x-ai/GROK-4.7 " to 500_000,
            " gateway/OPENAI/GPT-6.2-SOL " to 1_050_000,
            "anthropic/CLAUDE-SONNET-4-5-20250929" to 200_000,
            "z-ai/GLM-5.3-FLASHX-LEGAL" to 1_000_000,
            "google/GEMINI-3.8-FLASH" to 1_048_576,
            " MiniMax/MiniMax-M3.1-Flash " to 1_000_000,
            "moonshot/KIMI-K3.1-PRO" to 1_048_576,
            "qwen/QWEN3.7-PLUS-2026-05-26" to 1_000_000,
        ).forEach { (model, expected) ->
            assertEquals(model, expected, AiModelContextWindows.recommendedTokens(model))
        }
    }

    @Test fun unknownVariantsAndMalformedIdsDoNotInheritAnotherModelsLimit() {
        listOf("", " ", "unknown", "gpt-6.1-sol-mini", "gpt-6.1-sol-2027-01-01",
            "gpt-6.1", "gpt-6.2-sol-mini", "gemini-4-argon-preview", "deepseek-v4-pro-fast",
            "grok-4.70", "prefix-gpt-6.1-sol", "openai:gpt-6.1-sol", "gpt-6.1-sol/",
            "openai/ gpt-6.1-sol", "openai/gpt-6.1-sol?foo=bar",
            "claude-sonnet-4-5-20260101", "claude-opus-5-5-preview", "glm-5.3-fast",
            "deepseek-v4-flash-vision-exp-20261122", "gemini-3.8-flash-latest",
            "grok-4.6-latest", "qwen3.7-plus-2026-05-27", "kimi-k3.1-pro-preview",
            "minimax-m3.1-flash-latest").forEach { model ->
            assertNull(model, AiModelContextWindows.recommendedTokens(model))
            assertEquals(model, 1_000_000, AiModelContextWindows.defaultTokens(model))
        }
    }

    @Test fun matchingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(1_000_000, AiModelContextWindows.recommendedTokens("DEEPSEEK/DEEPSEEK-FLASH"))
            assertEquals(1_048_576, AiModelContextWindows.recommendedTokens("GOOGLE/GEMINI-4-ARGON"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}

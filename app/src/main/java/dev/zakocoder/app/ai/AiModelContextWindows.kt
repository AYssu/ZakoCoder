package dev.zakocoder.app.ai

import java.util.Locale

/** Shared references for known model IDs; never infers limits for unknown model variants. */
object AiModelContextWindows {
    const val DEFAULT_TOKENS = 128_000

    private val known = mapOf(
        "deepseek-flash" to 1_000_000,
        "deepseek-v4-pro" to 1_000_000,
        "gpt-6.1-sol" to 1_050_000,
        "gpt-6.1-terra" to 1_050_000,
        "gpt-6-astra" to 1_050_000,
        "gpt-6-luna" to 1_050_000,
        "gemini-4-argon" to 1_000_000,
        "grok-4.7" to 500_000,
    )

    /** Accepts exact IDs and slash-delimited provider routes, e.g. `openai/gpt-6.1-sol`. */
    fun recommendedTokens(modelId: String): Int? =
        known[modelId.trim().lowercase(Locale.ROOT).substringAfterLast('/')]

    /** Used when initially selecting a model, without changing saved or user-entered limits. */
    fun defaultTokens(modelId: String): Int = recommendedTokens(modelId) ?: DEFAULT_TOKENS
}

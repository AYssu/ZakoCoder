package dev.zakocoder.app.ai

import java.util.Locale

/** Shared references for known model IDs; never infers limits for unknown model variants. */
object AiModelContextWindows {
    const val DEFAULT_TOKENS = 1_000_000

    // Context limits checked against official model documentation on 2026-11-22.
    private val known = mapOf(
        // https://developers.openai.com/api/docs/models/gpt-6.2-sol
        // https://developers.openai.com/api/docs/models/gpt-6.2-astra
        "gpt-6.2-sol" to 1_050_000,
        "gpt-6.2-astra" to 1_050_000,
        "gpt-6.1-sol" to 1_050_000,
        "gpt-6.1-terra" to 1_050_000,
        "gpt-6-astra" to 1_050_000,
        "gpt-6-luna" to 1_050_000,

        // https://api-docs.deepseek.com/quick_start/pricing/
        "deepseek-flash" to 1_000_000,
        "deepseek-v4-pro" to 1_000_000,
        "deepseek-v4-flash" to 1_000_000,
        "deepseek-v4-flash-vision-exp" to 1_000_000,

        // https://aistudio.google.com/docs/models/gemini-4-carbon
        "gemini-4-carbon" to 1_048_576,
        "gemini-4-neon" to 1_048_576,
        "gemini-4-hydrogen" to 1_048_576,
        "gemini-4-argon" to 1_048_576,
        "gemini-3.8-flash" to 1_048_576,
        "gemini-3.6-flash" to 1_048_576,
        "gemini-3.5-flash-lite" to 1_048_576,
        "gemini-3.1-flash-lite" to 1_048_576,

        // https://platform.claude.com/docs/en/models/overview
        // https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions
        "claude-fable-5-2" to 1_000_000,
        "claude-fable-5-1" to 1_000_000,
        "claude-fable-5" to 1_000_000,
        "claude-opus-5-5" to 1_000_000,
        "claude-opus-5" to 1_000_000,
        "claude-opus-4-8" to 1_000_000,
        "claude-opus-4-7" to 1_000_000,
        "claude-opus-4-6" to 1_000_000,
        "claude-sonnet-5-5" to 1_000_000,
        "claude-sonnet-5" to 1_000_000,
        "claude-sonnet-4-6" to 1_000_000,
        "claude-haiku-5-5" to 1_000_000,
        "claude-opus-4-5" to 200_000,
        "claude-sonnet-4-5" to 200_000,
        "claude-sonnet-4-5-20250929" to 200_000,
        "claude-haiku-4-5" to 200_000,
        "claude-haiku-4-5-20251001" to 200_000,

        // https://docs.x.ai/developers/grok-4-7
        // https://docs.x.ai/developers/models/grok-4.6
        // https://docs.x.ai/developers/models/grok-4.5
        // https://docs.x.ai/developers/models/grok-4.3
        "grok-4.7" to 500_000,
        "grok-4.6" to 500_000,
        "grok-4.5" to 500_000,
        "grok-4.5-latest" to 500_000,
        "grok-build-latest" to 500_000,
        "grok-4.3" to 1_000_000,
        "grok-4.3-latest" to 1_000_000,

        // https://docs.z.ai/guides/overview/overview
        // https://docs.z.ai/guides/vlm/glm-5.3-flash
        "glm-5.3" to 1_000_000,
        "glm-5.3-flash" to 1_000_000,
        "glm-5.3-flashx" to 1_000_000,
        "glm-5.3-flashx-legal" to 1_000_000,
        "glm-5.2" to 1_000_000,
        "glm-5.1" to 200_000,
        "glm-5" to 200_000,
        "glm-4.7" to 200_000,
        "glm-4.6" to 200_000,

        // https://www.alibabacloud.com/help/en/model-studio/qwen4-max
        // https://www.alibabacloud.com/help/en/model-studio/qwen3-8-flash
        // https://www.alibabacloud.com/help/en/model-studio/qwen3-7-plus
        // https://docs.qwencloud.com/developer-guides/getting-started/text-generation-models
        "qwen4-max" to 1_000_000,
        "qwen3.8-flash" to 1_000_000,
        "qwen3.7-plus" to 1_000_000,
        "qwen3.7-plus-2026-05-26" to 1_000_000,
        "qwen3.6-plus" to 1_000_000,

        // https://platform.minimax.io/docs/models/text/models-intro
        "minimax-m3.1-flash" to 1_000_000,
        "minimax-m3" to 1_000_000,

        // https://platform.kimi.com/docs/models
        // https://platform.kimi.com/docs/pricing/chat
        "kimi-k3.1-pro" to 1_048_576,
        "kimi-k3.1-pro-highspeed" to 1_048_576,
        "kimi-k3" to 1_048_576,
        "kimi-k2.7-code" to 262_144,
        "kimi-k2.7-code-highspeed" to 262_144,
        "kimi-k2.6" to 262_144,
    )

    /** The capacity picker and automatic model selection share this exact catalogue. */
    internal fun referenceWindows(): Map<String, Int> = known.toMap()

    /** Accepts exact IDs and slash-delimited provider routes, e.g. `openai/gpt-6.1-sol`. */
    fun recommendedTokens(modelId: String): Int? =
        known[modelId.trim().lowercase(Locale.ROOT).substringAfterLast('/')]

    /** Used when initially selecting a model, without changing saved or user-entered limits. */
    fun defaultTokens(modelId: String): Int = recommendedTokens(modelId) ?: DEFAULT_TOKENS
}

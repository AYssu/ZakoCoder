package dev.zakocoder.app.ai

import dev.zakocoder.app.R

/** Address/protocol metadata only. Selecting a preset never supplies a model or credentials.
 * Official API documentation checked 2026-10-31:
 * https://platform.openai.com/docs/api-reference/responses
 * https://docs.anthropic.com/en/api/messages
 * https://ai.google.dev/api/generate-content
 * https://api-docs.deepseek.com/
 * https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope
 * https://docs.siliconflow.cn/cn/api-reference/chat-completions/chat-completions
 * https://platform.moonshot.cn/docs/guide/start-using-kimi-api
 * https://docs.bigmodel.cn/cn/guide/develop/http/introduction
 * https://openrouter.ai/docs/quickstart
 * https://docs.x.ai/developers/quickstart
 */
internal data class AiProviderPreset(val id: String, val label: Int, val endpoint: String, val protocol: AiApiProtocol)

internal object AiProviderPresets {
    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset("openai-responses", R.string.ai_provider_openai_responses, "https://api.openai.com/v1", AiApiProtocol.Responses),
        AiProviderPreset("openai-chat", R.string.ai_provider_openai_chat, "https://api.openai.com/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("anthropic", R.string.ai_provider_anthropic, "https://api.anthropic.com/v1", AiApiProtocol.Anthropic),
        AiProviderPreset("google", R.string.ai_provider_google, "https://generativelanguage.googleapis.com/v1beta", AiApiProtocol.Gemini),
        AiProviderPreset("deepseek", R.string.ai_provider_deepseek, "https://api.deepseek.com/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("qwen", R.string.ai_provider_qwen, "https://dashscope.aliyuncs.com/compatible-mode/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("siliconflow", R.string.ai_provider_siliconflow, "https://api.siliconflow.cn/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("kimi", R.string.ai_provider_kimi, "https://api.moonshot.cn/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("zhipu", R.string.ai_provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", AiApiProtocol.ChatCompletions),
        AiProviderPreset("openrouter", R.string.ai_provider_openrouter, "https://openrouter.ai/api/v1", AiApiProtocol.ChatCompletions),
        AiProviderPreset("xai", R.string.ai_provider_xai, "https://api.x.ai/v1", AiApiProtocol.Responses)
    )
}

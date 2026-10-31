package dev.zakocoder.app.ai

import java.net.URI
import java.net.URISyntaxException

/** Summary requests may use the existing output reservation, never increase the normal budget. */
internal fun aiSummarySettings(config: AiConfig): AiConfig {
    val endpoint = try { URI(config.endpoint) } catch (_: URISyntaxException) { null }
    // DeepSeek's official thinking mode is enabled by default. A short evidence summary does
    // not need a reasoning stream competing with its answer for the output token allowance.
    // https://api-docs.deepseek.com/guides/thinking_mode
    val officialDeepSeekChat = config.protocol == AiApiProtocol.ChatCompletions && endpoint != null &&
        endpoint.scheme.equals("https", ignoreCase = true) &&
        endpoint.host.equals("api.deepseek.com", ignoreCase = true) &&
        endpoint.rawUserInfo == null && (endpoint.port == -1 || endpoint.port == 443)
    val parameters = config.parameters.copy(maxTokens = aiOutputReserve(config)).let {
        if (officialDeepSeekChat) it.copy(thinking = AiThinking.Disabled, reasoningEffort = null) else it
    }
    return config.copy(parameters = parameters)
}

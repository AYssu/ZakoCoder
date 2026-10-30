package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Independent mobile memory guard, not a provider context window. */
internal const val AI_REQUEST_MAX_BYTES = 32 * 1024 * 1024

/** Tokenizers differ per provider. ASCII/code ~3 chars/token, non-ASCII ~2 tokens/code point,
 * plus protocol overhead. This deliberately cautious estimate is never API billing usage. */
internal fun estimateAiTokens(text: String): Long {
    var ascii = 0L
    var unicode = 0L
    var index = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        if (point < 128) ascii++ else unicode += 2
        index += Character.charCount(point)
    }
    return (ascii + 2) / 3 + unicode
}

internal data class AiTokenBudget(val inputTokens: Long, val windowTokens: Int, val outputReserveTokens: Int,
    val thresholdPercent: Int) {
    val inputCapacity get() = windowTokens.toLong() - outputReserveTokens
    val shouldCompress get() = inputTokens > inputCapacity * thresholdPercent / 100
    val fits get() = inputCapacity > 0 && inputTokens <= inputCapacity
}

internal fun tokenBudget(config: AiConfig, messages: JSONArray, tools: JSONArray): AiTokenBudget {
    config.context.validate()
    val serialized = JSONObject().put("messages", messages).put("tools", tools).toString()
    if (serialized.toByteArray(Charsets.UTF_8).size > AI_REQUEST_MAX_BYTES)
        throw AiTransportException(kind = AiFailure.LocalResourceLimit, diagnostic = "local_request_32mib_limit")
    // Explicit output limits are honored, even when they make a small window invalid.
    val reserve = aiOutputReserve(config)
    val images = (0 until messages.length()).sumOf { messages.getJSONObject(it).optJSONArray("zako_images")?.length() ?: 0 }
    return AiTokenBudget(estimateAiTokens(serialized) + messages.length() * 8L + tools.length() * 16L + images * 4096L,
        config.context.contextWindowTokens, reserve, config.context.compressionThresholdPercent)
}

/** Old character preferences were local caps, not advertised model token limits. */
internal fun decodeContextOptions(json: JSONObject): AiContextOptions {
    val legacy = json.optInt("maxContextChars", 96000)
    val window = if (json.has("contextWindowTokens")) json.getInt("contextWindowTokens")
        else if (legacy == 96000) 128000 else (legacy / 3).coerceIn(8192, 2097152)
    return AiContextOptions(maxContextChars = legacy, autoCompress = json.optBoolean("autoCompress", true),
        compressionThresholdPercent = json.optInt("compressionThresholdPercent", 90),
        summaryMaxChars = json.optInt("summaryMaxChars", 6000), contextWindowTokens = window).also { it.validate() }
}

internal fun aiOutputReserve(config: AiConfig): Int =
    config.parameters.maxTokens ?: minOf(8192, config.context.contextWindowTokens / 4)

internal fun aiSummaryOutputReserve(config: AiConfig): Int = minOf(aiOutputReserve(config),
    (config.context.summaryMaxChars * 2 / 3).coerceIn(1024, 8192))

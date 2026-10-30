package dev.zakocoder.app.ai

import org.json.JSONObject

internal const val PROVIDER_OUTPUT_KEY = "zako_provider_output"

/** An adapter cannot return executable tools before its terminal event has been validated. */
internal interface AiProviderStream {
    val done: Boolean
    val text: String
    val textChars: Int
    val reasoning: String
    val reasoningChars: Int
    val argumentChars: Int
    val inputTokens: Long?
    val outputTokens: Long?
    val cachedTokens: Long?
    val finish: String
    fun accept(event: JSONObject)
    fun reply(): AiReply
}

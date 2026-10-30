package dev.zakocoder.app.ai

enum class AiApiProtocol(val requestPath: String) {
    ChatCompletions("/chat/completions"), Responses("/responses"),
    Anthropic("/messages"), Gemini("/models/{model}:streamGenerateContent")
}

enum class AiThinking { Default, Enabled, Disabled }
enum class AiReasoningEffort(val wire: String) { Low("low"), Medium("medium"), High("high"), Max("max") }

/** Only explicit parameters are sent; omission preserves the provider's defaults. */
data class AiParameters(val maxTokens: Int? = null, val temperature: Double? = null, val topP: Double? = null,
    val thinking: AiThinking = AiThinking.Default, val reasoningEffort: AiReasoningEffort? = null,
    val frequencyPenalty: Double? = null, val presencePenalty: Double? = null, val seed: Int? = null) {
    fun validate() {
        require(maxTokens == null || maxTokens in 1..32768)
        require(temperature == null || (temperature.isFinite() && temperature in 0.0..2.0))
        require(topP == null || (topP.isFinite() && topP > 0.0 && topP <= 1.0))
        require(frequencyPenalty == null || (frequencyPenalty.isFinite() && frequencyPenalty in -2.0..2.0))
        require(presencePenalty == null || (presencePenalty.isFinite() && presencePenalty in -2.0..2.0))
    }
}
/** Explicit provider context window in tokens; local estimates are approximate, not billing usage.
 * The first field remains source-compatible with older callers; it is never a runtime budget. */
data class AiContextOptions(val maxContextChars: Int = 96000, val autoCompress: Boolean = true,
    val compressionThresholdPercent: Int = 90, val summaryMaxChars: Int = 6000,
    val contextWindowTokens: Int = if (maxContextChars == 96000) 128000 else (maxContextChars / 3).coerceIn(8192, 2097152)) {
    @Deprecated("Legacy character metadata only; use token budgets")
    val compressionThresholdChars get() = maxContextChars * compressionThresholdPercent / 100
    fun validate() {
        require(contextWindowTokens in 8192..2097152)
        require(compressionThresholdPercent in 50..95)
        require(summaryMaxChars in 2000..12000)
    }
}
/** Header values never belong in catalog/UI state or diagnostics. */
object AiRequestHeaders {
    private val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val controlled = setOf("authorization", "proxy-authorization", "host", "content-length", "content-type", "connection",
        "transfer-encoding", "upgrade", "te", "trailer", "accept-encoding", "cookie", "set-cookie", "proxy-connection")
    fun validate(headers: Map<String, String>) {
        require(headers.size <= 16)
        require(headers.keys.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == headers.size)
        require(headers.entries.sumOf { it.key.length + it.value.length } <= 24000)
        headers.forEach { (name, value) ->
            require(name.length in 1..100 && token.matches(name) && name.lowercase(java.util.Locale.ROOT) !in controlled)
            require(value.length <= 8192 && value.all { it.code in 32..126 })
        }
    }
}
data class AiProfile(val id: String, val name: String, val endpoint: String, val model: String, val hasKey: Boolean,
    val parameters: AiParameters, val context: AiContextOptions = AiContextOptions(), val headerNames: List<String> = emptyList(),
    val protocol: AiApiProtocol = AiApiProtocol.ChatCompletions)
data class AiCatalog(val activeId: String? = null, val profiles: List<AiProfile> = emptyList()) {
    val active get() = profiles.singleOrNull { it.id == activeId }
}
/** Ephemeral input, deliberately redacted and never saved as UI instance state. */
data class AiModelDraft(val id: String?, val name: String, val endpoint: String, val model: String,
    val replacementKey: String?, val parameters: AiParameters = AiParameters(), val context: AiContextOptions = AiContextOptions(),
    val replacementHeaders: Map<String, String>? = null, val protocol: AiApiProtocol = AiApiProtocol.ChatCompletions) {
    override fun toString() = "AiModelDraft(id=$id, key=<redacted>, headers=<redacted>)"
}
class AiOriginChanged : IllegalArgumentException("Credential origin changed")

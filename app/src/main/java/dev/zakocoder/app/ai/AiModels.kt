package dev.zakocoder.app.ai

/** Only enabled parameters are sent; omission preserves the provider's own defaults. */
data class AiParameters(val maxTokens: Int? = null, val temperature: Double? = null, val topP: Double? = null) {
    fun validate() {
        require(maxTokens == null || maxTokens in 1..32768)
        require(temperature == null || (temperature.isFinite() && temperature in 0.0..2.0))
        require(topP == null || (topP.isFinite() && topP > 0.0 && topP <= 1.0))
    }
}
data class AiProfile(val id: String, val name: String, val endpoint: String, val model: String, val hasKey: Boolean, val parameters: AiParameters)
data class AiCatalog(val activeId: String? = null, val profiles: List<AiProfile> = emptyList()) {
    val active get() = profiles.singleOrNull { it.id == activeId }
}
/** Ephemeral input, deliberately redacted and never saved as UI instance state. */
data class AiModelDraft(val id: String?, val name: String, val endpoint: String, val model: String,
    val replacementKey: String?, val parameters: AiParameters = AiParameters()) {
    override fun toString() = "AiModelDraft(id=$id, key=<redacted>)"
}
class AiOriginChanged : IllegalArgumentException("Credential origin changed")

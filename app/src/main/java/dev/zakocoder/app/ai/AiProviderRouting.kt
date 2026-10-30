package dev.zakocoder.app.ai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** The selected protocol owns paths and authentication; model names never select a protocol. */
internal object AiProviderRouting {
    private fun modelId(model: String): String = model.removePrefix("models/").also {
        if (it.isBlank() || it.length > 200 || !it.all { char -> char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_." })
            throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "gemini_invalid_model_id")
    }
    fun requestAddress(base: String, protocol: AiApiProtocol, model: String): String {
        val url = AiConfigStore.endpoint(base).toHttpUrl().newBuilder()
        return if (protocol == AiApiProtocol.Gemini) {
            url.addPathSegment("models").addPathSegment("${modelId(model)}:streamGenerateContent")
                .addQueryParameter("alt", "sse").build().toString()
        } else AiConfigStore.endpoint(base) + protocol.requestPath
    }
    fun requestUrl(config: AiConfig): String = requestAddress(config.endpoint, config.protocol, config.model)
    fun modelsUrl(config: AiConfig): HttpUrl = (AiConfigStore.endpoint(config.endpoint) + "/models").toHttpUrl()
    fun headers(builder: Request.Builder, config: AiConfig): Request.Builder = builder.apply {
        AiRequestHeaders.validate(config.requestHeaders)
        config.requestHeaders.forEach { (name, value) -> header(name, value) }
        when (config.protocol) {
            AiApiProtocol.Anthropic -> {
                if (headerValueMissing(config, "anthropic-version")) header("anthropic-version", "2023-06-01")
                if (config.key.isNotEmpty()) header("x-api-key", config.key)
            }
            AiApiProtocol.Gemini -> if (config.key.isNotEmpty()) header("x-goog-api-key", config.key)
            else -> if (config.key.isNotEmpty()) header("Authorization", "Bearer ${config.key}")
        }
    }
    private fun headerValueMissing(config: AiConfig, name: String) = config.requestHeaders.keys.none { it.equals(name, true) }
    fun modelIds(json: JSONObject, protocol: AiApiProtocol): List<String> {
        val data = if (protocol == AiApiProtocol.Gemini) json.getJSONArray("models") else json.getJSONArray("data")
        require(data.length() <= 5000)
        return (0 until data.length()).mapNotNull { index ->
            val item = data.getJSONObject(index)
            if (protocol == AiApiProtocol.Gemini) {
                val methods = item.optJSONArray("supportedGenerationMethods")
                if (methods != null && (0 until methods.length()).none { methods.getString(it) == "generateContent" }) null
                else item.getString("name").removePrefix("models/")
            } else item.getString("id")
        }.filter { id -> id.isNotBlank() && id.length <= 200 && !id.any(Char::isISOControl) }.distinct()
    }
    fun nextPage(json: JSONObject, protocol: AiApiProtocol): String? = when (protocol) {
        AiApiProtocol.Gemini -> json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        AiApiProtocol.Anthropic -> if (json.optBoolean("has_more")) json.getString("last_id") else null
        else -> null
    }?.also { require(it.length <= 2048 && !it.any(Char::isISOControl)) }
}

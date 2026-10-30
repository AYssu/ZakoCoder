package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native generateContent wire mapping. No SDK, host execution or transport ownership. */
internal object AiGeminiWire {
    private data class Pending(val name: String, val nativeId: String?)
    fun request(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String? = null): JSONObject {
        config.parameters.validate()
        val params = config.parameters
        if (params.reasoningEffort == AiReasoningEffort.Max || params.thinking == AiThinking.Disabled && params.reasoningEffort != null)
            configuration("gemini_unsupported_thinking_parameters")
        val contents = JSONArray()
        val system = JSONArray()
        val pending = linkedMapOf<String, Pending>()
        fun append(role: String, parts: JSONArray) {
            if (parts.length() == 0) return
            // Parallel tool responses belong to a single user content, as required by Gemini.
            val previous = if (contents.length() > 0) contents.getJSONObject(contents.length() - 1) else null
            if (role == "user" && previous?.optString("role") == "user") {
                repeat(parts.length()) { previous.getJSONArray("parts").put(parts.getJSONObject(it)) }
            } else contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        repeat(messages.length()) { index ->
            val message = messages.getJSONObject(index)
            when (message.getString("role")) {
                "system", "developer" -> content(message.opt("content"), false).let { parts -> repeat(parts.length()) { system.put(parts.getJSONObject(it)) } }
                "user" -> append("user", content(message.opt("content"), true))
                "assistant" -> {
                    val metadata = message.optJSONObject(PROVIDER_OUTPUT_KEY)
                    val replay = metadata?.takeIf { it.optString("protocol") == "Gemini" }?.getJSONArray("output")
                    val calls = message.optJSONArray("tool_calls") ?: JSONArray()
                    val parts = if (replay != null) JSONArray(replay.toString()).also(::validateParts) else content(message.opt("content"), false)
                    val nativeCalls = parts.objects().mapNotNull { it.optJSONObject("functionCall") }
                    if (replay != null && calls.length() != nativeCalls.size) invalid("gemini_replay_call_mismatch")
                    repeat(calls.length()) { callIndex ->
                        val call = calls.getJSONObject(callIndex)
                        if (call.optString("type", "function") != "function") invalid("gemini_unsupported_tool")
                        val function = call.getJSONObject("function")
                        val id = call.getString("id")
                        val name = function.getString("name")
                        if (pending.containsKey(id)) invalid("duplicate_tool_id")
                        val native = if (replay != null) nativeCalls[callIndex].also {
                            if (it.getString("name") != name || JSONObject(function.getString("arguments")).toString() != arguments(it).toString()) invalid("gemini_replay_call_mismatch")
                        } else JSONObject().put("name", name).put("args", JSONObject(function.getString("arguments"))).put("id", id).also {
                            parts.put(JSONObject().put("functionCall", it))
                        }
                        pending[id] = Pending(name, native.optString("id").takeIf(String::isNotEmpty))
                    }
                    validateParts(parts)
                    append("model", parts)
                }
                "tool" -> {
                    val callId = message.getString("tool_call_id")
                    val call = pending.remove(callId) ?: invalid("gemini_unknown_tool_result")
                    val result = message.getString("content")
                    val response = runCatching { JSONObject(result) }.getOrElse { JSONObject().put("output", result) }
                    append("user", JSONArray().put(JSONObject().put("functionResponse", JSONObject()
                        .put("name", call.name).put("response", response).apply { call.nativeId?.let { put("id", it) } })))
                }
                else -> invalid("gemini_invalid_role")
            }
        }
        val declarations = JSONArray()
        repeat(tools.length()) { index ->
            val tool = tools.getJSONObject(index)
            if (tool.getString("type") != "function") configuration("gemini_unsupported_tool")
            val function = tool.getJSONObject("function")
            declarations.put(JSONObject().put("name", function.getString("name")).put("description", function.optString("description"))
                .put("parametersJsonSchema", JSONObject(function.getJSONObject("parameters").toString())))
        }
        return JSONObject().put("contents", contents).apply {
            if (system.length() > 0) put("systemInstruction", JSONObject().put("parts", system))
            if (declarations.length() > 0) put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            if (forceTool != null) {
                if (declarations.objects().none { it.getString("name") == forceTool }) configuration("gemini_invalid_tool_choice")
                put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "ANY")
                    .put("allowedFunctionNames", JSONArray().put(forceTool))))
            }
            val generation = JSONObject()
            params.maxTokens?.let { generation.put("maxOutputTokens", it) }
            params.temperature?.let { generation.put("temperature", it) }
            params.topP?.let { generation.put("topP", it) }
            params.seed?.let { generation.put("seed", it) }
            params.frequencyPenalty?.let { generation.put("frequencyPenalty", it) }
            params.presencePenalty?.let { generation.put("presencePenalty", it) }
            val thinking = JSONObject()
            if (params.thinking == AiThinking.Enabled) thinking.put("includeThoughts", true)
            if (params.thinking == AiThinking.Disabled) thinking.put("thinkingBudget", 0)
            params.reasoningEffort?.let { thinking.put("thinkingLevel", it.wire.uppercase(java.util.Locale.ROOT)) }
            if (thinking.length() > 0) generation.put("thinkingConfig", thinking)
            if (generation.length() > 0) put("generationConfig", generation)
        }
    }

    private fun content(value: Any?, images: Boolean): JSONArray {
        if (value == null || value == JSONObject.NULL) return JSONArray()
        if (value is String) return JSONArray().apply { if (value.isNotEmpty()) put(JSONObject().put("text", value)) }
        val source = value as? JSONArray ?: invalid("gemini_invalid_content")
        return JSONArray().apply { repeat(source.length()) { index ->
            val part = source.getJSONObject(index)
            when (part.getString("type")) {
                "text" -> put(JSONObject().put("text", part.getString("text")))
                "image_url" -> {
                    if (!images) invalid("gemini_invalid_image_role")
                    val url = part.getJSONObject("image_url").getString("url")
                    val match = Regex("^data:(image/(?:png|jpeg|webp|heic|heif));base64,([A-Za-z0-9+/=\\r\\n]+)$").matchEntire(url)
                        ?: throw AiTransportException(kind = AiFailure.Image, diagnostic = "gemini_inline_image_required")
                    put(JSONObject().put("inlineData", JSONObject().put("mimeType", match.groupValues[1]).put("data", match.groupValues[2])))
                }
                else -> invalid("gemini_unsupported_content")
            }
        } }
    }

    internal fun arguments(call: JSONObject): JSONObject = if (call.isNull("args")) JSONObject() else call.getJSONObject("args")

    internal fun validateParts(parts: JSONArray) {
        if (parts.length() > 8192) invalid("gemini_part_limit")
        var text = 0L; var reasoning = 0L; var arguments = 0L; var signatures = 0L; var calls = 0
        val nativeIds = hashSetOf<String>()
        parts.objects().forEach { part ->
            val known = part.keys().asSequence().toSet()
            if (known.any { it !in setOf("text", "thought", "thoughtSignature", "functionCall") }) invalid("gemini_unsupported_output_part")
            if (part.has("text") && part.has("functionCall")) invalid("gemini_invalid_part")
            if (part.has("text")) {
                if (part.optBoolean("thought")) reasoning += part.getString("text").length else text += part.getString("text").length
            }
            part.optJSONObject("functionCall")?.let { call ->
                if (++calls > 8) invalid("tool_call_limit")
                val name = call.getString("name")
                if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,127}"))) invalid("invalid_tool_identity")
                if (call.has("partialArgs") || call.optBoolean("willContinue")) invalid("gemini_partial_tool_arguments_unsupported")
                arguments += AiGeminiWire.arguments(call).toString().toByteArray(Charsets.UTF_8).size
                call.optString("id").takeIf(String::isNotEmpty)?.let {
                    if (it.length > 200 || !nativeIds.add(it)) invalid("duplicate_tool_id")
                }
            }
            if (part.has("thoughtSignature")) signatures += part.getString("thoughtSignature").length
            if (text > 256000 || reasoning > 256000) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "gemini_text_limit")
            if (arguments > 8 * 1024 * 1024 || signatures > 1024 * 1024) invalid("gemini_output_limit")
            if (!part.has("text") && !part.has("functionCall") && !part.has("thoughtSignature")) invalid("gemini_empty_output_part")
        }
    }

    fun diagnostic(body: JSONObject): JSONObject {
        fun scrub(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key ->
                    put(key, when {
                        key == "inlineData" -> JSONObject().put("mimeType", value.getJSONObject(key).optString("mimeType")).put("data", "[image omitted]")
                        key == "thoughtSignature" -> "[opaque thought signature omitted]"
                        key == "text" && value.optBoolean("thought") -> "[thought text omitted]"
                        else -> scrub(value.get(key))
                    })
                }
            }
            is JSONArray -> JSONArray().apply { repeat(value.length()) { put(scrub(value.get(it))) } }
            else -> value
        }
        return scrub(body) as JSONObject
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun configuration(code: String): Nothing = throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = code)
    internal fun invalid(code: String): Nothing = throw AiTransportException(diagnostic = code)
}

/** A single candidate and attempt. Tools become executable only after an explicit STOP. */
internal class AiGeminiStream(private val delta: (String) -> Unit = {}, private val reasoningDelta: (String) -> Unit = {},
    private val toolDelta: (List<AiCallDraft>) -> Unit = {}) : AiProviderStream {
    private val parts = JSONArray()
    private val textBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private val prefix = "gemini-${UUID.randomUUID()}"
    override var done: Boolean = false; private set
    override val text get() = textBuilder.toString()
    override val textChars get() = textBuilder.length
    override val reasoning get() = reasoningBuilder.toString()
    override val reasoningChars get() = reasoningBuilder.length
    override var argumentChars: Int = 0; private set
    override var inputTokens: Long? = null; private set
    override var outputTokens: Long? = null; private set
    override var cachedTokens: Long? = null; private set
    override var finish: String = ""; private set
    private val calls = mutableListOf<AiCall>()
    private val nativeIds = hashSetOf<String>()
    private var argumentBytes = 0L
    private var signatureChars = 0L
    private var candidateOutputTokens: Long? = null
    private var thoughtOutputTokens: Long? = null
    private var pendingText: StringBuilder? = null
    private var pendingThought = false
    private var pendingHasThought = false

    private fun flushTextPart() {
        val pending = pendingText ?: return
        parts.put(JSONObject().put("text", pending.toString()).apply {
            if (pendingHasThought) put("thought", pendingThought)
        })
        pendingText = null
    }

    private fun appendPart(part: JSONObject) {
        val unsignedText = part.has("text") && part.keys().asSequence().all { it == "text" || it == "thought" }
        if (unsignedText) {
            val thought = part.optBoolean("thought")
            if (pendingText != null && pendingThought == thought) {
                pendingText!!.append(part.getString("text"))
                return
            }
            flushTextPart()
            if (parts.length() >= 8192) AiGeminiWire.invalid("gemini_part_limit")
            pendingThought = thought
            pendingHasThought = part.has("thought")
            pendingText = StringBuilder(part.getString("text"))
            return
        }
        // Signed parts are opaque replay data: retain their exact boundaries and fields.
        flushTextPart()
        if (parts.length() >= 8192) AiGeminiWire.invalid("gemini_part_limit")
        parts.put(part)
    }

    override fun accept(event: JSONObject) {
        if (event.has("error")) {
            val error = event.getJSONObject("error")
            AiGeminiWire.invalid(when {
                error.optInt("code") == 429 || error.optString("status") == "RESOURCE_EXHAUSTED" -> "gemini_rate_limit"
                error.optInt("code") == 503 || error.optString("status") == "UNAVAILABLE" -> "gemini_unavailable"
                else -> "gemini_provider_error"
            })
        }
        event.optJSONObject("usageMetadata")?.let { usage ->
            fun count(key: String): Long? = if (usage.isNull(key)) null else usage.getLong(key).also { if (it < 0) AiGeminiWire.invalid("gemini_invalid_usage") }
            inputTokens = count("promptTokenCount") ?: inputTokens
            candidateOutputTokens = count("candidatesTokenCount") ?: candidateOutputTokens
            thoughtOutputTokens = count("thoughtsTokenCount") ?: thoughtOutputTokens
            if (candidateOutputTokens != null || thoughtOutputTokens != null) outputTokens = try {
                Math.addExact(candidateOutputTokens ?: 0, thoughtOutputTokens ?: 0)
            } catch (_: ArithmeticException) { AiGeminiWire.invalid("gemini_invalid_usage") }
            cachedTokens = count("cachedContentTokenCount") ?: cachedTokens
        }
        event.optJSONObject("promptFeedback")?.let { if (it.optString("blockReason").isNotBlank() && it.optString("blockReason") != "BLOCK_REASON_UNSPECIFIED") AiGeminiWire.invalid("gemini_prompt_blocked") }
        val candidates = event.optJSONArray("candidates") ?: return
        if (candidates.length() == 0) return
        if (done) AiGeminiWire.invalid("gemini_event_after_completed")
        if (candidates.length() != 1) AiGeminiWire.invalid("gemini_multiple_candidates")
        val candidate = candidates.getJSONObject(0)
        if (candidate.optInt("index", 0) != 0) AiGeminiWire.invalid("gemini_invalid_candidate")
        candidate.optJSONObject("content")?.let { content ->
            if (content.optString("role", "model") != "model") AiGeminiWire.invalid("gemini_invalid_output_role")
            content.optJSONArray("parts")?.let { incoming ->
                // Validate the complete accumulated stream before delivering any tool drafts.
                AiGeminiWire.validateParts(incoming)
                var nextText = textChars.toLong(); var nextReasoning = reasoningChars.toLong()
                var nextArguments = argumentBytes; var nextSignatures = signatureChars; var nextCalls = calls.size
                val nextIds = nativeIds.toMutableSet()
                repeat(incoming.length()) { index ->
                    val part = incoming.getJSONObject(index)
                    if (part.has("text")) {
                        if (part.optBoolean("thought")) nextReasoning += part.getString("text").length else nextText += part.getString("text").length
                    }
                    if (part.has("thoughtSignature")) nextSignatures += part.getString("thoughtSignature").length
                    part.optJSONObject("functionCall")?.let { call ->
                        nextCalls++
                        nextArguments += AiGeminiWire.arguments(call).toString().toByteArray(Charsets.UTF_8).size
                        call.optString("id").takeIf(String::isNotEmpty)?.let { if (!nextIds.add(it)) AiGeminiWire.invalid("duplicate_tool_id") }
                    }
                }
                if (nextText > 256000 || nextReasoning > 256000) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "gemini_text_limit")
                if (nextCalls > 8) AiGeminiWire.invalid("tool_call_limit")
                if (nextArguments > 8 * 1024 * 1024 || nextSignatures > 1024 * 1024) AiGeminiWire.invalid("gemini_output_limit")
                nativeIds.clear(); nativeIds.addAll(nextIds)
                argumentBytes = nextArguments; signatureChars = nextSignatures
                repeat(incoming.length()) { index ->
                    val part = JSONObject(incoming.getJSONObject(index).toString())
                    part.optJSONObject("functionCall")?.let { function ->
                        val arguments = AiGeminiWire.arguments(function).toString()
                        calls += AiCall(function.optString("id").takeIf(String::isNotEmpty) ?: "$prefix-${calls.size}", function.getString("name"), arguments)
                        argumentChars += arguments.length
                    }
                    if (part.has("text")) {
                        val value = part.getString("text")
                        if (part.optBoolean("thought")) { reasoningBuilder.append(value); reasoningDelta(value) }
                        else { textBuilder.append(value); delta(value) }
                    }
                    appendPart(part)
                }
                toolDelta(calls.mapIndexed { index, call -> AiCallDraft(index, call.name, call.arguments.take(64000)) })
            }
        }
        candidate.optString("finishReason").takeIf(String::isNotEmpty)?.let { reason ->
            when (reason) {
                "STOP" -> { finish = if (calls.isEmpty()) "stop" else "tool_calls"; done = true }
                "MAX_TOKENS" -> throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
                "SAFETY", "RECITATION", "LANGUAGE", "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
                "MALFORMED_FUNCTION_CALL", "IMAGE_SAFETY", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "NO_IMAGE",
                "IMAGE_PROHIBITED_CONTENT", "IMAGE_OTHER", "IMAGE_RECITATION" -> AiGeminiWire.invalid("gemini_finish_${reason.lowercase(java.util.Locale.ROOT)}")
                else -> AiGeminiWire.invalid("gemini_invalid_finish_reason")
            }
        }
    }

    override fun reply(): AiReply {
        if (!done) AiGeminiWire.invalid("missing_done")
        flushTextPart()
        return AiReply(text, calls.toList(), providerOutput = parts.toString(), providerProtocol = "Gemini")
    }
}

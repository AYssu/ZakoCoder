package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Native Messages protocol. No server-side conversation identifier or inferred model rewriting. */
internal object AiAnthropicWire {
    fun request(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String? = null): JSONObject {
        val parameters = config.parameters
        parameters.validate()
        val maxTokens = parameters.maxTokens ?: 8192
        if (parameters.seed != null || parameters.frequencyPenalty != null || parameters.presencePenalty != null ||
            parameters.temperature?.let { it > 1.0 } == true ||
            (parameters.thinking == AiThinking.Disabled && parameters.reasoningEffort != null) ||
            (parameters.thinking == AiThinking.Enabled && (maxTokens <= 1024 || parameters.temperature != null || parameters.topP != null || forceTool != null)))
            configuration()
        val system = JSONArray(); val input = JSONArray()
        fun append(role: String, content: JSONArray) {
            if (content.length() == 0) return
            val previous = input.optJSONObject(input.length() - 1)
            if (previous?.optString("role") == role) {
                val target = previous.getJSONArray("content")
                repeat(content.length()) { target.put(content.getJSONObject(it)) }
            } else input.put(JSONObject().put("role", role).put("content", content))
        }
        repeat(messages.length()) { index ->
            val message = messages.getJSONObject(index)
            when (val role = message.getString("role")) {
                "system", "developer" -> {
                    val content = message.getString("content")
                    if (content.isNotEmpty()) system.put(JSONObject().put("type", "text").put("text", content))
                }
                "user", "assistant" -> {
                    val metadata = message.optJSONObject(PROVIDER_OUTPUT_KEY)
                    val converted = if (role == "assistant" && metadata?.optString("protocol") == "Anthropic") {
                        JSONArray(metadata.getJSONArray("output").toString()).also(::validateOutput)
                    } else {
                        val result = content(message.opt("content"), role == "assistant")
                        message.optJSONArray("tool_calls")?.let { calls ->
                            if (role != "assistant") invalid("anthropic_invalid_input")
                            repeat(calls.length()) { callIndex ->
                                val call = calls.getJSONObject(callIndex)
                                if (call.optString("type", "function") != "function") invalid("anthropic_unsupported_tool")
                                val function = call.getJSONObject("function")
                                result.put(JSONObject().put("type", "tool_use").put("id", call.getString("id"))
                                    .put("name", function.getString("name")).put("input", JSONObject(function.getString("arguments"))))
                            }
                        }
                        result
                    }
                    append(role, converted)
                }
                "tool" -> append("user", JSONArray().put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", message.getString("tool_call_id")).put("content", message.getString("content"))))
                else -> invalid("anthropic_invalid_input")
            }
        }
        val functions = JSONArray()
        repeat(tools.length()) { index ->
            val tool = tools.getJSONObject(index)
            if (tool.getString("type") != "function") invalid("anthropic_unsupported_tool")
            val function = tool.getJSONObject("function")
            functions.put(JSONObject().put("name", function.getString("name")).put("description", function.optString("description"))
                .put("input_schema", function.getJSONObject("parameters")).apply {
                    if (function.has("strict")) put("strict", function.getBoolean("strict"))
                })
        }
        return JSONObject().put("model", config.model).put("messages", input).put("stream", true).put("max_tokens", maxTokens).apply {
            if (system.length() > 0) put("system", system)
            if (functions.length() > 0) put("tools", functions)
            if (forceTool != null) {
                if ((0 until functions.length()).none { functions.getJSONObject(it).getString("name") == forceTool }) invalid("anthropic_invalid_tool_choice")
                put("tool_choice", JSONObject().put("type", "tool").put("name", forceTool))
            }
            parameters.temperature?.let { put("temperature", it) }
            parameters.topP?.let { put("top_p", it) }
            parameters.reasoningEffort?.let { put("output_config", JSONObject().put("effort", it.wire)) }
            when (parameters.thinking) {
                AiThinking.Enabled -> put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", minOf(4096, maxTokens - 1)))
                AiThinking.Disabled -> put("thinking", JSONObject().put("type", "disabled"))
                AiThinking.Default -> Unit
            }
        }
    }

    private fun content(value: Any?, assistant: Boolean): JSONArray {
        if (value == null || value == JSONObject.NULL) return JSONArray()
        if (value is String) return JSONArray().apply { if (value.isNotEmpty()) put(JSONObject().put("type", "text").put("text", value)) }
        val parts = value as? JSONArray ?: invalid("anthropic_invalid_input")
        return JSONArray().apply {
            repeat(parts.length()) { index ->
                val part = parts.getJSONObject(index)
                when (part.getString("type")) {
                    "text" -> put(JSONObject().put("type", "text").put("text", part.getString("text")))
                    "image_url" -> {
                        if (assistant) invalid("anthropic_invalid_input")
                        val image = part.getJSONObject("image_url")
                        if (image.optString("detail", "auto") != "auto") configuration()
                        val url = image.getString("url")
                        val source = JSONObject()
                        if (url.startsWith("data:")) {
                            val match = Regex("data:(image/(?:jpeg|png|gif|webp));base64,([A-Za-z0-9+/]+={0,2})").matchEntire(url)
                                ?: invalid("anthropic_invalid_image")
                            source.put("type", "base64").put("media_type", match.groupValues[1]).put("data", match.groupValues[2])
                        } else {
                            val uri = try { URI(url) } catch (_: Exception) { invalid("anthropic_invalid_image") }
                            if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) invalid("anthropic_invalid_image")
                            source.put("type", "url").put("url", url)
                        }
                        put(JSONObject().put("type", "image").put("source", source))
                    }
                    else -> invalid("anthropic_invalid_input")
                }
            }
        }
    }

    internal fun validateOutput(blocks: JSONArray) {
        if (blocks.length() > 64) invalid("anthropic_content_limit")
        var text = 0L; var reasoning = 0L; var opaque = 0L; var arguments = 0L
        val ids = hashSetOf<String>()
        repeat(blocks.length()) { index ->
            val block = blocks.getJSONObject(index)
            when (block.getString("type")) {
                "text" -> text += block.getString("text").length
                "thinking" -> { reasoning += block.getString("thinking").length; opaque += block.getString("signature").length }
                "redacted_thinking" -> opaque += block.getString("data").length
                "tool_use" -> {
                    val id = block.getString("id"); val name = block.getString("name")
                    if (id.isEmpty() || id.length > 200 || name.isEmpty() || name.length > 100 || !ids.add(id)) invalid("invalid_tool_identity")
                    block.optJSONObject("caller")?.let { if (it.optString("type") != "direct") invalid("anthropic_unsupported_tool") }
                    arguments += block.getJSONObject("input").toString().length
                }
                else -> invalid("anthropic_unsupported_content")
            }
        }
        if (ids.size > 8) throw AiTransportException(kind = AiFailure.ToolLimit)
        if (text > AI_RESPONSE_TEXT_LIMIT || reasoning > AI_RESPONSE_TEXT_LIMIT || opaque > 8 * 1024 * 1024 || arguments > 8 * 1024 * 1024)
            throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_content_limit")
    }

    fun diagnostic(body: JSONObject): JSONObject {
        fun scrub(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { name ->
                val opaque = name == "signature" || (name == "data" && value.optString("type") in setOf("redacted_thinking", "base64"))
                put(name, if (opaque) "[opaque content omitted]" else scrub(value.get(name)))
            } }
            is JSONArray -> JSONArray().apply { repeat(value.length()) { put(scrub(value.get(it))) } }
            else -> value
        }
        return scrub(body) as JSONObject
    }
    private fun configuration(): Nothing = throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "anthropic_unsupported_parameters")
    internal fun invalid(code: String): Nothing = throw AiTransportException(diagnostic = code)
}

/** Each instance owns one Messages attempt and yields executable tools only after message_stop. */
internal class AiAnthropicStream(private val delta: (String) -> Unit = {}, private val reasoningDelta: (String) -> Unit = {},
    private val toolDelta: (List<AiCallDraft>) -> Unit = {}) : AiProviderStream {
    private data class Block(val value: JSONObject, val text: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(), val signature: StringBuilder = StringBuilder(),
        var stopped: Boolean = false, var finalArguments: String? = null)
    private val blocks = sortedMapOf<Int, Block>()
    private var started = false
    private var initialBlockChars = 0L
    private var stopReason: String? = null
    private var rawInputTokens: Long? = null
    private var cacheCreationTokens = 0L
    override var done = false; private set
    override var inputTokens: Long? = null; private set
    override var outputTokens: Long? = null; private set
    override var cachedTokens: Long? = null; private set
    override var finish = ""; private set
    override val text get() = blocks.values.filter { it.value.getString("type") == "text" }.joinToString("") { it.text.toString() }
    override val textChars get() = blocks.values.filter { it.value.getString("type") == "text" }.sumOf { it.text.length }
    override val reasoning get() = blocks.values.filter { it.value.getString("type") == "thinking" }.joinToString("") { it.text.toString() }
    override val reasoningChars get() = blocks.values.filter { it.value.getString("type") == "thinking" }.sumOf { it.text.length }
    override val argumentChars get() = blocks.values.sumOf { it.finalArguments?.length ?: it.arguments.length }
    private val drafts get() = blocks.filterValues { it.value.getString("type") == "tool_use" }.map { (index, block) ->
        val value = block.finalArguments ?: block.arguments.toString()
        AiCallDraft(index, block.value.getString("name"), value.take(64000))
    }
    private fun invalid(code: String): Nothing = AiAnthropicWire.invalid(code)
    private fun usage(value: JSONObject?) {
        if (value == null) return
        fun number(name: String): Long? = if (value.isNull(name)) null else value.getLong(name).also { if (it < 0) invalid("anthropic_invalid_usage") }
        number("input_tokens")?.let { rawInputTokens = it }
        number("cache_creation_input_tokens")?.let { cacheCreationTokens = it }
        number("cache_read_input_tokens")?.let { cachedTokens = it }
        number("output_tokens")?.let { outputTokens = it }
        rawInputTokens?.let {
            val cached = cachedTokens ?: 0
            if (it > Long.MAX_VALUE - cacheCreationTokens || it + cacheCreationTokens > Long.MAX_VALUE - cached) invalid("anthropic_invalid_usage")
            inputTokens = it + cacheCreationTokens + cached
        }
    }
    private fun block(event: JSONObject): Block {
        if (!started || stopReason != null) invalid("anthropic_invalid_event_order")
        val block = blocks[event.getInt("index")] ?: invalid("anthropic_unknown_content_block")
        if (block.stopped) invalid("anthropic_closed_content_block")
        return block
    }
    private fun append(block: Block, value: String, kind: String) {
        val capacity = when (kind) {
            "text" -> AI_RESPONSE_TEXT_LIMIT - textChars
            "thinking" -> AI_RESPONSE_TEXT_LIMIT - reasoningChars
            "arguments" -> 8 * 1024 * 1024 - argumentChars
            else -> 8 * 1024 * 1024 - blocks.values.sumOf { it.signature.length + if (it.value.optString("type") == "redacted_thinking") it.value.optString("data").length else 0 }
        }
        if (value.length > capacity) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_${kind}_limit")
        when (kind) {
            "text" -> { block.text.append(value); if (value.isNotEmpty()) delta(value) }
            "thinking" -> { block.text.append(value); if (value.isNotEmpty()) reasoningDelta(value) }
            "arguments" -> block.arguments.append(value)
            else -> block.signature.append(value)
        }
    }
    override fun accept(event: JSONObject) {
        if (done) invalid("anthropic_event_after_stop")
        when (event.getString("type")) {
            "ping" -> Unit
            "message_start" -> {
                if (started) invalid("anthropic_duplicate_message_start")
                val message = event.getJSONObject("message")
                if (message.optString("role") != "assistant" || message.getJSONArray("content").length() != 0 || !message.isNull("stop_reason")) invalid("anthropic_invalid_message_start")
                started = true; usage(message.optJSONObject("usage"))
            }
            "content_block_start" -> {
                if (!started || stopReason != null) invalid("anthropic_invalid_event_order")
                val index = event.getInt("index")
                if (index !in 0..63 || blocks.containsKey(index) || blocks.values.any { !it.stopped }) invalid("anthropic_invalid_content_index")
                val value = JSONObject(event.getJSONObject("content_block").toString())
                initialBlockChars += value.toString().length
                if (initialBlockChars > 32 * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_content_limit")
                val block = Block(value); blocks[index] = block
                when (value.getString("type")) {
                    "text" -> append(block, value.getString("text"), "text")
                    "thinking" -> { append(block, value.getString("thinking"), "thinking"); append(block, value.optString("signature"), "signature") }
                    "redacted_thinking" -> if (blocks.values.sumOf { it.signature.length.toLong() + if (it.value.optString("type") == "redacted_thinking") it.value.getString("data").length else 0 } > 8 * 1024 * 1024)
                        throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_opaque_limit")
                    "tool_use" -> {
                        val id = value.getString("id"); val name = value.getString("name")
                        if (id.isEmpty() || id.length > 200 || name.isEmpty() || name.length > 100 || blocks.values.count { it.value.optString("id") == id } > 1) invalid("invalid_tool_identity")
                        val initialInput = value.getJSONObject("input")
                        if (initialInput.length() > 0 && initialInput.toString().length > 8 * 1024 * 1024 - argumentChars)
                            throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_arguments_limit")
                        value.optJSONObject("caller")?.let { if (it.optString("type") != "direct") invalid("anthropic_unsupported_tool") }
                        if (blocks.values.count { it.value.getString("type") == "tool_use" } > 8) throw AiTransportException(kind = AiFailure.ToolLimit)
                        toolDelta(drafts)
                    }
                    else -> invalid("anthropic_unsupported_content")
                }
            }
            "content_block_delta" -> {
                val block = block(event); val part = event.getJSONObject("delta")
                val type = block.value.getString("type")
                when (part.getString("type")) {
                    "text_delta" -> { if (type != "text") invalid("anthropic_content_type_mismatch"); append(block, part.getString("text"), "text") }
                    "thinking_delta" -> { if (type != "thinking") invalid("anthropic_content_type_mismatch"); append(block, part.getString("thinking"), "thinking") }
                    "signature_delta" -> { if (type != "thinking") invalid("anthropic_content_type_mismatch"); append(block, part.getString("signature"), "signature") }
                    "input_json_delta" -> {
                        if (type != "tool_use" || block.value.getJSONObject("input").length() != 0) invalid("anthropic_content_type_mismatch")
                        append(block, part.getString("partial_json"), "arguments"); toolDelta(drafts)
                    }
                    "citations_delta" -> {
                        if (type != "text") invalid("anthropic_content_type_mismatch")
                        val citations = block.value.optJSONArray("citations") ?: JSONArray().also { block.value.put("citations", it) }
                        if (citations.length() >= 128) invalid("anthropic_citations_limit")
                        citations.put(part.getJSONObject("citation"))
                        if (blocks.values.sumOf { it.value.optJSONArray("citations")?.toString()?.length?.toLong() ?: 0L } > AI_RESPONSE_TEXT_LIMIT)
                            throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_citations_limit")
                    }
                    else -> invalid("anthropic_unsupported_delta")
                }
            }
            "content_block_stop" -> {
                val block = block(event)
                when (block.value.getString("type")) {
                    "text" -> block.value.put("text", block.text.toString())
                    "thinking" -> {
                        if (block.signature.isEmpty()) invalid("anthropic_missing_thinking_signature")
                        block.value.put("thinking", block.text.toString()).put("signature", block.signature.toString())
                    }
                    "tool_use" -> {
                        val arguments = if (block.arguments.isEmpty()) block.value.getJSONObject("input") else JSONObject(block.arguments.toString())
                        block.finalArguments = arguments.toString(); block.value.put("input", arguments)
                        if (argumentChars > 8 * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "anthropic_arguments_limit")
                        toolDelta(drafts)
                    }
                }
                block.stopped = true
            }
            "message_delta" -> {
                if (!started || blocks.values.any { !it.stopped }) invalid("anthropic_invalid_event_order")
                val reason = event.getJSONObject("delta").optString("stop_reason").takeIf { it.isNotEmpty() && it != "null" }
                if (reason != null) {
                    if (stopReason != null && stopReason != reason) invalid("anthropic_conflicting_stop_reason")
                    stopReason = reason
                    finish = when (reason) { "max_tokens" -> "length"; "tool_use" -> "tool_calls"; "end_turn", "stop_sequence", "refusal" -> "stop"; else -> "other" }
                }
                usage(event.optJSONObject("usage"))
            }
            "message_stop" -> {
                if (!started || blocks.values.any { !it.stopped } || blocks.keys.toList() != (0 until blocks.size).toList()) invalid("anthropic_invalid_event_order")
                if (stopReason == "max_tokens") throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
                if (stopReason == "model_context_window_exceeded") throw AiTransportException(kind = AiFailure.ContextLimit, diagnostic = "anthropic_context_limit")
                val calls = blocks.values.count { it.value.getString("type") == "tool_use" }
                if ((calls > 0 && stopReason != "tool_use") || (calls == 0 && stopReason !in setOf("end_turn", "stop_sequence", "refusal"))) invalid("anthropic_unexpected_stop_reason")
                AiAnthropicWire.validateOutput(JSONArray(blocks.values.map { it.value }))
                finish = if (calls > 0) "tool_calls" else "stop"; done = true
            }
            "error" -> {
                val type = event.optJSONObject("error")?.optString("type")
                invalid(when (type) { "overloaded_error" -> "anthropic_overloaded"; "rate_limit_error" -> "anthropic_rate_limit"; else -> "anthropic_failed" })
            }
            else -> invalid("anthropic_unsupported_event")
        }
    }
    override fun reply(): AiReply {
        if (!done) invalid("missing_done")
        val calls = blocks.values.filter { it.value.getString("type") == "tool_use" }.map {
            AiCall(it.value.getString("id"), it.value.getString("name"), requireNotNull(it.finalArguments))
        }
        return AiReply(text, calls, reasoning.takeIf { it.isNotEmpty() }, providerOutput = JSONArray(blocks.values.map { it.value }).toString(), providerProtocol = "Anthropic")
    }
}

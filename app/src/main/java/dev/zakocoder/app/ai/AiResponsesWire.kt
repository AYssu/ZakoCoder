package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

internal const val RESPONSES_OUTPUT_KEY = "zako_responses_output"

/** Stateless Responses adapter. Internal history remains independent of the transport protocol. */
internal object AiResponsesWire {
    const val OUTPUT_KEY = RESPONSES_OUTPUT_KEY
    fun request(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String? = null): JSONObject {
        val parameters = config.parameters
        parameters.validate()
        if (parameters.seed != null || parameters.frequencyPenalty != null || parameters.presencePenalty != null)
            throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "responses_unsupported_parameters")
        if (parameters.thinking == AiThinking.Disabled && parameters.reasoningEffort != null)
            throw AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "responses_unsupported_parameters")
        val input = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val role = message.getString("role")
            if (role == "assistant" && message.has(OUTPUT_KEY)) {
                val output = message.getJSONArray(OUTPUT_KEY)
                validateReplay(output)
                for (item in 0 until output.length()) {
                    val value = output.getJSONObject(item)
                    validateOutputType(value)
                    input.put(JSONObject(value.toString()))
                }
                continue
            }
            when (role) {
                "system", "developer", "user", "assistant" -> {
                    val content = message.opt("content")
                    if (content != null && content != JSONObject.NULL && (content !is String || content.isNotEmpty()))
                        input.put(JSONObject().put("role", role).put("content", content(content, role == "assistant")))
                    message.optJSONArray("tool_calls")?.let { calls ->
                        if (role != "assistant") invalid("responses_invalid_input")
                        repeat(calls.length()) { callIndex ->
                            val call = calls.getJSONObject(callIndex)
                            if (call.optString("type", "function") != "function") invalid("responses_unsupported_tool")
                            val function = call.getJSONObject("function")
                            input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                                .put("name", function.getString("name")).put("arguments", function.getString("arguments")))
                        }
                    }
                }
                "tool" -> input.put(JSONObject().put("type", "function_call_output")
                    .put("call_id", message.getString("tool_call_id")).put("output", message.getString("content")))
                else -> invalid("responses_invalid_input")
            }
        }
        val convertedTools = JSONArray()
        repeat(tools.length()) { index ->
            val tool = tools.getJSONObject(index)
            if (tool.getString("type") != "function") invalid("responses_unsupported_tool")
            val function = tool.getJSONObject("function")
            convertedTools.put(JSONObject().put("type", "function").put("name", function.getString("name"))
                .put("description", function.optString("description")).put("parameters", function.getJSONObject("parameters"))
                // Responses otherwise normalizes optional parameters into strict, required ones.
                .put("strict", if (function.has("strict")) function.getBoolean("strict") else false))
        }
        return JSONObject().put("model", config.model).put("input", input).put("stream", true).put("store", false)
            .put("include", JSONArray().put("reasoning.encrypted_content")).apply {
                if (convertedTools.length() > 0) put("tools", convertedTools)
                if (forceTool != null) {
                    if ((0 until convertedTools.length()).none { convertedTools.getJSONObject(it).getString("name") == forceTool })
                        invalid("responses_invalid_tool_choice")
                    put("tool_choice", JSONObject().put("type", "function").put("name", forceTool))
                }
                parameters.maxTokens?.let { put("max_output_tokens", it) }
                parameters.temperature?.let { put("temperature", it) }
                parameters.topP?.let { put("top_p", it) }
                val reasoning = JSONObject()
                parameters.reasoningEffort?.let { reasoning.put("effort", it.wire) }
                if (parameters.thinking == AiThinking.Enabled) reasoning.put("summary", "auto")
                if (parameters.thinking == AiThinking.Disabled) reasoning.put("effort", "none")
                if (reasoning.length() > 0) put("reasoning", reasoning)
            }
    }

    /** Explicit non-streaming replies share every terminal validation with SSE. */
    fun completed(response: JSONObject, stream: AiResponsesStream = AiResponsesStream()): AiResponsesStream = stream.apply {
        val type = when {
            !response.isNull("error") || response.optString("status") == "failed" -> "response.failed"
            response.optString("status") == "incomplete" -> "response.incomplete"
            else -> "response.completed"
        }
        accept(JSONObject().put("type", type).put("response", response))
    }

    private fun content(value: Any, assistant: Boolean): JSONArray {
        if (value is String) return JSONArray().put(JSONObject().put("type", if (assistant) "output_text" else "input_text").put("text", value))
        val source = value as? JSONArray ?: invalid("responses_invalid_input")
        return JSONArray().apply {
            repeat(source.length()) { index ->
                val part = source.getJSONObject(index)
                when (part.getString("type")) {
                    "text" -> put(JSONObject().put("type", if (assistant) "output_text" else "input_text").put("text", part.getString("text")))
                    "image_url" -> {
                        if (assistant) invalid("responses_invalid_input")
                        val image = part.getJSONObject("image_url")
                        put(JSONObject().put("type", "input_image").put("image_url", image.getString("url")).apply {
                            image.optString("detail").takeIf { it.isNotEmpty() }?.let { put("detail", it) }
                        })
                    }
                    else -> invalid("responses_invalid_input")
                }
            }
        }
    }

    /** Never place opaque reasoning material in request logs, including nested replay items. */
    fun diagnostic(body: JSONObject): JSONObject {
        fun scrub(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { name ->
                put(name, when {
                    name == "encrypted_content" -> "[opaque reasoning omitted]"
                    name == "text" && value.optString("type") == "reasoning_text" -> "[reasoning text omitted]"
                    else -> scrub(value.get(name))
                })
            } }
            is JSONArray -> JSONArray().apply { repeat(value.length()) { put(scrub(value.get(it))) } }
            else -> value
        }
        return scrub(body) as JSONObject
    }

    internal fun validateOutputType(item: JSONObject) {
        if (item.optString("type") !in setOf("message", "reasoning", "function_call")) invalid("responses_unsupported_output_item")
        if (item.optString("type") == "message" && item.optString("role") != "assistant") invalid("responses_invalid_output_role")
    }
    private fun validateReplay(output: JSONArray) {
        if (output.length() > 64) invalid("responses_output_index_limit")
        val ids = hashSetOf<String>(); val callIds = hashSetOf<String>()
        var arguments = 0L; var text = 0L; var publicReasoning = 0L; var opaque = 0L
        repeat(output.length()) { index ->
            val item = output.getJSONObject(index)
            validateOutputType(item)
            val id = if (item.has("id")) item.getString("id")
                else if (item.optString("type") == "function_call") item.getString("call_id")
                else invalid("responses_item_identity_mismatch")
            if (id.isEmpty() || id.length > 200 || !ids.add(id)) invalid("responses_item_identity_mismatch")
            if (item.optString("status", "completed") != "completed") invalid("responses_incomplete_output_item")
            when (item.getString("type")) {
                "function_call" -> {
                    val callId = item.getString("call_id"); val name = item.getString("name")
                    if (callId.isEmpty() || callId.length > 200 || name.isEmpty() || name.length > 100 || !callIds.add(callId)) invalid("invalid_tool_identity")
                    val value = item.getString("arguments"); arguments += value.length
                    if (arguments > 8 * 1024 * 1024 || callIds.size > 8) invalid("responses_replay_limit")
                    JSONObject(value)
                }
                "message" -> {
                    val parts = item.getJSONArray("content")
                    if (parts.length() > 128) invalid("responses_content_limit")
                    repeat(parts.length()) { partIndex ->
                        val part = parts.getJSONObject(partIndex)
                        val type = part.getString("type")
                        if (type !in setOf("output_text", "refusal")) invalid("responses_unsupported_output_content")
                        text += part.getString(if (type == "refusal") "refusal" else "text").length
                    }
                }
                "reasoning" -> {
                    opaque += item.optString("encrypted_content").length
                    listOf("summary" to "summary_text", "content" to "reasoning_text").forEach { (key, type) ->
                        val parts = if (key == "summary" || !item.isNull(key)) item.getJSONArray(key) else JSONArray()
                        if (parts.length() > 128) invalid("responses_content_limit")
                        repeat(parts.length()) { partIndex ->
                            val part = parts.getJSONObject(partIndex)
                            if (part.getString("type") != type) invalid("responses_unsupported_output_content")
                            publicReasoning += part.getString("text").length
                        }
                    }
                }
            }
            if (text > AI_RESPONSE_TEXT_LIMIT || publicReasoning > AI_RESPONSE_TEXT_LIMIT || opaque > 8 * 1024 * 1024) invalid("responses_replay_limit")
        }
    }
    internal fun invalid(code: String): Nothing = throw AiTransportException(diagnostic = code)
}

/** One attempt only. No tool leaves this decoder until response.completed validates all output. */
internal class AiResponsesStream(private val delta: (String) -> Unit = {},
    private val reasoningDelta: (String) -> Unit = {}, private val toolDelta: (List<AiCallDraft>) -> Unit = {}) : AiProviderStream {
    private data class Item(var id: String, val type: String, var explicitId: Boolean = true, var opaqueChars: Int = 0,
        var callId: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())
    private val items = sortedMapOf<Int, Item>()
    private val texts = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    private val summaries = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    private val reasoningTexts = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    // Validate and replay both channels, display the first non-empty channel per item only.
    private val visibleReasoning = mutableMapOf<Int, String>()
    private var completed: JSONArray? = null
    override var done: Boolean = false; private set
    override var inputTokens: Long? = null; private set
    override var outputTokens: Long? = null; private set
    override var cachedTokens: Long? = null; private set
    override var finish: String = ""; private set
    override val text get() = texts.values.joinToString("")
    override val reasoning get() = items.keys.joinToString("") { index ->
        val map = if (visibleReasoning[index] == "content") reasoningTexts else summaries
        map.filterKeys { it.first == index }.values.joinToString("")
    }
    override val textChars get() = texts.values.sumOf { it.length }
    override val reasoningChars get() = summaries.values.sumOf { it.length } + reasoningTexts.values.sumOf { it.length }
    override val argumentChars get() = items.values.sumOf { it.arguments.length }
    val drafts get() = items.filterValues { it.type == "function_call" }.map { (index, item) ->
        liveCallDraft(index, item.name, item.arguments)
    }
    private fun invalid(code: String): Nothing = AiResponsesWire.invalid(code)
    private fun index(event: JSONObject): Int = event.getInt("output_index").also { if (it !in 0..63) invalid("responses_output_index_limit") }
    private fun item(event: JSONObject): Pair<Int, Item> {
        val suppliedIndex = if (event.has("output_index")) index(event) else null
        val itemId = if (event.has("item_id")) event.getString("item_id") else null
        val callId = if (event.has("call_id")) event.getString("call_id") else null
        if (suppliedIndex == null && itemId == null && callId == null) invalid("responses_unknown_output_item")
        val matches = items.filter { (index, item) ->
            (suppliedIndex == null || suppliedIndex == index) && (callId == null || callId == item.callId) &&
                (itemId == null || itemId == item.id || (!item.explicitId && (suppliedIndex != null || callId != null)))
        }
        if (matches.size != 1) invalid(if (matches.isEmpty() && suppliedIndex != null && items[suppliedIndex] == null)
            "responses_unknown_output_item" else "responses_item_identity_mismatch")
        val match = matches.entries.single()
        if (itemId != null && itemId != match.value.id) bindId(match.key, match.value, itemId)
        return match.key to match.value
    }
    private fun bindId(index: Int, item: Item, id: String) {
        if (id.isEmpty() || id.length > 200) invalid("invalid_tool_identity")
        if (items.any { it.key != index && it.value.id == id }) invalid("responses_duplicate_item_id")
        if (item.explicitId && item.id != id) invalid("responses_item_identity_mismatch")
        item.id = id; item.explicitId = true
    }
    private fun add(index: Int, value: JSONObject): Item {
        AiResponsesWire.validateOutputType(value)
        val type = value.getString("type")
        val explicitId = value.has("id")
        val id = if (explicitId) value.getString("id")
            else if (type == "function_call") value.getString("call_id")
            else invalid("invalid_tool_identity")
        if (id.isEmpty() || id.length > 200) invalid("invalid_tool_identity")
        if (items.any { it.key != index && it.value.id == id }) invalid("responses_duplicate_item_id")
        val item = items.getOrPut(index) { Item(id, type, explicitId) }
        if (item.type != type) invalid("responses_item_identity_mismatch")
        if (explicitId) bindId(index, item, id)
        if (type == "function_call") {
            if (items.values.count { it.type == "function_call" } > 8) throw AiTransportException(kind = AiFailure.ToolLimit)
            val callId = value.getString("call_id"); val name = value.getString("name")
            if (callId.isEmpty() || callId.length > 200 || name.isEmpty() || name.length > 100) invalid("invalid_tool_identity")
            if ((item.callId.isNotEmpty() && item.callId != callId) || (item.name.isNotEmpty() && item.name != name)) invalid("responses_item_identity_mismatch")
            if (items.any { it.key != index && it.value.callId == callId }) invalid("duplicate_tool_id")
            item.callId = callId; item.name = name
        }
        return item
    }

    private fun append(builder: StringBuilder, fragment: String, kind: String) {
        val available = when (kind) { "arguments" -> 8 * 1024 * 1024 - argumentChars; "reasoning" -> AI_RESPONSE_TEXT_LIMIT - reasoningChars; else -> AI_RESPONSE_TEXT_LIMIT - textChars }
        if (fragment.length > available) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "responses_${kind}_limit")
        builder.append(fragment)
        if (kind == "text" && fragment.isNotEmpty()) delta(fragment)
    }
    private fun reconcile(builder: StringBuilder, final: String, kind: String) {
        if (!final.startsWith(builder.toString())) invalid("responses_output_snapshot_mismatch")
        append(builder, final.substring(builder.length), kind)
    }
    private fun reconcileReasoning(index: Int, partIndex: Int, channel: String, value: String, complete: Boolean) {
        val map = if (channel == "content") reasoningTexts else summaries
        val builder = map.getOrPut(index to partIndex) { StringBuilder() }
        val before = builder.length
        if (complete) reconcile(builder, value, "reasoning") else append(builder, value, "reasoning")
        if (builder.length > before) {
            visibleReasoning.putIfAbsent(index, channel)
            if (visibleReasoning[index] == channel) reasoningDelta(builder.substring(before))
        }
    }

    private fun snapshot(index: Int, value: JSONObject) {
        val item = add(index, value)
        if (value.optString("status", "completed") !in setOf("completed", "in_progress")) invalid("responses_incomplete_output_item")
        when (item.type) {
            "message" -> {
                if (value.optString("role") != "assistant") invalid("responses_invalid_output_role")
                val parts = value.getJSONArray("content")
                if (parts.length() > 128) invalid("responses_content_limit")
                repeat(parts.length()) { partIndex ->
                    val part = parts.getJSONObject(partIndex)
                    val partType = part.getString("type")
                    if (partType !in setOf("output_text", "refusal")) invalid("responses_unsupported_output_content")
                    val builder = texts.getOrPut(index to partIndex) { StringBuilder() }
                    reconcile(builder, part.getString(if (partType == "refusal") "refusal" else "text"), "text")
                }
                if (texts.keys.any { it.first == index && it.second >= parts.length() }) invalid("responses_output_snapshot_mismatch")
            }
            "reasoning" -> {
                listOf("summary" to "summary_text", "content" to "reasoning_text").forEach { (channel, type) ->
                    val parts = if (channel == "summary" || !value.isNull(channel)) value.getJSONArray(channel) else JSONArray()
                    if (parts.length() > 128) invalid("responses_content_limit")
                    repeat(parts.length()) { partIndex ->
                        val part = parts.getJSONObject(partIndex)
                        if (part.getString("type") != type) invalid("responses_unsupported_output_content")
                        reconcileReasoning(index, partIndex, channel, part.getString("text"), true)
                    }
                    val map = if (channel == "content") reasoningTexts else summaries
                    if (map.keys.any { it.first == index && it.second >= parts.length() }) invalid("responses_output_snapshot_mismatch")
                }
                item.opaqueChars = value.optString("encrypted_content").length
                if (items.values.sumOf { it.opaqueChars.toLong() } > 8 * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "responses_opaque_limit")
            }
            "function_call" -> { reconcile(item.arguments, value.getString("arguments"), "arguments"); toolDelta(drafts) }
        }
    }

    internal fun readUsage(response: JSONObject) {
        response.optJSONObject("usage")?.let { usage ->
            fun number(value: JSONObject, name: String): Long? = try { AiStreamUsage.count(value, name) }
                catch (_: AiTransportException) { invalid("responses_invalid_usage") }
            inputTokens = number(usage, "input_tokens") ?: number(usage, "prompt_tokens") ?: inputTokens
            outputTokens = number(usage, "output_tokens") ?: number(usage, "completion_tokens") ?: outputTokens
            cachedTokens = usage.optJSONObject("input_tokens_details")?.let { number(it, "cached_tokens") }
                ?: usage.optJSONObject("prompt_tokens_details")?.let { number(it, "cached_tokens") }
                ?: number(usage, "cached_tokens") ?: number(usage, "prompt_cache_hit_tokens") ?: cachedTokens
        }
    }

    private val lifecycleNotifications = setOf("response.created", "response.in_progress", "response.queued")
    // Content is reconciled against the required terminal output snapshot, not these envelopes.
    private val structuralNotifications = setOf("response.content_part.added", "response.content_part.done",
        "response.reasoning_summary_part.added", "response.reasoning_summary_part.done")
    private val metadataNotifications = setOf("response.metadata", "response.output_text.annotation.added")

    override fun accept(event: JSONObject) {
        if (done) invalid("responses_event_after_completed")
        val type = event.getString("type")
        // Lifecycle and metadata are notifications only: neither can complete or supply output.
        // Keep a bounded whitelist rather than accepting arbitrary response.* extensions.
        if (type in lifecycleNotifications || type in structuralNotifications || type in metadataNotifications) return
        when (type) {
            "response.output_item.added" -> { val index = index(event); add(index, event.getJSONObject("item")); toolDelta(drafts) }
            "response.output_item.done" -> snapshot(index(event), event.getJSONObject("item"))
            "response.output_text.delta", "response.refusal.delta" -> {
                val (index, item) = item(event); if (item.type != "message") invalid("responses_item_type_mismatch")
                val partIndex = event.getInt("content_index"); if (partIndex !in 0..127) invalid("responses_content_limit")
                append(texts.getOrPut(index to partIndex) { StringBuilder() }, event.getString("delta"), "text")
            }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val (index, item) = item(event); if (item.type != "reasoning") invalid("responses_item_type_mismatch")
                val channel = if (type == "response.reasoning_text.delta") "content" else "summary"
                val partIndex = event.getInt(if (channel == "content") "content_index" else "summary_index")
                if (partIndex !in 0..127) invalid("responses_content_limit")
                reconcileReasoning(index, partIndex, channel, event.getString("delta"), false)
            }
            "response.function_call_arguments.delta" -> {
                val (_, item) = item(event); if (item.type != "function_call") invalid("responses_item_type_mismatch")
                append(item.arguments, event.getString("delta"), "arguments"); toolDelta(drafts)
            }
            "response.function_call_arguments.done" -> {
                val (_, item) = item(event); if (item.type != "function_call") invalid("responses_item_type_mismatch")
                reconcile(item.arguments, event.getString("arguments"), "arguments"); toolDelta(drafts)
            }
            "response.output_text.done", "response.refusal.done", "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                val (index, item) = item(event)
                val reasoning = type in setOf("response.reasoning_summary_text.done", "response.reasoning_text.done")
                if (item.type != if (reasoning) "reasoning" else "message") invalid("responses_item_type_mismatch")
                val partIndex = event.getInt(if (type == "response.reasoning_summary_text.done") "summary_index" else "content_index")
                if (partIndex !in 0..127) invalid("responses_content_limit")
                if (reasoning) reconcileReasoning(index, partIndex, if (type == "response.reasoning_text.done") "content" else "summary", event.getString("text"), true)
                else reconcile(texts.getOrPut(index to partIndex) { StringBuilder() }, event.getString(if (type == "response.refusal.done") "refusal" else "text"), "text")
            }
            "response.completed" -> {
                val response = event.getJSONObject("response")
                if (response.getString("status") != "completed" || !response.isNull("error")) invalid("responses_invalid_completion")
                val output = response.getJSONArray("output")
                if (output.length() > 64 || items.keys.any { it >= output.length() }) invalid("responses_output_snapshot_mismatch")
                repeat(output.length()) { index ->
                    val value = output.getJSONObject(index)
                    if (value.optString("status", "completed") != "completed") invalid("responses_incomplete_output_item")
                    snapshot(index, value)
                }
                if (items.values.filter { it.type == "function_call" }.map { it.callId }.distinct().size != items.values.count { it.type == "function_call" }) invalid("duplicate_tool_id")
                items.values.filter { it.type == "function_call" }.forEach { JSONObject(it.arguments.toString()) }
                readUsage(response)
                completed = JSONArray(output.toString()); done = true
                finish = if (items.values.any { it.type == "function_call" }) "tool_calls" else "stop"
            }
            "response.failed", "response.incomplete", "response.error", "error" -> {
                val response = event.optJSONObject("response")
                response?.let(::readUsage)
                val reason = response?.optJSONObject("incomplete_details")?.optString("reason")
                if (type == "response.incomplete" && reason == "max_output_tokens") throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
                val code = sequenceOf(response?.optJSONObject("error")?.optString("code"), event.optJSONObject("error")?.optString("code"), event.optString("code"))
                    .firstOrNull { !it.isNullOrEmpty() }.orEmpty()
                invalid(when {
                    code == "server_error" -> "responses_server_error"
                    code == "rate_limit_exceeded" -> "responses_rate_limit"
                    type == "response.incomplete" -> "responses_incomplete"
                    else -> "responses_failed"
                })
            }
            else -> invalid("responses_unsupported_event")
        }
    }
    override fun reply(): AiReply {
        if (!done) invalid("missing_done")
        val calls = items.values.filter { it.type == "function_call" }.map { AiCall(it.callId, it.name, it.arguments.toString()) }
        return AiReply(text, calls, reasoning.takeIf { it.isNotEmpty() }, requireNotNull(completed).toString())
    }
}

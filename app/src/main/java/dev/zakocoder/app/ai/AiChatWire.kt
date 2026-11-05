package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

internal object AiChatWire {
    /** Only an explicit JSON completion can finish without an SSE terminator. */
    fun complete(body: JSONObject, stream: AiChatStream = AiChatStream()): AiChatStream = stream.apply {
        if (body.has("error")) throw AiTransportException(diagnostic = "provider_error_event")
        val choices = body.getJSONArray("choices")
        if (choices.length() != 1) throw AiTransportException(diagnostic = "chat_invalid_choices")
        val choice = choices.getJSONObject(0)
        val message = JSONObject(choice.getJSONObject("message").toString())
        if (message.optString("role") != "assistant") throw AiTransportException(diagnostic = "chat_invalid_output_role")
        message.optJSONArray("tool_calls")?.let { calls ->
            for (index in 0 until calls.length()) calls.getJSONObject(index).put("index", index)
        }
        accept(JSONObject(body.toString()).put("choices", JSONArray().put(JSONObject(choice.toString()).put("delta", message))))
        markDone()
    }
}

/** One request attempt. Incremental tool drafts never imply permission to execute. */
internal class AiChatStream(private val delta: (String) -> Unit = {}, private val reasoningDelta: (String) -> Unit = {},
    private val toolDelta: (List<AiCallDraft>) -> Unit = {}) : AiProviderStream {
    private data class Tool(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private var sawReasoning = false
    private val calls = sortedMapOf<Int, Tool>()
    private val details = AiChatReasoning()
    override var done = false; private set
    override val text get() = textBuffer.toString()
    override val textChars get() = textBuffer.length
    override val reasoning get() = reasoningBuffer.toString()
    override val reasoningChars get() = reasoningBuffer.length
    override val argumentChars get() = calls.values.sumOf { it.arguments.length }
    override var inputTokens: Long? = null; private set
    override var outputTokens: Long? = null; private set
    override var cachedTokens: Long? = null; private set
    override var finish = ""; private set
    private fun invalid(code: String): Nothing = throw AiTransportException(diagnostic = code)
    private fun fragment(value: JSONObject, key: String): String {
        if (value.isNull(key)) return ""
        return (value.get(key) as? String) ?: invalid("chat_invalid_content")
    }
    private fun append(target: StringBuilder, fragment: String, code: String) {
        if (fragment.length > AI_RESPONSE_TEXT_LIMIT - target.length) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = code)
        target.append(fragment)
    }
    override fun accept(event: JSONObject) {
        if (done) invalid("chat_event_after_completed")
        if (event.has("error")) invalid("provider_error_event")
        event.optJSONObject("usage")?.let { usage ->
            inputTokens = AiStreamUsage.count(usage, "prompt_tokens") ?: inputTokens
            outputTokens = AiStreamUsage.count(usage, "completion_tokens") ?: outputTokens
            cachedTokens = usage.optJSONObject("prompt_tokens_details")?.let { AiStreamUsage.count(it, "cached_tokens") }
                ?: AiStreamUsage.count(usage, "cached_tokens") ?: AiStreamUsage.count(usage, "prompt_cache_hit_tokens") ?: cachedTokens
        }
        val choices = event.optJSONArray("choices") ?: return
        if (choices.length() > 1) invalid("chat_invalid_choices")
        val choice = choices.optJSONObject(0) ?: return
        if (!choice.isNull("finish_reason")) {
            val next = choice.get("finish_reason") as? String ?: invalid("unexpected_finish_reason")
            if (finish.isNotEmpty() && finish != next) invalid("unexpected_finish_reason")
            finish = next
        }
        val part = choice.optJSONObject("delta") ?: return
        val reasoningKey = if (!part.isNull("reasoning_content")) "reasoning_content" else "reasoning"
        if (!part.isNull(reasoningKey)) {
            sawReasoning = true
            val value = fragment(part, reasoningKey)
            append(reasoningBuffer, value, "response_reasoning_limit")
            if (value.isNotEmpty()) reasoningDelta(value)
        }
        if (!part.isNull("reasoning_details")) details.accept(part.getJSONArray("reasoning_details"))
        val content = fragment(part, "content")
        append(textBuffer, content, "response_text_limit")
        if (content.isNotEmpty()) delta(content)
        part.optJSONArray("tool_calls")?.let { tools ->
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                val rawIndex = tool.get("index")
                val position = when (rawIndex) { is Int -> rawIndex; is Long -> if (rawIndex in 0..7) rawIndex.toInt() else -1; else -> -1 }
                if (position !in 0..7) throw AiTransportException(kind = AiFailure.ToolLimit)
                if (!tool.isNull("type") && tool.getString("type") != "function") invalid("chat_unsupported_tool")
                val target = calls.getOrPut(position) { Tool() }
                target.id.append(fragment(tool, "id"))
                tool.optJSONObject("function")?.let { function ->
                    target.name.append(fragment(function, "name"))
                    val arguments = fragment(function, "arguments")
                    if (arguments.length > 8 * 1024 * 1024 - argumentChars) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "tool_arguments_limit")
                    target.arguments.append(arguments)
                }
                if (target.id.length > 200 || target.name.length > 100) invalid("invalid_tool_identity")
            }
            toolDelta(calls.map { (index, tool) -> liveCallDraft(index, tool.name.toString(), tool.arguments) })
        }
    }
    fun markDone() {
        if (finish == "length") throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
        if ((calls.isEmpty() && finish != "stop") || (calls.isNotEmpty() && finish != "tool_calls")) invalid("unexpected_finish_reason")
        if (calls.values.map { it.id.toString() }.distinct().size != calls.size) invalid("duplicate_tool_id")
        calls.values.forEach {
            if (it.id.isEmpty() || it.name.isEmpty()) invalid("incomplete_tool_identity")
            try { JSONObject(it.arguments.toString()) } catch (_: org.json.JSONException) { invalid("invalid_tool_arguments") }
        }
        done = true
    }
    override fun reply(): AiReply {
        if (!done) invalid("missing_done")
        return AiReply(text, calls.values.map { AiCall(it.id.toString(), it.name.toString(), it.arguments.toString()) },
            reasoningContent = reasoning.takeIf { sawReasoning }, chatReasoningDetails = details.snapshot().takeIf { it.length() > 0 }?.toString())
    }
}

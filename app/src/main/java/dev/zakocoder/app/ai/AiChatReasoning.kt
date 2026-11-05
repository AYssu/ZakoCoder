package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

internal const val CHAT_REASONING_DETAILS_KEY = "zako_chat_reasoning_details"

/** Opaque provider context: retained for replay, never converted into commands or displayed logs. */
internal class AiChatReasoning {
    private class Detail(val index: Int) {
        val fixed = linkedMapOf<String, String>()
        val fragments = linkedMapOf<String, StringBuilder>()
        val nullFields = linkedSetOf<String>()
    }
    private val details = sortedMapOf<Int, Detail>()
    // Includes field names and values. JSON escaping is checked once when producing the replay.
    private var retainedChars = 0
    private fun reserve(count: Int) {
        if (count > MAX_CHARS - retainedChars) invalid()
        retainedChars += count
    }
    fun accept(array: JSONArray) {
        if (array.length() > MAX_DETAILS) invalid()
        for (position in 0 until array.length()) {
            val incoming = array.getJSONObject(position)
            val index = if (incoming.has("index")) strictIndex(incoming.get("index")) else position
            val detail = details.getOrPut(index) { reserve("index".length + index.toString().length); Detail(index) }
            val type = if (incoming.has("type")) (incoming.get("type") as? String)?.takeIf(String::isNotBlank) ?: invalid()
                else detail.fixed["type"] ?: invalid()
            if (detail.fixed["type"]?.let { it != type } == true) invalid()
            val keys = incoming.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = incoming.get(key)
                if (key == "index") { strictIndex(value); continue }
                if (key.length > MAX_CHARS) invalid()
                if (value == JSONObject.NULL) {
                    if (!nullable(type, key)) invalid()
                    if (key !in detail.fixed && key !in detail.fragments && detail.nullFields.add(key)) reserve(key.length + 4)
                    continue
                }
                if (value !is String || value.length > MAX_CHARS) invalid()
                detail.nullFields.remove(key)
                if (key in FRAGMENTS) {
                    val builder = detail.fragments[key]
                    if (builder == null) reserve(key.length)
                    reserve(value.length)
                    (builder ?: StringBuilder().also { detail.fragments[key] = it }).append(value)
                } else {
                    val previous = detail.fixed[key]
                    if (previous != null && previous != value) invalid()
                    if (previous == null) { reserve(key.length); reserve(value.length); detail.fixed[key] = value }
                }
            }
            if (detail.fixed["type"].isNullOrBlank()) invalid()
        }
    }
    fun snapshot(): JSONArray = JSONArray().apply {
        details.values.forEach { detail ->
            put(JSONObject().put("index", detail.index).apply {
                detail.nullFields.forEach { put(it, JSONObject.NULL) }
                detail.fixed.forEach { (key, value) -> put(key, value) }
                detail.fragments.forEach { (key, value) -> put(key, value.toString()) }
            })
        }
        validateReplay(this)
    }

    companion object {
        private const val MAX_DETAILS = 128
        private const val MAX_CHARS = 1024 * 1024
        private val FRAGMENTS = setOf("text", "summary", "data", "signature")
        private fun nullable(type: String, key: String): Boolean = when (type) {
            "reasoning.text" -> key in setOf("format", "id", "signature", "text")
            "reasoning.summary", "reasoning.encrypted" -> key in setOf("format", "id")
            else -> false
        }
        private fun invalid(): Nothing = throw AiTransportException(diagnostic = "chat_invalid_reasoning_details")
        private fun strictIndex(raw: Any): Int {
            val index = when (raw) { is Int -> raw; is Long -> if (raw in 0..127) raw.toInt() else invalid(); else -> invalid() }
            if (index !in 0 until MAX_DETAILS) invalid()
            return index
        }
        fun validateReplay(array: JSONArray) {
            if (array.length() > MAX_DETAILS || array.toString().length > MAX_CHARS) invalid()
            val indexes = mutableSetOf<Int>()
            for (position in 0 until array.length()) {
                val value = array.getJSONObject(position)
                val index = if (value.has("index")) strictIndex(value.get("index")) else position
                if (!indexes.add(index) || value.opt("type") !is String || value.getString("type").isBlank()) invalid()
                val type = value.getString("type")
                if (type == "reasoning.summary" && value.opt("summary") !is String) invalid()
                if (type == "reasoning.encrypted" && value.opt("data") !is String) invalid()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "index") continue
                    val field = value.get(key)
                    if (field !is String && !(field == JSONObject.NULL && nullable(type, key))) invalid()
                }
            }
        }
        fun diagnostic(body: JSONObject): JSONObject = scrub(body) as JSONObject
        private fun scrub(value: Any): Any = when (value) {
            is JSONObject -> JSONObject().apply {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, if (key == "reasoning_details" || key == CHAT_REASONING_DETAILS_KEY) "[opaque reasoning omitted]" else scrub(value.get(key)))
                }
            }
            is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(scrub(value.get(index))) }
            else -> value
        }
    }
}

package dev.zakocoder.app.ai

import org.json.JSONObject

/** Provider counts are optional integers, never local estimates or coercions of invalid fields. */
internal object AiStreamUsage {
    fun count(value: JSONObject, name: String): Long? {
        if (value.isNull(name)) return null
        return when (val raw = value.get(name)) {
            is Byte -> raw.toLong()
            is Short -> raw.toLong()
            is Int -> raw.toLong()
            is Long -> raw
            else -> null
        }?.takeIf { it >= 0 } ?: throw AiTransportException(diagnostic = "invalid_usage")
    }
}

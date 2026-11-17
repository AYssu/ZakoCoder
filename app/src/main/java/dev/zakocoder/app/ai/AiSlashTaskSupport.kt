package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** A task snapshot only: the persistent global selection is never written here. */
internal fun explicitTaskSkills(enabled: List<String>, available: List<AiSkill>, explicit: String?): List<String> {
    if (explicit == null) return enabled
    require(available.any { it.path == explicit && it.valid })
    val retained = if (explicit in AiBuiltinSkills.expressionStyles) enabled.filterNot { it in AiBuiltinSkills.expressionStyles } else enabled
    return (retained + explicit).distinct().also { require(it.size <= AiSkills.MAX_ENABLED) }
}

/** Full display history remains independent of the compacted model context. */
internal fun manualContextMessages(state: AiState): JSONArray = JSONArray().apply {
    if (state.contextSummary.isNotBlank()) put(contextMemory(state.contextSummary))
    state.contextTurns.forEach { encoded ->
        val messages = JSONArray(encoded)
        repeat(messages.length()) { index ->
            val message = messages.getJSONObject(index)
            if (!message.optBoolean("zako_memory_snapshot") && !message.optString("content").startsWith("User-selected skills for this task.")) put(message)
        }
    }
    state.recoveryNotes.forEach { put(JSONObject().put("role", "user").put("content", it)) }
}

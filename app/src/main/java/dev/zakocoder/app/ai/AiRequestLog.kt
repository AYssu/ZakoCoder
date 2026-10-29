package dev.zakocoder.app.ai

import dev.zakocoder.app.R
import org.json.JSONArray
import org.json.JSONObject

/** Only stable, allow-listed diagnostics enter history. Never retain Throwable, headers or provider error bodies. */
enum class AiFailure(val label: Int) {
    Compaction(R.string.ai_failure_compaction), Skill(R.string.ai_failure_skill), Http(R.string.ai_failure_http), IdleTimeout(R.string.ai_failure_idle), Network(R.string.ai_failure_network),
    Protocol(R.string.ai_failure_protocol), OutputLimit(R.string.ai_failure_output), ContextLimit(R.string.ai_failure_context),
    RoundLimit(R.string.ai_failure_round), ToolLimit(R.string.ai_failure_tools), TaskTimeout(R.string.ai_failure_task),
    Cancelled(R.string.ai_failure_cancelled), Interrupted(R.string.ai_failure_interrupted), Internal(R.string.ai_failure_internal)
}
data class AiStreamPoint(val ms: Long, val events: Int, val chars: Int, val bytes: Long)
data class AiRequestLog(
    val id: String = java.util.UUID.randomUUID().toString(), val turn: Int = 0, val round: Int = 0,
    val started: Long = System.currentTimeMillis(), val elapsed: Long = 0, val firstEvent: Long? = null, val firstText: Long? = null,
    val status: Int? = null, val model: String = "", val origin: String = "", val contextChars: Int = 0,
    val requestBytes: Int = 0, val events: Int = 0, val textChars: Int = 0, val argumentChars: Int = 0, val bytes: Long = 0,
    val inputTokens: Long? = null, val outputTokens: Long? = null, val cachedTokens: Long? = null,
    val finish: String = "", val diagnostic: String = "", val failure: AiFailure? = null, val complete: Boolean = false,
    val compaction: Boolean = false, val request: String = "", val response: String = "", val truncated: Boolean = false, val points: List<AiStreamPoint> = emptyList()
) {
    fun encode(): JSONObject = JSONObject().put("id", id).put("turn", turn).put("round", round).put("started", started)
        .put("elapsed", elapsed).put("firstEvent", firstEvent).put("firstText", firstText).put("status", status)
        .put("model", model).put("origin", origin).put("contextChars", contextChars).put("requestBytes", requestBytes)
        .put("events", events).put("textChars", textChars).put("argumentChars", argumentChars).put("bytes", bytes)
        .put("inputTokens", inputTokens).put("outputTokens", outputTokens).put("cachedTokens", cachedTokens)
        .put("finish", finish).put("diagnostic", diagnostic).put("failure", failure?.name).put("complete", complete).put("compaction", compaction).put("request", request)
        .put("response", response).put("truncated", truncated)
        .put("points", JSONArray(points.map { JSONArray(listOf(it.ms, it.events, it.chars, it.bytes)) }))
    companion object {
        fun decode(j: JSONObject): AiRequestLog {
            fun long(name: String) = if (j.isNull(name)) null else j.getLong(name)
            val points = j.getJSONArray("points"); require(points.length() <= 256)
            val request = j.getString("request"); val response = j.getString("response")
            require(request.length <= 240000 && response.length <= 16000)
            return AiRequestLog(j.getString("id"), j.getInt("turn"), j.getInt("round"), j.getLong("started"),
                j.getLong("elapsed"), long("firstEvent"), long("firstText"), long("status")?.toInt(),
                j.getString("model").take(200), j.getString("origin").take(300), j.getInt("contextChars"), j.getInt("requestBytes"),
                j.getInt("events"), j.getInt("textChars"), j.getInt("argumentChars"), j.getLong("bytes"),
                long("inputTokens"), long("outputTokens"), long("cachedTokens"), j.getString("finish").take(64), j.optString("diagnostic").take(100),
                if (j.isNull("failure")) null else AiFailure.valueOf(j.getString("failure")), j.getBoolean("complete"),
                j.optBoolean("compaction"), request, response, j.getBoolean("truncated"), (0 until points.length()).map { n -> points.getJSONArray(n).let {
                    AiStreamPoint(it.getLong(0), it.getInt(1), it.getInt(2), it.getLong(3))
                } })
        }
    }
}

/** Redact the active credential even if echoed by a service or accidentally present in a prompt. */
class AiLogRedactor(private val key: String) {
    fun clean(value: String): String {
        val withoutKey = if (key.isNotEmpty()) value.replace(key, "[REDACTED]") else value
        return withoutKey.replace(bearer, "$1[REDACTED]").replace(secretShape, "[REDACTED]")
    }
    override fun toString() = "AiLogRedactor(<redacted>)"
    companion object {
        private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
        private val secretShape = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")
    }
}
fun boundedRequests(values: List<AiRequestLog>): List<AiRequestLog> {
    val result = values.takeLast(32).toMutableList()
    while (result.size > 1 && result.sumOf { it.request.length + it.response.length } > 768000) result.removeAt(0)
    return result
}

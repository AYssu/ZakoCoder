package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Public clarification, separate from tool authorization. Never stores credentials or grants. */
data class AiUserQuestion(val id: String, val eventId: String, val prompt: String, val options: List<String>,
    val turn: Int, val answer: String? = null, val cancelled: Boolean = false) {
    fun validate() {
        require(UUID.fromString(id).toString() == id && UUID.fromString(eventId).toString() == eventId)
        require(prompt.isNotBlank() && prompt.length <= 2000 && turn in 1..1000000)
        require(options.size <= 6 && options.distinct().size == options.size && options.all { it.isNotBlank() && it.length <= 200 })
        require(answer == null || (answer.isNotBlank() && answer.length <= MAX_ANSWER))
    }
    internal fun encode(): JSONObject = JSONObject().put("id", id).put("event", eventId).put("prompt", prompt)
        .put("options", JSONArray(options)).put("turn", turn).put("answer", answer).put("cancelled", cancelled).also { validate() }
    companion object {
        const val MAX_ANSWER = 4096
        internal fun fromArguments(event: AiToolEvent, args: JSONObject): AiUserQuestion {
            require(args.get("question") is String)
            val array = if (args.has("options")) args.getJSONArray("options") else JSONArray()
            require(array.length() <= 6)
            return AiUserQuestion(UUID.randomUUID().toString(), event.id, args.getString("question").trim(),
                (0 until array.length()).map { require(array.get(it) is String); array.getString(it).trim() }, event.turn).also { it.validate() }
        }
        internal fun decode(json: JSONObject) = AiUserQuestion(json.getString("id"), json.getString("event"), json.getString("prompt"),
            json.getJSONArray("options").let { a -> require(a.length() <= 6); (0 until a.length()).map(a::getString) },
            json.getInt("turn"), if (json.isNull("answer")) null else json.getString("answer"), json.getBoolean("cancelled")).also { it.validate() }
    }
}

/** Exact canonical transcript ending at a sole unanswered ask_user call. No processes resume from disk.
 * System settings/temporary grants are rebuilt; screenshot envelopes never enter this snapshot.
 */
data class AiQuestionContinuation(val questionId: String, val callId: String, val messages: String, val turnStart: Int) {
    internal fun validate(question: AiUserQuestion) {
        require(question.id == questionId && !question.cancelled && callId.isNotBlank() && callId.length <= 200)
        require(messages.length <= AI_RETAINED_CONTEXT_CHARS)
        val array = JSONArray(messages)
        require(array.length() in 2..AI_RETAINED_TURN_MESSAGES && turnStart in 0 until array.length() - 1)
        require(array.getJSONObject(turnStart).getString("role") == "user")
        val pending = mutableSetOf<String>()
        repeat(array.length()) { index ->
            val message = array.getJSONObject(index)
            when (message.getString("role")) {
                "user" -> require(pending.isEmpty())
                "assistant" -> {
                    require(pending.isEmpty())
                    message.optJSONArray("tool_calls")?.let { calls ->
                        require(calls.length() in 1..8)
                        repeat(calls.length()) { require(pending.add(calls.getJSONObject(it).getString("id"))) }
                    }
                }
                "tool" -> require(pending.remove(message.getString("tool_call_id")))
                else -> error("Unsupported continuation role")
            }
        }
        require(pending == setOf(callId))
        val last = array.getJSONObject(array.length() - 1)
        require(last.getString("role") == "assistant")
        val calls = last.getJSONArray("tool_calls")
        require(calls.length() == 1 && calls.getJSONObject(0).getString("id") == callId)
        val function = calls.getJSONObject(0).getJSONObject("function")
        require(function.getString("name") == AiTool.AskUser.wire)
        val args = JSONObject(function.getString("arguments"))
        val parsed = AiUserQuestion.fromArguments(AiToolEvent(question.eventId, callId, AiTool.AskUser, "ask_user", "", "", "", turn = question.turn), args)
        require(parsed.prompt == question.prompt && parsed.options == question.options)
    }
    internal fun encode(): JSONObject = JSONObject().put("question", questionId).put("call", callId)
        .put("messages", JSONArray(messages)).put("turnStart", turnStart)
    companion object {
        internal fun decode(json: JSONObject) = AiQuestionContinuation(json.getString("question"), json.getString("call"),
            json.getJSONArray("messages").toString(), json.getInt("turnStart"))
    }
}
internal class AiQuestionPaused : Exception()

package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AiQueuedKind { Steer, FollowUp }
enum class AiQueuedStatus { Pending, Applied, Cancelled }
/** Durable user intent. It grants no permissions and never contains an executable tool call. */
data class AiQueuedMessage(val id: String = UUID.randomUUID().toString(), val text: String,
    val kind: AiQueuedKind, val turn: Int, val created: Long = System.currentTimeMillis(),
    val status: AiQueuedStatus = AiQueuedStatus.Pending) {
    fun validate() { require(id.length in 1..80 && text.isNotBlank() && text.length <= 16000 && turn >= 0 && created >= 0) }
}
object AiTaskQueue {
    const val MAX_PENDING = 16
    fun add(queue: List<AiQueuedMessage>, message: AiQueuedMessage): List<AiQueuedMessage> {
        message.validate()
        require(queue.count { it.status == AiQueuedStatus.Pending } < MAX_PENDING)
        require(queue.none { it.id == message.id })
        return (queue.filter { it.status == AiQueuedStatus.Pending } + queue.filter { it.status != AiQueuedStatus.Pending }.takeLast(47) + message).sortedBy { it.created }
    }
    fun encode(queue: List<AiQueuedMessage>): JSONArray {
        require(queue.size <= 64 && queue.map { it.id }.distinct().size == queue.size)
        require(queue.count { it.status == AiQueuedStatus.Pending } <= MAX_PENDING)
        return JSONArray(queue.map { q -> q.validate(); JSONObject().put("id", q.id).put("text", q.text)
            .put("kind", q.kind.name).put("turn", q.turn).put("created", q.created).put("status", q.status.name) })
    }
    fun decode(array: JSONArray?): List<AiQueuedMessage> {
        if (array == null) return emptyList()
        require(array.length() <= 64)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { q ->
            AiQueuedMessage(q.getString("id"), q.getString("text"), AiQueuedKind.valueOf(q.getString("kind")),
                q.getInt("turn"), q.getLong("created"), AiQueuedStatus.valueOf(q.getString("status"))).also { it.validate() }
        } }.also { encode(it) }
    }
}

internal class AiSteeringBoundary : IllegalStateException()

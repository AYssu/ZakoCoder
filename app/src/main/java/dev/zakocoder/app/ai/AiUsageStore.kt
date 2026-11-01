package dev.zakocoder.app.ai

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Independent rolling ledger; conversation deletion and the 32-request diagnostic cap do not erase it. */
internal class AiUsageStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ai-usage-v1.json"))
    private val mutex = Mutex()
    private var snapshot: AiUsageState? = null
    suspend fun load(): AiUsageState = mutate { it }
    suspend fun record(request: AiUsageRequest): AiUsageState = record(listOf(request), emptyList())
    suspend fun record(tool: AiUsageTool): AiUsageState = record(emptyList(), listOf(tool))
    suspend fun record(requests: List<AiUsageRequest>, tools: List<AiUsageTool>, incomplete: Boolean = false): AiUsageState = mutate { state ->
        val requestMap = state.requests.associateBy { Triple(it.projectId, it.conversationId, it.id) }.toMutableMap()
        requests.forEach { request ->
            val key = Triple(request.projectId, request.conversationId, request.id)
            if (requestMap[key]?.complete != true || request.complete) requestMap[key] = request
        }
        val toolMap = state.tools.associateBy { Triple(it.projectId, it.conversationId, it.id) }.toMutableMap()
        tools.forEach { tool ->
            val key = Triple(tool.projectId, tool.conversationId, tool.id)
            toolMap[key] = tool.copy(started = toolMap[key]?.started ?: tool.started)
        }
        state.copy(requests = requestMap.values.toList(), tools = toolMap.values.toList(), incomplete = state.incomplete || incomplete)
    }
    private suspend fun mutate(update: (AiUsageState) -> AiUsageState): AiUsageState = withContext(Dispatchers.IO) { mutex.withLock {
        val original = read(); val updated = update(original)
        val cutoff = (clock() - RETENTION_MILLIS).coerceAtLeast(0)
        val requests = updated.requests.filter { it.started >= cutoff }.sortedBy { it.started }.takeLast(MAX_RECORDS)
        val tools = updated.tools.filter { it.started >= cutoff }.sortedBy { it.started }.takeLast(MAX_RECORDS)
        val trimmed = requests.size < updated.requests.size || tools.size < updated.tools.size
        val next = updated.copy(loading = false, failed = false, requests = requests, tools = tools,
            truncated = updated.truncated || trimmed, retainedFrom = if (trimmed) maxOf(updated.retainedFrom ?: updated.startedAt,
                cutoff, requests.firstOrNull()?.started ?: 0, tools.firstOrNull()?.started ?: 0) else updated.retainedFrom)
        write(next); snapshot = next; next
    } }
    private fun read(): AiUsageState {
        snapshot?.let { return it }
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val initial = AiUsageState(loading = false, startedAt = clock())
            write(initial); snapshot = initial; return initial
        }
        require(file.baseFile.length() <= MAX_BYTES)
        val root = JSONObject(file.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_BYTES); output.write(buffer, 0, count) }
            output.toString(Charsets.UTF_8.name())
        })
        require(root.getInt("version") == 1)
        val requests = root.getJSONArray("requests"); val tools = root.getJSONArray("tools")
        require(requests.length() <= MAX_RECORDS && tools.length() <= MAX_RECORDS)
        fun JSONObject.text(name: String): String = getString(name).also { require(it.length <= 200) }
        fun JSONObject.tokens(name: String): Long? = if (isNull(name)) null else getLong(name).also { require(it >= 0) }
        return AiUsageState(loading = false, startedAt = root.getLong("startedAt"), retainedFrom = root.tokens("retainedFrom"),
            truncated = root.optBoolean("truncated"), incomplete = root.optBoolean("incomplete"), requests = (0 until requests.length()).map { index -> requests.getJSONObject(index).let {
                AiUsageRequest(it.text("id"), it.text("project"), it.text("conversation"), it.text("model"), it.getLong("started"),
                    it.getBoolean("complete"), it.getBoolean("failed"), it.tokens("input"), it.tokens("output"), it.tokens("cached"),
                    it.getLong("context"), it.getInt("window"), it.getBoolean("compaction")) } },
            tools = (0 until tools.length()).map { index -> tools.getJSONObject(index).let {
                AiUsageTool(it.text("id"), it.text("project"), it.text("conversation"), it.text("model"), it.getLong("started"),
                    it.text("status"), it.getBoolean("executed")) } }).also { snapshot = it }
    }
    private fun write(state: AiUsageState) {
        val root = JSONObject().put("version", 1).put("startedAt", state.startedAt).put("retainedFrom", state.retainedFrom)
            .put("truncated", state.truncated).put("incomplete", state.incomplete).put("requests", JSONArray(state.requests.map { request -> JSONObject()
                .put("id", request.id).put("project", request.projectId).put("conversation", request.conversationId).put("model", request.model)
                .put("started", request.started).put("complete", request.complete).put("failed", request.failed)
                .put("input", request.inputTokens).put("output", request.outputTokens).put("cached", request.cachedTokens)
                .put("context", request.estimatedContextTokens).put("window", request.contextWindowTokens).put("compaction", request.compaction) }))
            .put("tools", JSONArray(state.tools.map { tool -> JSONObject().put("id", tool.id).put("project", tool.projectId)
                .put("conversation", tool.conversationId).put("model", tool.model).put("started", tool.started)
                .put("status", tool.status).put("executed", tool.executed) }))
        val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object {
        private const val MAX_RECORDS = 10000
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val RETENTION_MILLIS = 730L * 24 * 60 * 60 * 1000
    }
}

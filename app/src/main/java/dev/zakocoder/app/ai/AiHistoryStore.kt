package dev.zakocoder.app.ai

import android.content.Context
import android.util.AtomicFile
import dev.zakocoder.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Blocking storage boundary. Call only on IO; no provider settings or authorization grants enter this store.
 * Operations are replaceable for isolated storage faults; the owning controller serializes access.
 */
open class AiHistoryStore(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "ai-history-v1"))
    internal val checkpointDirectory get() = File(directory.parentFile, directory.name + "-checkpoints")
    private val metadata = mutableMapOf<String, List<AiConversation>>()
    data class Loaded(val state: AiState?, val conversations: List<AiConversation>)
    private fun project(id: String): File {
        require(id.length in 1..200)
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, hash)
    }
    private fun session(id: String): String { require(UUID.fromString(id).toString() == id); return "$id.json" }
    private fun read(file: File): JSONObject {
        val bytes = AtomicFile(file).openRead().use { input -> java.io.ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); require(output.size() <= 8 * 1024 * 1024) }
            output.toByteArray()
        } }
        require(bytes.size <= 8 * 1024 * 1024)
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("schemaVersion") == 1) }
    }
    private fun write(file: File, json: JSONObject) {
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bytes = json.toString().toByteArray(); require(bytes.size <= 8 * 1024 * 1024)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun records(id: String): List<Pair<File, JSONObject>> {
        val files = project(id).listFiles()?.mapNotNull { file ->
            val name = file.name.removeSuffix(".bak")
            if (name != "selected.json" && name.endsWith(".json")) File(file.parentFile, name) else null
        }?.distinctBy { it.name }.orEmpty()
        require(files.size <= 20)
        return files.map { file -> session(file.nameWithoutExtension); file to read(file).also { require(it.getString("id") == file.nameWithoutExtension) } }
    }
    private fun summaries(records: List<Pair<File, JSONObject>>) = records.map { (_, item) ->
        AiConversation(item.getString("id"), item.getString("title").take(80), item.getLong("updated"), item.getInt("turn"))
    }.sortedByDescending { it.updated }
    open fun load(projectId: String, conversation: String? = null): Loaded {
        val records = records(projectId)
        val pointer = File(project(projectId), "selected.json")
        val selected = conversation ?: if (pointer.exists() || File(pointer.path + ".bak").exists()) read(pointer).getString("id") else null
        val json = records.find { it.second.getString("id") == selected }?.second ?: records.maxByOrNull { it.second.getLong("updated") }?.second
        val list = summaries(records)
        metadata[projectId] = list
        return Loaded(json?.let(::decode), list)
    }
    open fun save(projectId: String, state: AiState): List<AiConversation> {
        val records = metadata[projectId] ?: summaries(records(projectId))
        require(records.size < 20 || records.any { it.id == state.conversationId })
        val json = encode(state)
        write(File(project(projectId), session(state.conversationId)), json)
        write(File(project(projectId), "selected.json"), JSONObject().put("schemaVersion", 1).put("id", state.conversationId))
        return (records.filterNot { it.id == state.conversationId } + AiConversation(state.conversationId, json.getString("title"), json.getLong("updated"), state.turn))
            .sortedByDescending { it.updated }.also { metadata[projectId] = it }
    }
    open fun select(projectId: String, conversation: String) {
        val file = File(project(projectId), session(conversation))
        require(file.exists() || File(file.path + ".bak").exists())
        write(File(project(projectId), "selected.json"), JSONObject().put("schemaVersion", 1).put("id", conversation))
    }
    open fun delete(projectId: String, conversation: String) {
        AtomicFile(File(project(projectId), session(conversation))).delete()
        metadata.remove(projectId)
    }
    open fun deleteProject(projectId: String) { metadata.remove(projectId); check(!project(projectId).exists() || project(projectId).deleteRecursively()) }
    private val sources = listOf(R.string.ai_permission_default_rule, R.string.ai_permission_tool_rule,
        R.string.ai_permission_task_rule, R.string.ai_permission_rejected_rule, R.string.ai_permission_once_rule)
    private fun encode(state: AiState): JSONObject = JSONObject().put("schemaVersion", 1).put("id", state.conversationId)
        .put("title", (state.conversations.firstOrNull { it.id == state.conversationId }?.title?.takeIf { it.isNotBlank() } ?: state.timeline.firstOrNull { it.role == AiRole.User }?.text)?.replace('\n', ' ')?.take(80).orEmpty())
        .put("checkpointId", state.checkpointId).put("updated", System.currentTimeMillis()).put("turn", state.turn).put("active", state.active)
        .put("turnTimings", JSONArray(state.turnTimings.map { JSONObject().put("turn", it.turn).put("started", it.started).put("finished", it.finished) }))
        .put("phase", state.phase.name).put("trimmed", state.historyTrimmed)
        .put("plan", JSONArray(state.plan.map { JSONObject().put("title", it.title).put("status", it.status) }))
        .put("timeline", JSONArray(state.timeline.map { JSONObject().put("id", it.id).put("text", it.text).put("tool", it.toolId)
            .put("role", it.role.name).put("turn", it.turn) }))
        .put("events", JSONArray(state.events.map { e -> JSONObject().put("id", e.id).put("call", e.callId).put("name", e.name)
            .put("input", e.input).put("reason", e.reason).put("target", e.target).put("status", e.status.name)
            .put("turn", e.turn).put("permission", e.permission.name).put("source", sources.indexOf(e.permissionSource))
            .put("preview", e.preview).put("output", e.output).put("truncated", e.truncated).put("exit", e.exitCode)
            .put("duration", if (e.started != null && e.finished != null) (e.finished - e.started).coerceAtLeast(0) else JSONObject.NULL) }))
        .put("context", JSONArray(state.contextTurns.map { JSONArray(it) }))
        .put("commandChanges", JSONArray(state.commandChanges.map { it.encode() })).put("commandReviewLimited", state.commandReviewLimited)
        .put("requests", JSONArray(state.requests.map { it.encode() })).put("recovery", JSONArray(state.recoveryNotes))
        .put("skillsInitialized", state.skillsInitialized)
        .put("contextSummary", state.contextSummary).put("contextCompactions", state.contextCompactions).put("contextChars", state.contextChars)
        .put("taskRecovery", state.taskRecovery).put("taskPrompt", state.taskPrompt).put("enabledSkills", JSONArray(state.enabledSkills))
        .put("taskDiagnostic", state.taskDiagnostic.take(2048)).put("error", state.error.take(500)).put("failureKind", state.failureKind?.name)
    private fun decode(json: JSONObject): AiState {
        // Additive v1 field: old records contain no plan; old readers ignore this field.
        val planArray = json.optJSONArray("plan") ?: JSONArray()
        require(planArray.length() <= 10)
        val plan = (0 until planArray.length()).map { index ->
            val step = planArray.getJSONObject(index)
            val title = step.getString("title"); val status = step.getString("status")
            require(title.isNotBlank() && title.length <= 200 && status in setOf("pending", "in_progress", "completed"))
            AiPlanStep(title, status)
        }
        require(plan.count { it.status == "in_progress" } <= 1)
        val entries = json.getJSONArray("timeline"); require(entries.length() <= 512)
        val timeline = (0 until entries.length()).map { index -> entries.getJSONObject(index).let {
            val role = AiRole.valueOf(it.getString("role"))
            AiEntry(it.getString("id").take(100), it.getString("text").also { text ->
                require(text.length <= if (role == AiRole.Assistant) AI_RESPONSE_TEXT_LIMIT else 64000)
            }, if (it.isNull("tool")) null else it.getString("tool").take(100), role, it.getInt("turn"))
        } }
        require(timeline.sumOf { it.text.length } <= 512000 && timeline.map { it.id }.distinct().size == timeline.size)
        require(json.getInt("turn") in 0..1000000)
        val events = json.getJSONArray("events"); require(events.length() <= 64)
        val restored = (0 until events.length()).map { index -> events.getJSONObject(index).let { e ->
            val status = AiToolStatus.valueOf(e.getString("status"))
            AiToolEvent(e.getString("id"), e.getString("call"), AiTool.named(e.getString("name")), e.getString("name"),
                e.getString("input"), e.getString("reason"), e.getString("target"),
                if (status.terminal) status else AiToolStatus.Incomplete, AiPermission.valueOf(e.getString("permission")),
                sources.getOrElse(e.getInt("source")) { R.string.ai_permission_default_rule }, e.getString("preview"), e.getString("output"),
                e.getBoolean("truncated"), if (e.isNull("exit")) null else e.getInt("exit"),
                if (e.isNull("duration")) null else 0L, if (e.isNull("duration")) null else e.getLong("duration"), e.optInt("turn", timeline.find { it.toolId == e.getString("id") }?.turn ?: 0))
        } }
        require(restored.map { it.id }.distinct().size == restored.size)
        require(restored.sumOf { it.input.length + it.preview.length + it.output.length + it.reason.length + it.target.length } <= 512000)
        val context = json.getJSONArray("context"); require(context.length() <= AI_RETAINED_CONTEXT_TURNS)
        val turns = (0 until context.length()).map { index ->
            val turn = context.getJSONArray(index)
            require(turn.length() in 2..AI_RETAINED_TURN_MESSAGES && turn.getJSONObject(0).getString("role") == "user")
            val pending = mutableSetOf<String>()
            var seenAssistant = false
            for (messageIndex in 1 until turn.length()) {
                val message = turn.getJSONObject(messageIndex)
                when (message.getString("role")) {
                    "user" -> require(!seenAssistant && pending.isEmpty())
                    "assistant" -> {
                        seenAssistant = true
                        require(pending.isEmpty())
                        val calls = message.optJSONArray("tool_calls")
                        if (calls == null || calls.length() == 0) require(messageIndex == turn.length() - 1)
                        else {
                            require(calls.length() <= 8 && messageIndex < turn.length() - 1)
                            repeat(calls.length()) { call -> require(pending.add(calls.getJSONObject(call).getString("id"))) }
                        }
                    }
                    "tool" -> require(pending.remove(message.getString("tool_call_id")))
                    else -> error("Invalid conversation role")
                }
            }
            require(pending.isEmpty() && turn.getJSONObject(turn.length() - 1).getString("role") == "assistant")
            turn.toString()
        }; require(turns.sumOf { it.length } <= AI_RETAINED_CONTEXT_CHARS)
        val requestArray = json.optJSONArray("requests") ?: JSONArray(); require(requestArray.length() <= 32)
        val requests = (0 until requestArray.length()).map { index -> AiRequestLog.decode(requestArray.getJSONObject(index)).let {
            if (!it.complete) it.copy(complete = true, failure = AiFailure.Interrupted) else it
        } }
        require(requests.sumOf { it.request.length + it.response.length } <= 768000)
        val recoveryArray = json.optJSONArray("recovery") ?: JSONArray(); require(recoveryArray.length() <= 4)
        val recovery = (0 until recoveryArray.length()).map { recoveryArray.getString(it).also { text -> require(text.length <= 24000) } }
        require(recovery.sumOf(String::length) <= 48000)
        val pendingRecovery = json.optString("taskRecovery"); require(pendingRecovery.length <= 24000)
        val taskPrompt = json.optString("taskPrompt"); require(taskPrompt.length <= 16000)
        val skillArray = json.optJSONArray("enabledSkills") ?: JSONArray(); require(skillArray.length() <= AiSkills.MAX_ENABLED)
        val enabledSkills = (0 until skillArray.length()).map { skillArray.getString(it).also { path ->
            require((AiBuiltinSkills.contains(path) || Regex("\\.zakocoder/skills/[^/\\\\]+/SKILL\\.md").matches(path)) && path.length <= 300)
        } }
        require(enabledSkills.distinct().size == enabledSkills.size && enabledSkills.count { it in AiBuiltinSkills.expressionStyles } <= 1)
        val changeArray = json.optJSONArray("commandChanges") ?: JSONArray(); require(changeArray.length() <= 32)
        val commandChanges = (0 until changeArray.length()).map { AiCommandChange.decode(changeArray.getJSONObject(it)) }
        require(commandChanges.sumOf { (it.before?.length ?: 0) + (it.after?.length ?: 0) } <= 512000)
        val summary = json.optString("contextSummary"); require(summary.length <= 12000)
        val compactions = json.optInt("contextCompactions"); require(compactions >= 0)
        val contextChars = json.optInt("contextChars"); require(contextChars >= 0)
        val checkpointId = json.optString("checkpointId").takeIf { it.isNotEmpty() && it != "null" }?.also { require(UUID.fromString(it).toString() == it) }
        val timingArray = json.optJSONArray("turnTimings") ?: JSONArray()
        require(timingArray.length() <= 512)
        val timings = (0 until timingArray.length()).map { index -> timingArray.getJSONObject(index).let {
            AiTurnTiming(it.getInt("turn"), it.getLong("started"), if (it.isNull("finished")) null else it.getLong("finished"))
        } }
        val state = AiState(turnTimings = timings, checkpointId = checkpointId, conversationId = json.getString("id"), turn = json.getInt("turn"), timeline = timeline, events = restored, plan = plan,
            contextSummary = summary, contextCompactions = compactions, contextChars = contextChars,
            messages = timeline.filter { it.toolId == null }.takeLast(100).map { it.text }, contextTurns = turns,
            commandChanges = commandChanges, commandReviewLimited = json.optBoolean("commandReviewLimited"),
            requests = requests, recoveryNotes = recovery, taskPrompt = taskPrompt, enabledSkills = enabledSkills, skillsInitialized = json.optBoolean("skillsInitialized", enabledSkills.isNotEmpty()), error = json.optString("error").take(500),
            taskDiagnostic = json.optString("taskDiagnostic").take(2048),
            failureKind = json.optString("failureKind").takeIf { it.isNotBlank() && it != "null" }?.let(AiFailure::valueOf),
            historyTrimmed = json.optBoolean("trimmed"), interrupted = json.getBoolean("active"),
            phase = if (json.getBoolean("active")) AiPhase.Cancelled else AiPhase.valueOf(json.getString("phase")))
        val interrupted = json.getBoolean("active")
        val legacyFailed = !json.has("recovery") && state.phase == AiPhase.Failed
        return if (pendingRecovery.isNotBlank() || interrupted || legacyFailed) state.copy(recoveryNotes = boundedRecovery(recovery + pendingRecovery.ifBlank { recoveryNote(state, AiFailure.Interrupted) })) else state
    }
}

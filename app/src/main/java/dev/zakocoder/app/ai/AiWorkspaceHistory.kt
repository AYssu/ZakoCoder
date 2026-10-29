package dev.zakocoder.app.ai

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AiRestoration(val id: String, val checkpoint: String, val conversation: String, val turn: Int,
    val started: Long, val finished: Long = 0, val status: String = "pending", val confirmed: Int = 0,
    val total: Int = 0, val paths: List<String> = emptyList(), val pendingPath: String = "", val pendingBefore: String = "", val pendingAfter: String = "") {
    /** Actual old exits remain evidence of execution, not verification of a restored workspace. */
    fun invalidates(startedAt: Long) = startedAt <= (if (finished > 0) finished else started)
}

/** Project-level restoration facts survive conversation switches and checkpoint retention. Blocking IO. */
internal class AiWorkspaceHistory(directory: File) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(directory, "workspace-events-v1.json"))
    private fun encode(e: AiRestoration) = JSONObject().put("id", e.id).put("checkpoint", e.checkpoint)
        .put("conversation", e.conversation).put("turn", e.turn).put("started", e.started).put("finished", e.finished)
        .put("status", e.status).put("confirmed", e.confirmed).put("total", e.total).put("paths", JSONArray(e.paths)).put("pendingPath", e.pendingPath).put("pendingBefore", e.pendingBefore).put("pendingAfter", e.pendingAfter)
    fun records(): List<AiRestoration> = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val json = file.openRead().use { input -> require(input.available() <= 512000); JSONObject(input.bufferedReader().readText()) }
        require(json.getInt("version") == 1)
        val array = json.getJSONArray("events"); require(array.length() <= 16)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let { e ->
            val paths = e.getJSONArray("paths"); require(paths.length() <= 20)
            AiRestoration(e.getString("id"), e.getString("checkpoint"), e.getString("conversation"), e.getInt("turn"),
                e.getLong("started"), e.getLong("finished"), e.getString("status"), e.getInt("confirmed"), e.getInt("total"),
                (0 until paths.length()).map(paths::getString), e.optString("pendingPath"), e.optString("pendingBefore"), e.optString("pendingAfter"))
        } }
    }
    fun save(event: AiRestoration): Unit = synchronized(lock) {
        val events = (listOf(event) + records().filterNot { it.id == event.id }).take(16)
        val bytes = JSONObject().put("version", 1).put("events", JSONArray(events.map(::encode))).toString().toByteArray()
        require(bytes.size <= 512000)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun begin(checkpoint: String, conversation: String, turn: Int, total: Int, alreadyRestored: Int) =
        AiRestoration(UUID.randomUUID().toString(), checkpoint, conversation, turn, System.currentTimeMillis(), confirmed = alreadyRestored, total = total).also(::save)

    fun observeLegacy(checkpoints: List<AiCheckpoint>): Unit = synchronized(lock) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) return
        // Old manifests prove restoration occurred, but cannot prove its exact date/order.
        checkpoints.filter { it.restored || it.restoredCount > 0 }.take(16).reversed().forEach { cp ->
            save(AiRestoration(UUID.randomUUID().toString(), cp.id, cp.conversation, cp.turn, System.currentTimeMillis(),
                status = "legacy", confirmed = cp.restoredCount, total = cp.changed))
        }
    }
    fun context(records: List<AiRestoration>): String {
        if (records.isEmpty()) return ""
        val events = JSONArray(records.take(8).map { encode(it).put("pendingPath", it.pendingPath.take(160)).put("paths", JSONArray(it.paths.take(6).map { path -> path.take(160) })) })
        val result = JSONObject().put("kind", "workspace_restoration_facts").put("revision", records.first().id)
            .put("latest_restoration_boundary", records.maxOf { if (it.finished > 0) it.finished else it.started })
            .put("notice", "Actual file restoration facts across all project conversations. Prior edits and verification may describe an older workspace. Inspect current files and re-run relevant checks before claiming they still pass. Do not replay installs, network operations or previous side effects. Pending/partial operations may have changed some files. Legacy times are observation times, not the original restoration date. Events and paths are bounded samples; no file contents are included.")
            .put("events", events)
        while (result.toString().length > 12000 && events.length() > 1) events.remove(events.length() - 1)
        return result.toString()
    }
}

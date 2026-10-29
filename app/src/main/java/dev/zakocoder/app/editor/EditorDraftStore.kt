package dev.zakocoder.app.editor

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class EditorDraft(val baseline: FileBaseline, val text: String, val line: Int, val column: Int)
/** Private draft snapshots, never source-file autosave. Single atomic index prevents half-updated tabs. */
internal class EditorDraftStore(private val directory: File) {
    private val file get() = AtomicFile(File(directory, "drafts-v1.json"))
    fun load(): List<EditorDraft> {
        if (!file.baseFile.exists()) return emptyList()
        val json = file.openRead().use { require(it.available() <= 32 * 1024 * 1024); JSONObject(it.bufferedReader().readText()) }
        val a = json.getJSONArray("drafts"); require(a.length() <= 8)
        return (0 until a.length()).map { i ->
            val d = a.getJSONObject(i)
            val text = d.getString("text"); val old = d.getString("baseline")
            for (value in listOf(text, old)) {
                require(value.toByteArray().size <= EditorFileStore.MAX_BYTES && '\u0000' !in value)
                require(value.lines().size <= EditorFileStore.MAX_LINES && value.lineSequence().all { it.length <= EditorFileStore.MAX_COLUMN })
            }
            require(Regex("[a-f0-9]{64}").matches(d.getString("digest")))
            EditorDraft(FileBaseline(d.getString("project"), d.getString("path"), old, d.getString("digest"), d.getBoolean("bom")), text, d.getInt("line").coerceAtLeast(0), d.getInt("column").coerceAtLeast(0))
        }
    }
    fun save(drafts: List<EditorDraft>) {
        require(drafts.size <= 8)
        drafts.forEach { d -> require(d.text.toByteArray().size <= EditorFileStore.MAX_BYTES && d.text.lines().size <= EditorFileStore.MAX_LINES && d.text.lineSequence().all { it.length <= EditorFileStore.MAX_COLUMN }) }
        directory.mkdirs()
        val json = JSONObject().put("drafts", JSONArray(drafts.map { d -> JSONObject().put("project", d.baseline.projectId).put("path", d.baseline.path).put("baseline", d.baseline.text)
            .put("digest", d.baseline.digest).put("bom", d.baseline.bom).put("text", d.text).put("line", d.line).put("column", d.column) }))
        require(json.toString().toByteArray().size <= 32 * 1024 * 1024)
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
}

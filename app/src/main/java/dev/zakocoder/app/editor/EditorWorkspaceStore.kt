package dev.zakocoder.app.editor

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal data class EditorLocation(val path: String, val line: Int = 0, val column: Int = 0)

/** Only document locations; source text and unsaved recovery stay in their existing stores. */
internal class EditorWorkspaceStore(directory: File) {
    private val file = AtomicFile(File(directory, "locations-v1.json"))
    fun load(): Map<String, EditorLocation> {
        if (!file.baseFile.exists()) return emptyMap()
        val json = file.openRead().use { require(it.available() <= 1024 * 1024); JSONObject(it.bufferedReader().readText()) }
        return json.keys().asSequence().associateWith { id ->
            val entry = json.getJSONObject(id)
            EditorLocation(entry.getString("path"), entry.optInt("line").coerceAtLeast(0), entry.optInt("column").coerceAtLeast(0))
        }
    }
    fun save(locations: Map<String, EditorLocation>) {
        file.baseFile.parentFile!!.mkdirs()
        val json = JSONObject()
        locations.forEach { (id, location) -> json.put(id, JSONObject().put("path", location.path).put("line", location.line).put("column", location.column)) }
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
}

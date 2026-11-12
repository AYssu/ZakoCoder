package dev.zakocoder.app.ai

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Installation-wide selection. Workspace history is never read as configuration. Blocking IO. */
internal class AiGlobalSkills(context: Context) {
    data class Selection(val enabled: List<String>, val onDemand: Boolean = false)
    private val file = AtomicFile(File(context.filesDir, "shared-skills-selection.json"))
    private val defaults = { dev.zakocoder.app.settings.OnboardingRepository(context).defaultSkills() }

    fun load(): Selection = synchronized(lock) {
        val bytes = try { file.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 16000)
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        } }
        catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return@synchronized Selection(defaults())
        }
        require(bytes.size <= 16000)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.get("version") is Int && json.getInt("version") == 1)
        val paths = json.getJSONArray("enabled")
        require(paths.length() <= AiSkills.MAX_ENABLED && json.get("onDemand") is Boolean)
        require((0 until paths.length()).all { paths.get(it) is String })
        Selection(List(paths.length()) { paths.getString(it) }, json.getBoolean("onDemand")).also(::validate)
    }

    fun save(selection: Selection) = synchronized(lock) {
        validate(selection)
        val bytes = JSONObject().put("version", 1).put("enabled", JSONArray(selection.enabled))
            .put("onDemand", selection.onDemand).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    private fun validate(selection: Selection) {
        require(selection.enabled.size <= AiSkills.MAX_ENABLED && selection.enabled.distinct().size == selection.enabled.size)
        require(selection.enabled.count { it in AiBuiltinSkills.expressionStyles } <= 1)
        require(selection.enabled.all { path -> path.length <= 300 && (AiBuiltinSkills.contains(path) ||
            Regex("\\.zakocoder/skills/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?/SKILL\\.md").matches(path)) })
    }
    private companion object { val lock = Any() }
}

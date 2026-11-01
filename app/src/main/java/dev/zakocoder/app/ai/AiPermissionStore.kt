package dev.zakocoder.app.ai

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Only explicit user choices are persisted, never prompts, files or secrets. Blocking IO. */
class AiPermissionStore(context: Context, name: String = "ai-permissions-v1") {
    private val file = AtomicFile(File(context.filesDir, "$name.json"))
    private fun read(): JSONObject = if (!file.baseFile.exists()) JSONObject() else {
        require(file.baseFile.length() <= 1024 * 1024)
        JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }
    @Synchronized fun load(id: String): AiPolicy {
        val stored = read()
        if (!stored.has(id)) return AiPolicy()
        val json = stored.getJSONObject(id)
        val overrides = json.getJSONObject("tools")
        return AiPolicy(AiPermission.valueOf(json.getString("default")), AiTool.entries.mapNotNull { tool ->
            overrides.optString(tool.wire).takeIf { it.isNotEmpty() }?.let { tool to AiPermission.valueOf(it) }
        }.toMap(), yolo = json.opt("yolo") == true)
    }
    @Synchronized fun save(id: String, policy: AiPolicy) {
        require(id.isNotEmpty() && id.length <= 100)
        val json = read()
        val tools = JSONObject(); policy.overrides.forEach { (tool, permission) -> tools.put(tool.wire, permission.name) }
        json.put(id, JSONObject().put("default", policy.default.name).put("tools", tools).put("yolo", policy.yolo))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

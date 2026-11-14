package dev.zakocoder.app.ai

import org.json.JSONObject
import java.net.URI

/** A conservative app approval aid, not an OS sandbox or proof that arbitrary code is safe. */
internal enum class AiPermissionRisk { Read, WorkspaceWrite, NetworkRead, Unknown }

internal object AiPermissionRisks {
    fun classify(tool: AiTool, args: JSONObject): AiPermissionRisk = classify(tool,
        action = args.optString("action"), command = args.optString("command"), url = args.optString("url"))

    /** Pure fields keep risk decisions testable without Android's JSONObject implementation. */
    internal fun classify(tool: AiTool, action: String = "", command: String = "", url: String = ""): AiPermissionRisk = when (tool) {
        AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.LoadSkill,
        AiTool.ListTerminals, AiTool.SearchMemory, AiTool.AskUser, AiTool.SetTaskMode,
        AiTool.UpdatePlan, AiTool.DelegateReadOnly -> AiPermissionRisk.Read
        // The controller resolves these paths without following links before admission.
        AiTool.WriteFile, AiTool.EditFile, AiTool.MakeDirectory -> AiPermissionRisk.WorkspaceWrite
        AiTool.RunCommand -> if (readCommand(command)) AiPermissionRisk.Read else AiPermissionRisk.Unknown
        AiTool.Browser -> when (action) {
            "status", "read", "find", "scroll", "wait", "screenshot" -> AiPermissionRisk.Read
            "navigate" -> navigation(url)
            else -> AiPermissionRisk.Unknown
        }
        // Terminal input, project scripts, MCP and remote/device actions can have arbitrary side effects.
        else -> AiPermissionRisk.Unknown
    }

    private fun navigation(url: String): AiPermissionRisk {
        val uri = try { URI(url) } catch (_: Exception) { return AiPermissionRisk.Unknown }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null)
            return AiPermissionRisk.Unknown
        return if (uri.host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1"))
            AiPermissionRisk.WorkspaceWrite else AiPermissionRisk.NetworkRead
    }

    /**
     * Only shell builtins: LinuxRuntime uses env -i and bash --noprofile --norc for commands,
     * so user profiles, exported functions and aliases are absent. External tools remain unknown:
     * the user's additional PATH is configurable. Never extend this list to project scripts.
     */
    internal fun readCommand(command: String): Boolean {
        val words = literalWords(command) ?: return false
        val name = words.firstOrNull() ?: return false
        val args = words.drop(1)
        return when (name) {
            "pwd" -> args.isEmpty() || args == listOf("-P") || args == listOf("-L")
            "true", "false" -> args.isEmpty()
            "echo" -> true
            "printf" -> args.isNotEmpty() && !args.first().startsWith('-')
            else -> false
        }
    }

    private fun literalWords(command: String): List<String>? {
        if (command.isBlank() || command.length > 18000 || command.any { it.isISOControl() || it in "$`\\;|&<>(){}[]*?!~" }) return null
        val words = mutableListOf<String>()
        var quote: Char? = null
        var started = false
        val word = StringBuilder()
        for (char in command) {
            when {
                quote != null -> if (char == quote) quote = null else word.append(char)
                char == '\'' || char == '"' -> { quote = char; started = true }
                char == ' ' -> if (started) { words += word.toString(); word.setLength(0); started = false }
                else -> { word.append(char); started = true }
            }
        }
        if (quote != null) return null
        if (started) words += word.toString()
        return words
    }
}

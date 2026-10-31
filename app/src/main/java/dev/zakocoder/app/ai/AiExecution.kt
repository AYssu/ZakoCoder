package dev.zakocoder.app.ai

import dev.zakocoder.app.R

/** Stable native tool catalog, separate from vendor wire syntax. */
enum class AiTool(val wire: String, val label: Int, val description: Int, val writes: Boolean = false) {
    ListFiles("list_files", R.string.ai_tool_list, R.string.ai_tool_list_desc),
    ReadFile("read_file", R.string.ai_tool_read, R.string.ai_tool_read_desc),
    SearchFiles("search_files", R.string.ai_tool_search, R.string.ai_tool_search_desc),
    WriteFile("write_file", R.string.ai_tool_write, R.string.ai_tool_write_desc, true),
    MakeDirectory("make_directory", R.string.ai_tool_directory, R.string.ai_tool_directory_desc, true),
    RunCommand("run_command", R.string.ai_tool_run, R.string.ai_tool_run_desc, true),
    ReadCommandLog("read_command_log", R.string.ai_command_log_read, R.string.ai_command_log_read_desc),
    ListTerminals("list_terminals", R.string.ai_terminal_list, R.string.ai_terminal_list_desc),
    ReadTerminal("read_terminal", R.string.ai_terminal_read, R.string.ai_terminal_read_desc),
    StartTerminal("start_terminal", R.string.ai_terminal_start, R.string.ai_terminal_start_desc, true),
    SendTerminalInput("send_terminal_input", R.string.ai_terminal_input, R.string.ai_terminal_input_desc, true),
    CloseTerminal("close_terminal", R.string.ai_terminal_close, R.string.ai_terminal_close_desc, true),
    SshRemote("ssh_remote", R.string.ai_ssh_tool, R.string.ai_ssh_tool_desc, true),
    AndroidTest("android_test", R.string.ai_screen_test_tool, R.string.ai_screen_test_tool_desc, true),
    UseMcp("use_mcp", R.string.ai_tool_mcp, R.string.ai_tool_mcp_desc, true),
    UpdatePlan("update_plan", R.string.ai_tool_plan, R.string.ai_tool_plan_desc);
    companion object { fun named(name: String) = entries.singleOrNull { it.wire == name } }
}
enum class AiPermission(val label: Int) { Allow(R.string.ai_permission_allow), Ask(R.string.ai_permission_ask), Deny(R.string.ai_permission_deny) }
data class AiPolicy(val default: AiPermission = AiPermission.Ask, val overrides: Map<AiTool, AiPermission> = AiTool.entries.filterNot { it.writes || it == AiTool.ReadTerminal || it == AiTool.ReadCommandLog }.associateWith { AiPermission.Allow }) {
    // Explicit advanced rules are authoritative here. Capability, target lease and system
    // authorization remain independent checks at execution; presets still default to Ask.
    fun permission(tool: AiTool): AiPermission = overrides[tool] ?: default
    fun source(tool: AiTool) = if (tool in overrides) R.string.ai_permission_tool_rule else R.string.ai_permission_default_rule
}
enum class AiToolStatus(val label: Int) {
    Queued(R.string.ai_status_queued), Waiting(R.string.ai_status_waiting), Running(R.string.ai_status_running),
    Succeeded(R.string.ai_status_succeeded), Failed(R.string.ai_status_failed), Denied(R.string.ai_status_denied), Cancelled(R.string.ai_status_cancelled), Incomplete(R.string.ai_status_incomplete);
    val terminal get() = this !in setOf(Queued, Waiting, Running)
}
enum class AiPhase(val label: Int) { Compacting(R.string.ai_phase_compacting), Idle(R.string.ai_phase_idle), Requesting(R.string.ai_phase_requesting), Receiving(R.string.ai_phase_receiving),
    Waiting(R.string.ai_status_waiting), Executing(R.string.ai_phase_executing), Stopping(R.string.ai_phase_stopping), Completed(R.string.ai_phase_completed), Failed(R.string.ai_status_failed), Cancelled(R.string.ai_status_cancelled) }
enum class AiDecision { Once, TaskTarget, TaskTool, Reject }
data class AiToolEvent(val id: String, val callId: String, val tool: AiTool?, val name: String, val input: String, val reason: String,
    val target: String, val status: AiToolStatus = AiToolStatus.Queued, val permission: AiPermission = AiPermission.Ask,
    val permissionSource: Int = R.string.ai_permission_default_rule, val preview: String = "", val output: String = "", val truncated: Boolean = false,
    val exitCode: Int? = null, val started: Long? = null, val finished: Long? = null, val turn: Int = 0)
enum class AiRole { User, Assistant, Notice }
internal const val AI_REASONING_DISPLAY_LIMIT = 64000

data class AiEntry(val id: String, val text: String = "", val toolId: String? = null, val role: AiRole = AiRole.Notice, val turn: Int = 0,
    val reasoning: String = "", val reasoningTruncated: Boolean = false, val images: List<AiImageAttachment> = emptyList())
data class AiConversation(val id: String, val title: String, val updated: Long, val turns: Int)
data class AiPlanStep(val title: String, val status: String)
data class AiCallDraft(val index: Int, val name: String, val arguments: String)

/** User-facing failures are constructed exclusively from local string resources. */
class AiTerminalException(message: String) : IllegalStateException(message)

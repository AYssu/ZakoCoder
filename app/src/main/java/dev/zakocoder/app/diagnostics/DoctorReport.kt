package dev.zakocoder.app.diagnostics

import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.mcp.McpConnectionStatus
import dev.zakocoder.app.mcp.McpState
import dev.zakocoder.app.ssh.SshState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Export projections are deliberately independent of history/config encoders: no free text escapes. */
internal object DoctorReport {
    fun ai(state: AiState, now: Long): JSONObject = JSONObject()
        .put("workspace", identity(state.projectId)).put("conversation", identity(state.conversationId))
        .put("active", state.active).put("phase", state.phase.name).put("turn", state.turn)
        .put("round", state.round).put("toolCount", state.toolCount).put("configured", state.configured)
        .put("failure", state.failureKind?.name).put("historyError", state.historyError)
        .put("stopCode", taskStopDiagnosticCode(state.taskDiagnostic)?.name)
        .put("taskUsage", JSONObject().put("requests", state.taskUsage.requests).put("tools", state.taskUsage.tools)
            .put("tokens", state.taskUsage.tokens).put("estimatedRequests", state.taskUsage.estimatedRequests)
            .put("elapsedMs", state.taskUsage.elapsedMs))
        .put("historyProblem", state.historyProblem?.reason?.name).put("interrupted", state.interrupted)
        .put("restoring", state.restoring).put("waitingApproval", state.approval != null)
        .put("waitingAnswer", state.questionContinuation != null)
        .put("pendingMessages", state.queuedMessages.size).put("skillCount", state.enabledSkills.size)
        .put("skillsLoading", state.skillsLoading).put("skillError", state.skillError)
        .put("permissionsLoading", state.permissionsLoading).put("permissionsSaving", state.permissionsSaving)
        .put("context", JSONObject().put("estimatedTokens", state.estimatedContextTokens)
            .put("windowTokens", state.contextWindowTokens).put("outputReserveTokens", state.contextOutputReserveTokens)
            .put("approximate", state.contextEstimateApproximate).put("compactions", state.contextCompactions))
        .put("requests", JSONArray(state.requests.takeLast(32).map { request(it, now) }))
        .put("tools", JSONArray(state.events.takeLast(128).map { event ->
            JSONObject().put("id", identity(event.id)).put("tool", event.tool?.name ?: "External")
                .put("status", event.status.name).put("permission", event.permission.name)
                .put("turn", event.turn).put("started", event.started).put("finished", event.finished)
                .put("exitCode", event.exitCode).put("truncated", event.truncated)
        }))

    private fun request(value: AiRequestLog, now: Long): JSONObject = JSONObject()
        .put("id", identity(value.id)).put("turn", value.turn).put("round", value.round)
        .put("protocol", AiApiProtocol.entries.firstOrNull { it.name == value.apiProtocol }?.name ?: "Unknown")
        .put("started", value.started).put("elapsedMs", value.elapsed)
        .put("firstEventMs", value.firstEvent).put("firstTextMs", value.firstText)
        .put("ageMs", (now - value.started).coerceAtLeast(0))
        .put("httpStatus", value.status).put("complete", value.complete).put("failure", value.failure?.name)
        .put("attempt", value.attempt).put("maxAttempts", value.maxAttempts).put("retryDelayMs", value.retryDelayMs)
        .put("compaction", value.compaction).put("events", value.events).put("receivedBytes", value.bytes)
        .put("requestBytes", value.requestBytes).put("textChars", value.textChars).put("argumentChars", value.argumentChars)
        .put("inputTokens", value.inputTokens).put("outputTokens", value.outputTokens).put("cachedTokens", value.cachedTokens)
        .put("estimatedContextTokens", value.estimatedContextTokens).put("contextWindowTokens", value.contextWindowTokens)
        .put("points", JSONArray(value.points.takeLast(64).map {
            JSONObject().put("ms", it.ms).put("events", it.events).put("chars", it.chars).put("bytes", it.bytes)
        }))

    fun components(workbench: WorkbenchState, mcp: McpState, ssh: SshState): JSONObject = JSONObject()
        .put("workspaceLoading", workbench.loading).put("workspaceCount", workbench.projects.size)
        .put("environmentInstalled", workbench.installed).put("environmentBusy", workbench.busy)
        .put("backgroundServiceRunning", workbench.backgroundServiceRunning)
        .put("backgroundServiceError", workbench.backgroundServiceError)
        .put("terminalCount", workbench.sessions.size).put("toolsChecking", workbench.checkingTools)
        .put("toolCheckFailed", workbench.toolCheckError.isNotEmpty())
        .put("toolStatuses", JSONObject(workbench.tools.groupingBy { it.status.name }.eachCount()))
        .put("proxyConfigured", workbench.settings.proxy.isNotEmpty())
        .put("mcp", JSONObject().put("servers", mcp.servers.size).put("enabled", mcp.servers.count { it.enabled })
            .put("loading", mcp.loading).put("busy", mcp.busy).put("hasError", mcp.error.isNotEmpty())
            .put("ready", mcp.connections.values.count { it.status == McpConnectionStatus.Ready })
            .put("failed", mcp.connections.values.count { it.status == McpConnectionStatus.Failed }))
        .put("ssh", JSONObject().put("profiles", ssh.profiles.size).put("busy", ssh.busy)
            .put("awaitingTrust", ssh.pendingHostKey != null).put("hasError", !ssh.error.isNullOrEmpty()))

    /** Correlate local records without exposing project names, paths or opaque external identities. */
    private fun identity(value: String?): String? = value?.takeIf { it.length in 1..128 }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(8).joinToString("") { b -> "%02x".format(b) }
    }
}

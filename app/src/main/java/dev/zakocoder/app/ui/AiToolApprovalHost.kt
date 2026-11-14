package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiDecision
import dev.zakocoder.app.ai.AiReview
import dev.zakocoder.app.ai.AiState

/** Foreground modal host when the retained chat page is intentionally noninteractive. */
@Composable
internal fun AiToolApprovalHost(workbench: Workbench, state: AiState) {
    val approval = state.approval ?: return
    var inspecting by remember(state.projectId, state.conversationId, approval) { mutableStateOf<AiReview?>(null) }
    var permissions by remember(state.projectId, state.conversationId, approval) { mutableStateOf(false) }
    val event = state.events.find { it.id == approval.eventId }
    // A stale dialog must never complete a decision belonging to another task or conversation.
    fun current() = workbench.ai.state.value.let {
        it.projectId == state.projectId && it.conversationId == state.conversationId && it.approval == approval
    }
    fun decide(decision: AiDecision) { if (current()) workbench.ai.approve(decision) }
    val normal = MaterialTheme.colorScheme.onSurface
    val removed = MaterialTheme.colorScheme.error
    val added = androidx.compose.ui.graphics.Color(if (LocalWorkbenchDark.current) 0xFF6EE7B7 else 0xFF087F5B)
    val details = remember(approval.detail, normal, removed, added) {
        buildAnnotatedString {
            approval.detail.lineSequence().forEach { line ->
                pushStyle(SpanStyle(color = when { line.startsWith("+") -> added; line.startsWith("-") -> removed; else -> normal }))
                append(line); append('\n'); pop()
            }
        }
    }
    // Same decisions and scopes as the chat's approval dialog. Keeping this inside the
    // browser window places approvals above WebView without dismissing its document.
    WorkbenchAlertDialog(onDismissRequest = {}, dismissible = false, title = { Text(approval.title) }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (event != null) {
                Text(stringResource(event.tool?.label ?: R.string.ai_review_request), style = MaterialTheme.typography.labelLarge)
                Text(event.reason.ifBlank { stringResource(R.string.ai_reason_missing) }, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_tool_authority, stringResource(event.permissionSource), stringResource(event.permission.label)),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (approval.review != null) TextButton(onClick = { if (current()) inspecting = approval.review }) {
                Text(stringResource(R.string.ai_review_content))
            }
            SelectionContainer { Text(details, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            if (event != null) {
                Text(stringResource(R.string.ai_approval_scope), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { decide(AiDecision.TaskTarget) }) { Text(stringResource(R.string.ai_approve_target)) }
                TextButton(onClick = { decide(AiDecision.TaskTool) }) { Text(stringResource(R.string.ai_approve_tool_task)) }
                Text(stringResource(R.string.ai_approve_tool_task_scope), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { if (current()) permissions = true }) { Text(stringResource(R.string.ai_permissions)) }
            }
            TextButton(onClick = { if (current()) workbench.ai.stop() }) { Text(stringResource(R.string.ai_stop)) }
        }
    }, confirmButton = { TextButton(onClick = { decide(AiDecision.Once) }) { Text(stringResource(R.string.ai_approve_once)) } },
        dismissButton = { TextButton(onClick = { decide(AiDecision.Reject) }) { Text(stringResource(R.string.ai_reject)) } })
    inspecting?.let { review -> AiCodeReview(review, workbench.editor.languages) { inspecting = null } }
    if (permissions) {
        val workbenchState by workbench.state.collectAsStateWithLifecycle()
        AiPermissionsSheet(workbench,
            workbenchState.projects.singleOrNull { it.id == state.projectId }?.name.orEmpty(), { permissions = false })
    }
}

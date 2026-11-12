package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.projects.Project
import java.text.DateFormat
import java.util.Date

@Composable
internal fun WorkspaceConversationsPage(workbench: Workbench, state: WorkbenchState, project: Project,
    onBack: () -> Unit, onFiles: () -> Unit, onTerminal: () -> Unit, onAi: () -> Unit, backEnabled: Boolean = true) {
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val navigate by rememberUpdatedState(onAi)
    var pendingId by remember(project.id) { mutableStateOf<String?>(null) }
    var previousId by remember(project.id) { mutableStateOf("") }
    var actionFailed by remember(project.id) { mutableStateOf(false) }
    var commandMenu by remember(project.id) { mutableStateOf(false) }
    var taskKind by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var deleting by remember(project.id) { mutableStateOf<String?>(null) }
    val identity = state.selectedId == project.id && ai.projectId == project.id
    val ready = identity && !state.busy && !ai.permissionsLoading
    val switching = pendingId != null
    val mutable = ready && !switching && !ai.active && !ai.restoring && !ai.permissionsSaving && !ai.historyError
    val bound = workbench.projects.isBound(project.id)
    val back = rememberWorkbenchBackMotion(enabled = backEnabled, onBack = onBack)

    LaunchedEffect(pendingId, ai.conversationId, ai.permissionsLoading, ai.restoring, ai.historyError, ai.error, ready) {
        val target = pendingId ?: return@LaunchedEffect
        val current = workbench.ai.state.value
        if (current.permissionsLoading || current.restoring || state.busy) return@LaunchedEffect
        if (current.projectId != project.id || state.selectedId != project.id || current.historyError) {
            pendingId = null; actionFailed = true
        } else if (target == "delete") {
            pendingId = null
            if (current.error.isNotBlank()) actionFailed = true
        } else if ((target == "new" && current.conversationId != previousId) || current.conversationId == target) {
            pendingId = null
            navigate()
        } else {
            pendingId = null; actionFailed = true
        }
    }

    Surface(Modifier.fillMaxSize().then(back.modifier), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back.close) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.workspace_threads_back)) }
                Text(project.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                Box {
                    IconButton(onClick = { commandMenu = true }, enabled = ready) {
                        WorkbenchIcon(Glyph.More, description = stringResource(R.string.ai_more))
                    }
                    DropdownMenu(expanded = commandMenu, onDismissRequest = { commandMenu = false }) {
                        listOf("build", "run", "test").forEach { kind ->
                            DropdownMenuItem(text = { Text(commandKind(kind)) }, onClick = {
                                commandMenu = false
                                taskKind = kind
                            })
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    WorkbenchCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (bound) R.string.workspace_flow_local else R.string.workspace_flow_work), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(if (bound) R.string.workspace_flow_local_hint else R.string.workspace_flow_work_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WorkbenchSecondaryButton(onClick = onFiles, enabled = ready, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workspace_threads_files)) }
                                WorkbenchSecondaryButton(onClick = onTerminal, enabled = ready, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workspace_threads_terminal)) }
                            }
                        }
                    }
                }
                if (!ready) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.busy || ai.permissionsLoading || ai.restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(if (ai.active && !identity) R.string.workspace_threads_blocked else R.string.workspace_threads_loading), style = MaterialTheme.typography.bodyMedium)
                        if (!state.busy && !ai.permissionsLoading && !ai.active && !ai.restoring && !ai.permissionsSaving) {
                            WorkbenchSecondaryButton(onClick = { actionFailed = !workbench.openWorkspace(project.id) }) { Text(stringResource(R.string.workspace_threads_retry)) }
                        }
                    }
                }
                if (state.messageIsError && state.message.isNotBlank()) item { Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if ((identity && (ai.error.isNotBlank() || ai.historyError)) || actionFailed) item {
                    Text(ai.error.takeIf { identity && it.isNotBlank() } ?: stringResource(R.string.workspace_threads_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (identity && ai.historyError) item {
                    val canRecover = !state.busy && !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving
                    if (workbench.ai.canRetryHistorySave) TextButton(onClick = { workbench.ai.retryHistorySave() }, enabled = canRecover) {
                        Text(stringResource(R.string.ai_history_retry_save))
                    } else AiHistoryRecoveryActions(ai.historyProblem, enabled = canRecover,
                        onRetry = workbench.ai::retryHistoryLoad,
                        onRemove = { if (!workbench.ai.recoverUnreadableConversation(it)) actionFailed = true })
                }
                if (ready) {
                    item {
                        WorkbenchPrimaryButton(onClick = {
                            actionFailed = false; previousId = ai.conversationId; pendingId = "new"
                            workbench.ai.conversation()
                        }, enabled = mutable, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.workspace_threads_new))
                        }
                    }
                    item { Text(stringResource(R.string.workspace_threads_heading), style = MaterialTheme.typography.titleMedium) }
                    if (ai.conversations.isEmpty() && !ai.historyError) item {
                        Text(stringResource(R.string.workspace_threads_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(ai.conversations, key = { it.id }) { conversation ->
                        val selected = conversation.id == ai.conversationId
                        WorkbenchCard(onClick = {
                            if (selected) onAi() else {
                                actionFailed = false; previousId = ai.conversationId; pendingId = conversation.id
                                workbench.ai.conversation(conversation.id)
                            }
                        }, enabled = !switching && !ai.restoring && !ai.permissionsSaving && !ai.historyError && (selected || mutable),
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(conversation.title.ifBlank { stringResource(R.string.ai_empty_conversation) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conversation.updated)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (selected) Text(stringResource(R.string.workspace_threads_current), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { deleting = conversation.id }, enabled = mutable) { WorkbenchIcon(Glyph.Trash, description = stringResource(R.string.ai_history_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
    taskKind?.let { kind ->
        ProjectTasksPage(workbench, project.id, onAi = {
            taskKind = null
            onAi()
        }, initialKind = kind) { taskKind = null }
    }
    deleting?.let { id ->
        WorkbenchAlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.ai_history_delete)) },
            text = { Text(stringResource(R.string.ai_history_delete_detail)) },
            confirmButton = { TextButton(enabled = mutable, onClick = { deleting = null; actionFailed = false; pendingId = "delete"; workbench.ai.deleteConversation(id) }) { Text(stringResource(R.string.ai_history_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AiCheckpointSheet(workbench: Workbench,
    onFork: (suspend (conversation: String, turn: Int) -> Boolean)? = null, close: () -> Unit) {
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    key(ai.projectId) { AiCheckpointContent(workbench, close, onFork) }
}

@Composable
private fun AiCheckpointContent(workbench: Workbench, close: () -> Unit,
    onFork: (suspend (conversation: String, turn: Int) -> Boolean)?) {
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<AiCheckpoint>()) }
    var changes by remember { mutableStateOf(emptyList<AiCheckpointChange>()) }
    var selected by remember { mutableStateOf<AiCheckpoint?>(null) }
    var review by remember { mutableStateOf<AiReview?>(null) }
    var expandedScope by remember { mutableStateOf(false) }
    var restoreFailed by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<AiCheckpointRecovery?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var forkTarget by remember { mutableStateOf<AiCheckpoint?>(null) }
    var forking by remember { mutableStateOf(false) }
    var forkFailed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(ai.projectId, revision, selected?.id, ai.workspaceRevision) {
        loading = true; failed = false
        changes = emptyList(); recovery = null
        try {
            val key = selected?.id
            records = workbench.ai.checkpointList()
            val item = records.firstOrNull { it.id == key }
            selected = item
            if (item == null) Unit
            else if (!item.sealed && !item.restored) {
                recovery = workbench.ai.reviewInterruptedCheckpoint(item.id)
                changes = recovery!!.changes
            } else changes = workbench.ai.checkpointChanges(item.id)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    WorkbenchSheet(stringResource(R.string.ai_checkpoints), close) {
        Text(stringResource(R.string.ai_checkpoint_file_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expandedScope = !expandedScope }) { Text(stringResource(if (expandedScope) R.string.ai_checkpoint_scope_less else R.string.ai_checkpoint_scope_more)) }
        if (expandedScope) Text(stringResource(R.string.ai_checkpoint_scope), style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (restoreFailed) {
            Text(stringResource(R.string.ai_checkpoint_conflict), color = MaterialTheme.colorScheme.error)
            records.firstOrNull { it.id == selected?.id }?.takeIf { it.restoredCount > 0 }?.let { Text(stringResource(R.string.ai_checkpoint_partial, it.restoredCount)) }
            TextButton(onClick = { restoreFailed = false; revision++ }) { Text(stringResource(R.string.ai_checkpoint_review_again)) }
        }
        if (failed) {
            Text(stringResource(R.string.ai_checkpoint_load_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { revision++ }) { Text(stringResource(R.string.editor_retry)) }
        }
        if (selected?.sealed == false && selected?.restored != true) Text(stringResource(R.string.ai_checkpoint_interrupted_review), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (recovery?.limited == true) Text(stringResource(R.string.ai_checkpoint_limited), color = MaterialTheme.colorScheme.error)
        recovery?.takeIf { it.restoredCount > 0 }?.let { Text(stringResource(R.string.ai_checkpoint_partial, it.restoredCount)) }
        if (selected != null) {
            TextButton(onClick = { selected = null }) { Text(stringResource(R.string.editor_back)) }
            val checkpoint = selected!!
            Text(ai.conversations.firstOrNull { it.id == checkpoint.conversation }?.title
                ?: stringResource(R.string.ai_checkpoint_conversation, checkpoint.conversation.take(8)),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.project_validation_turn, checkpoint.turn), style = MaterialTheme.typography.labelMedium)
            if (checkpoint.conversation == ai.conversationId) ai.timeline.firstOrNull {
                it.turn == checkpoint.turn && it.role == AiRole.User
            }?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected == null) {
                if (records.isEmpty() && !loading && !failed) item { Text(stringResource(R.string.ai_checkpoint_empty)) }
                items(records, key = { it.id }) { item ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading && !failed) { selected = item }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ai.conversations.firstOrNull { it.id == item.conversation }?.title
                            ?: stringResource(R.string.ai_checkpoint_conversation, item.conversation.take(8)),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.created)))
                        Text(if (!item.sealed && !item.restored) stringResource(R.string.ai_checkpoint_unsealed_row, item.turn) else stringResource(R.string.ai_checkpoint_row, item.turn, item.changed), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (item.conversation == ai.conversationId) R.string.ai_checkpoint_current_conversation else R.string.ai_checkpoint_other_conversation),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.conversation == ai.conversationId) ai.timeline.firstOrNull { it.turn == item.turn && it.role == AiRole.User }
                            ?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        if (!item.sealed && !item.restored) Text(stringResource(R.string.ai_checkpoint_unsealed), style = MaterialTheme.typography.bodySmall)
                        if (item.limited) Text(stringResource(R.string.ai_checkpoint_limited), style = MaterialTheme.typography.bodySmall)
                        if (item.restoredCount > 0 && !item.restored) Text(stringResource(R.string.ai_checkpoint_partial, item.restoredCount), style = MaterialTheme.typography.bodySmall)
                        if (item.restored) Text(stringResource(R.string.ai_checkpoint_restored), style = MaterialTheme.typography.bodySmall)
                    } }
                }
            } else {
                if (changes.isEmpty() && !loading && !failed) item { Text(stringResource(R.string.ai_no_changes)) }
                items(changes, key = { it.path }) { change -> TextButton(enabled = !loading && !failed, onClick = { review = AiReview(change.path, change.before, change.after, change.directory, change.beforeMode, change.afterMode) }) { WorkbenchIcon(if (change.directory) Glyph.Projects else Glyph.File); Spacer(Modifier.width(8.dp)); Text(if (change.directory) stringResource(R.string.ai_checkpoint_directory_path, change.path) else change.path) } }
            }
        }
        if (forkFailed) Text(stringResource(R.string.ai_checkpoint_fork_failed), color = MaterialTheme.colorScheme.error)
        if (selected != null && onFork != null) TextButton(onClick = { forkTarget = selected },
            enabled = !loading && !failed && !forking && !ai.active && !ai.restoring) {
            Text(stringResource(R.string.ai_checkpoint_fork))
        }
        if (selected != null && (changes.isNotEmpty() || (recovery?.restoredCount ?: 0) > 0)) OutlinedButton(onClick = { confirm = true }, enabled = !loading && !failed && !restoreFailed && selected?.restored != true && !ai.active && !ai.restoring) { Text(stringResource(R.string.ai_checkpoint_restore)) }
    }
    forkTarget?.let { target ->
        AlertDialog(onDismissRequest = { if (!forking) forkTarget = null },
            title = { Text(stringResource(R.string.ai_checkpoint_fork)) },
            text = { Text(stringResource(R.string.ai_checkpoint_fork_detail, target.turn)) },
            confirmButton = { TextButton(enabled = !forking && !ai.active && !ai.restoring, onClick = {
                if (onFork != null) {
                    forking = true
                    forkFailed = false
                    scope.launch {
                        try {
                            if (onFork(target.conversation, target.turn)) { forkTarget = null; close() }
                            else { forkFailed = true; forkTarget = null }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { forkFailed = true; forkTarget = null }
                        finally { forking = false }
                    }
                }
            }) { Text(stringResource(R.string.ai_checkpoint_fork)) } },
            dismissButton = { TextButton(enabled = !forking, onClick = { forkTarget = null }) { Text(stringResource(R.string.cancel)) } })
    }
    review?.let { AiCodeReview(it, workbench.editor.languages) { review = null } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.ai_checkpoint_restore)) },
        text = { Text(stringResource(if (recovery != null) R.string.ai_checkpoint_interrupted_confirm else R.string.ai_checkpoint_restore_detail)) }, confirmButton = { TextButton(onClick = {
            confirm = false; loading = true
            scope.launch { try {
                restoreFailed = !workbench.ai.restoreCheckpoint(selected!!.id, recovery)
                if (!restoreFailed) { selected = null; revision++ }
                else { records = workbench.ai.checkpointList(); recovery = null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { restoreFailed = true }
            finally { loading = false }
            }
        }) { Text(stringResource(R.string.ai_checkpoint_restore)) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

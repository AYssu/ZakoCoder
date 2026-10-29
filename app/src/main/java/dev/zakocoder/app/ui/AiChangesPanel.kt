package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class AiChangesStatus { Loading, NoTask, Missing, Unsealed, Ready, Failed, Running, Restoring }
internal data class AiChangesSnapshot(
    val status: AiChangesStatus = AiChangesStatus.Loading,
    val checkpoints: List<AiCheckpoint> = emptyList(),
    val checkpoint: AiCheckpoint? = null,
    val changes: List<AiCheckpointChange> = emptyList()
)

/** A fresh instance belongs to one project/conversation/turn/workspace identity. */
internal class AiChangesRead {
    var snapshot by mutableStateOf(AiChangesSnapshot())
        private set

    suspend fun load(state: AiState, turn: Int, checkpoints: suspend () -> List<AiCheckpoint>, changes: suspend (String) -> List<AiCheckpointChange>) {
        try {
            if (state.permissionsLoading) return
            if (state.projectId == null || turn <= 0) {
                snapshot = AiChangesSnapshot(AiChangesStatus.NoTask)
                return
            }
            val records = checkpoints().filter { it.conversation == state.conversationId }
            currentCoroutineContext().ensureActive()
            val checkpoint = records.firstOrNull { it.turn == turn }
            val status = when {
                state.restoring -> AiChangesStatus.Restoring
                state.active -> AiChangesStatus.Running
                checkpoint == null -> AiChangesStatus.Missing
                !checkpoint.sealed -> AiChangesStatus.Unsealed
                else -> AiChangesStatus.Ready
            }
            val result = if (status == AiChangesStatus.Ready) changes(requireNotNull(checkpoint).id) else emptyList()
            currentCoroutineContext().ensureActive()
            snapshot = AiChangesSnapshot(status, records, checkpoint, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            snapshot = AiChangesSnapshot(AiChangesStatus.Failed)
        }
    }
}

@Composable
internal fun AiChangesPanel(
    state: AiState,
    languages: EditorLanguages,
    onUndo: (AiChange) -> Unit,
    onCheckpoints: () -> Unit,
    loadCheckpoints: suspend () -> List<AiCheckpoint>,
    loadChanges: suspend (String) -> List<AiCheckpointChange>,
    modifier: Modifier = Modifier
) {
    var selectedTurn by rememberSaveable(state.projectId, state.conversationId, state.turn) { mutableIntStateOf(state.turn) }
    var retry by remember { mutableIntStateOf(0) }
    var turnMenu by remember(state.projectId, state.conversationId) { mutableStateOf(false) }
    // Reset during composition, so old paths and reviews cannot appear under a new identity.
    val read = remember(state.projectId, state.conversationId, state.turn, selectedTurn, state.workspaceRevision,
        state.active, state.restoring, state.permissionsLoading, state.checkpointId, retry) { AiChangesRead() }
    var review by remember(read) { mutableStateOf<AiReview?>(null) }
    LaunchedEffect(read) { read.load(state, selectedTurn, loadCheckpoints, loadChanges) }
    val snapshot = read.snapshot
    val turns = (listOf(state.turn) + state.timeline.map { it.turn } + state.events.map { it.turn } +
        snapshot.checkpoints.map { it.turn }).filter { it > 0 }.distinct().sortedDescending()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        item {
            Text(stringResource(R.string.ai_changes_persisted_help), style = MaterialTheme.typography.bodySmall)
            if (selectedTurn > 0) Box {
                TextButton(onClick = { turnMenu = true }) { Text(stringResource(R.string.project_validation_turn, selectedTurn)) }
                DropdownMenu(turnMenu, { turnMenu = false }) {
                    turns.forEach { turn -> DropdownMenuItem(text = { Text(stringResource(R.string.project_validation_turn, turn)) },
                        onClick = { selectedTurn = turn; turnMenu = false }) }
                }
            }
            snapshot.checkpoint?.let { checkpoint ->
                if (checkpoint.limited) Text(stringResource(R.string.project_validation_snapshot_limited), style = MaterialTheme.typography.bodySmall)
                if (checkpoint.restored) Text(stringResource(R.string.ai_changes_restored_history), style = MaterialTheme.typography.bodySmall)
            }
            val message = when (snapshot.status) {
                AiChangesStatus.Loading -> R.string.loading
                AiChangesStatus.NoTask -> R.string.ai_changes_no_task
                AiChangesStatus.Missing -> R.string.ai_changes_missing_checkpoint
                AiChangesStatus.Unsealed -> R.string.ai_checkpoint_unsealed
                AiChangesStatus.Failed -> R.string.ai_changes_read_failed
                AiChangesStatus.Running -> R.string.ai_changes_running
                AiChangesStatus.Restoring -> R.string.ai_changes_restoring
                AiChangesStatus.Ready -> if (snapshot.changes.isEmpty()) R.string.ai_changes_snapshot_empty else null
            }
            message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = if (snapshot.status == AiChangesStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (snapshot.status == AiChangesStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (snapshot.status == AiChangesStatus.Failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.editor_retry)) }
            if (snapshot.status != AiChangesStatus.NoTask) TextButton(onClick = onCheckpoints,
                enabled = !state.active && !state.restoring && state.projectId != null) { Text(stringResource(R.string.ai_checkpoints)) }
        }
        items(snapshot.changes, key = { it.path }) { change ->
            TextButton(onClick = { review = AiReview(change.path, change.before, change.after, change.directory, change.beforeMode, change.afterMode) }, modifier = Modifier.fillMaxWidth()) {
                WorkbenchIcon(if (change.directory) Glyph.Projects else Glyph.File)
                Spacer(Modifier.width(8.dp))
                Text(if (change.directory) stringResource(R.string.ai_checkpoint_directory_path, change.path) else change.path, Modifier.weight(1f))
                WorkbenchIcon(Glyph.Next)
            }
        }
        // Runtime capabilities are shown only beside the latest turn, never while browsing older tasks.
        if (selectedTurn == state.turn) {
        if (state.changes.isNotEmpty()) item {
            Text(stringResource(R.string.ai_changes_runtime_undo), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.ai_changes_runtime_undo_help), style = MaterialTheme.typography.bodySmall)
        }
        items(state.changes) { change -> Column {
            TextButton(onClick = { review = AiReview(change.path, change.before?.text, change.after.text) }) {
                WorkbenchIcon(Glyph.File); Spacer(Modifier.width(8.dp)); Text(change.path)
            }
            OutlinedButton(onClick = { onUndo(change) }, enabled = !state.active && !state.restoring && !state.permissionsLoading) {
                WorkbenchIcon(Glyph.Undo); Text(stringResource(R.string.ai_undo_file, change.path))
            }
        } }
        if (state.commandChanges.isNotEmpty()) item {
            Text(stringResource(R.string.ai_changes_runtime_commands), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.ai_changes_runtime_commands_help), style = MaterialTheme.typography.bodySmall)
            if (state.commandReviewLimited) Text(stringResource(R.string.ai_command_review_limited), style = MaterialTheme.typography.bodySmall)
        }
        items(state.commandChanges, key = { "command-${it.id}" }) { change ->
            TextButton(onClick = { review = AiReview(change.path, change.before, change.after) }, modifier = Modifier.fillMaxWidth()) {
                WorkbenchIcon(Glyph.File); Spacer(Modifier.width(8.dp)); Text(change.path)
            }
        }
        }
    }
    review?.let { AiCodeReview(it, languages) { review = null } }
}

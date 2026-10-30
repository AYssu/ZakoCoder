package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @Suppress("UNUSED_PARAMETER") onCheckpoints: () -> Unit,
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
    // A checkpoint is the durable source; transient observations only fill paths it does not contain.
    val latest = selectedTurn == state.turn
    val rows = remember(snapshot.changes, state.changes, state.commandChanges, latest) {
        val result = linkedMapOf<String, AiReview>()
        snapshot.changes.forEach { change -> result[change.path] = AiReview(change.path, change.before, change.after,
            change.directory, change.beforeMode, change.afterMode) }
        if (latest) {
            state.commandChanges.groupBy { it.path }.forEach { (path, changes) -> result.putIfAbsent(path,
                AiReview(path, changes.first().before, changes.last().after)) }
            state.changes.groupBy { it.path }.forEach { (path, changes) -> result.putIfAbsent(path,
                AiReview(path, changes.first().before?.text, changes.last().after.text)) }
        }
        result.values.toList()
    }
    val calculated by produceState<Pair<List<AiReview>, Map<String, AiLineSummary>>?>(null, rows) {
        value = rows to withContext(Dispatchers.Default) { rows.associate { it.path to aiLineSummary(it) } }
    }
    val summaries = calculated?.takeIf { it.first == rows }?.second.orEmpty()
    val conversation = state.conversations.firstOrNull { it.id == state.conversationId }?.title
    val prompt = state.timeline.firstOrNull { it.turn == selectedTurn && it.role == AiRole.User }?.text
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text(stringResource(R.string.ai_file_changes_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_file_changes_scope), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(conversation ?: stringResource(R.string.ai_checkpoint_conversation, state.conversationId.take(8)),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selectedTurn > 0) Box {
                TextButton(onClick = { turnMenu = true }) { Text(stringResource(R.string.project_validation_turn, selectedTurn)) }
                DropdownMenu(turnMenu, { turnMenu = false }) {
                    turns.forEach { turn -> DropdownMenuItem(text = { Text(stringResource(R.string.project_validation_turn, turn)) },
                        onClick = { selectedTurn = turn; turnMenu = false }) }
                }
            }
            Text(prompt ?: stringResource(R.string.ai_file_changes_prompt_missing), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rows.isNotEmpty()) Text(stringResource(R.string.ai_file_changes_count, rows.size),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
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
                AiChangesStatus.Ready -> if (rows.isEmpty()) R.string.ai_changes_snapshot_empty else null
            }
            message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = if (snapshot.status == AiChangesStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (latest && state.commandReviewLimited) Text(stringResource(R.string.ai_command_review_limited), style = MaterialTheme.typography.bodySmall)
            if (snapshot.status == AiChangesStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (snapshot.status == AiChangesStatus.Failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.editor_retry)) }
        }
        items(rows, key = { it.path }) { item ->
            val undo = if (latest) state.changes.lastOrNull { it.path == item.path } else null
            Column {
                Row(Modifier.fillMaxWidth().clickable { review = item }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkbenchIcon(if (item.directory) Glyph.Projects else Glyph.File)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if ('/' in item.path) Text(item.path, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.directory) Text(stringResource(R.string.ai_file_changes_directory), style = MaterialTheme.typography.labelSmall)
                        if (snapshot.changes.none { it.path == item.path }) Text(stringResource(R.string.ai_file_changes_observed),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else summaries[item.path]?.let { count -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ai_file_changes_added, count.added), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                            Text(stringResource(R.string.ai_file_changes_removed, count.removed), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        } }
                    }
                    WorkbenchIcon(Glyph.Next)
                }
                if (undo != null) TextButton(onClick = { onUndo(undo) },
                    enabled = !state.active && !state.restoring && !state.permissionsLoading) {
                    WorkbenchIcon(Glyph.Undo)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_file_changes_undo_last))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    review?.let { AiCodeReview(it, languages) { review = null } }
}

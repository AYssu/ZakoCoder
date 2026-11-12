package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
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
                state.active && turn == state.turn -> AiChangesStatus.Running
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

/** All reads are scoped to an actual project, conversation, turn and workspace revision. */
@Composable
private fun rememberChangesRead(state: AiState, turn: Int, retry: Int,
    loadCheckpoints: suspend () -> List<AiCheckpoint>, loadChanges: suspend (String) -> List<AiCheckpointChange>): AiChangesRead {
    val read = remember(state.projectId, state.conversationId, state.turn, turn, state.workspaceRevision,
        state.active, state.restoring, state.permissionsLoading, state.checkpointId, retry) { AiChangesRead() }
    LaunchedEffect(read) { read.load(state, turn, loadCheckpoints, loadChanges) }
    return read
}

/** Unknown legacy ownership is not sufficient evidence to attach a write to the current task. */
internal fun aiCurrentTurnEditorChanges(state: AiState): List<AiChange> =
    state.changes.filter { state.turn > 0 && it.turn == state.turn && it.after.projectId == state.projectId }

/** A sealed checkpoint is authoritative; transient records are never allowed to widen that task's scope. */
internal fun aiChangeRowsForTurn(state: AiState, turn: Int, snapshot: AiChangesSnapshot): List<AiReview> {
    val result = linkedMapOf<String, AiReview>()
    snapshot.changes.forEach { change -> result[change.path] = AiReview(change.path, change.before, change.after,
        change.directory, change.beforeMode, change.afterMode) }
    if (turn == state.turn && snapshot.status != AiChangesStatus.Ready) {
        val commandEvents = state.events.filter { it.turn == turn && it.tool == AiTool.RunCommand }.map { it.id }.toSet()
        state.commandChanges.filter { it.id.substringBefore(':') in commandEvents }.groupBy { it.path }.forEach { (path, changes) ->
            result.putIfAbsent(path, AiReview(path, changes.first().before, changes.last().after))
        }
        aiCurrentTurnEditorChanges(state).groupBy { it.path }.forEach { (path, changes) ->
            result.putIfAbsent(path, AiReview(path, changes.first().before?.text, changes.last().after.text))
        }
    }
    return result.values.toList()
}

@Composable
private fun rememberChangeRows(state: AiState, turn: Int, snapshot: AiChangesSnapshot): List<AiReview> =
    remember(snapshot.changes, snapshot.status, state.changes, state.commandChanges, state.events, state.projectId, turn, state.turn) {
        aiChangeRowsForTurn(state, turn, snapshot)
    }

@Composable
internal fun AiConversationChangeCard(
    state: AiState, turn: Int, languages: EditorLanguages, onUndo: (AiChange) -> Unit,
    loadCheckpoints: suspend () -> List<AiCheckpoint>, loadChanges: suspend (String) -> List<AiCheckpointChange>,
    modifier: Modifier = Modifier, compact: Boolean = false
) {
    var retry by remember { mutableIntStateOf(0) }
    val read = rememberChangesRead(state, turn, retry, loadCheckpoints, loadChanges)
    val rows = rememberChangeRows(state, turn, read.snapshot)
    val summaries = rememberChangeSummaries(rows)
    var open by remember(state.projectId, state.conversationId, turn, state.workspaceRevision) { mutableStateOf(false) }
    if (read.snapshot.status == AiChangesStatus.NoTask ||
        (read.snapshot.status == AiChangesStatus.Ready && rows.isEmpty()) ||
        (rows.isEmpty() && (read.snapshot.status == AiChangesStatus.Loading ||
            ((state.active || state.questionContinuation != null) &&
                read.snapshot.status in setOf(AiChangesStatus.Running, AiChangesStatus.Unsealed))))) return
    val limited = read.snapshot.checkpoint?.limited == true ||
        (turn == state.turn && state.commandReviewLimited) || rows.any { row -> read.snapshot.changes.none { it.path == row.path } }
    val qualifier = when (read.snapshot.status) {
        AiChangesStatus.Failed -> R.string.ai_changes_short_failed
        AiChangesStatus.Missing, AiChangesStatus.Unsealed -> R.string.ai_changes_short_unsealed
        AiChangesStatus.Loading -> R.string.loading
        AiChangesStatus.Running -> R.string.ai_changes_short_running
        AiChangesStatus.Restoring -> R.string.ai_changes_short_restoring
        else -> if (limited) R.string.ai_changes_short_limited else if (read.snapshot.checkpoint?.restored == true) R.string.ai_changes_short_restored else null
    }
    val compactDescription = stringResource(R.string.ai_file_changes_title)
    if (compact) TextButton(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp)
        .semantics { contentDescription = compactDescription },
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        WorkbenchIcon(Glyph.File, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        val counts = summaries?.takeIf { rows.isNotEmpty() }
        val addedColor = changeAddedColor()
        val removedColor = changeRemovedColor()
        val summary = buildAnnotatedString {
            append(stringResource(R.string.ai_changes_files_count, rows.size))
            if (counts != null) {
                append(" ")
                withStyle(SpanStyle(color = addedColor)) {
                    append(stringResource(R.string.ai_file_changes_added, counts.values.sumOf { it.added }))
                }
                append(" ")
                withStyle(SpanStyle(color = removedColor)) {
                    append(stringResource(R.string.ai_file_changes_removed, counts.values.sumOf { it.removed }))
                }
            }
        }
        Text(summary, Modifier.weight(1f, fill = false), fontSize = 11.sp, fontWeight = FontWeight.Normal,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        qualifier?.let {
            Spacer(Modifier.width(4.dp))
            Text(stringResource(it), Modifier.widthIn(max = 64.dp), fontSize = 10.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (read.snapshot.status == AiChangesStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
    WorkbenchCard(onClick = { open = true }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (rows.isEmpty()) stringResource(R.string.ai_file_changes_title)
                    else stringResource(if (rows.any { it.directory }) R.string.ai_file_changes_count else R.string.ai_changes_files_count, rows.size),
                    style = MaterialTheme.typography.labelLarge)
                Box(Modifier.weight(1f)) {
                    if (summaries != null && rows.isNotEmpty()) ChangeRatio(summaries.values.sumOf { it.added }, summaries.values.sumOf { it.removed })
                }
                if (summaries != null && rows.isNotEmpty()) ChangeNumbers(AiLineSummary(summaries.values.sumOf { it.added }, summaries.values.sumOf { it.removed }))
                else if (rows.isNotEmpty()) Text(stringResource(R.string.loading), style = MaterialTheme.typography.labelSmall)
                WorkbenchIcon(Glyph.Next, Modifier.size(16.dp))
            }
            ChangesStatus(read.snapshot, rows.isEmpty(), { retry++ })
            ChangesLimitations(state, turn, read.snapshot, rows)
        }
    }
    }
    if (open) ChangeReviewSheet(turn, rows, summaries, { open = false }) {
        ChangesList(state, turn, read.snapshot, rows, summaries, languages, onUndo, { retry++ },
            Modifier.fillMaxWidth().heightIn(max = 520.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeReviewSheet(turn: Int, rows: List<AiReview>, summaries: Map<String, AiLineSummary>?,
    onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    WorkbenchPanelSheet(onDismiss = onDismiss, cornerRadius = 20.dp) { requestDismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ai_file_changes_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
                        Text(stringResource(if (rows.any { it.directory }) R.string.ai_file_changes_count
                            else R.string.ai_changes_files_count, rows.size),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.project_validation_turn, turn),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                summaries?.let { ChangeNumbers(AiLineSummary(it.values.sumOf { count -> count.added },
                    it.values.sumOf { count -> count.removed })) }
                IconButton(onClick = requestDismiss) {
                    WorkbenchIcon(Glyph.Close, Modifier.size(18.dp), description = stringResource(R.string.close))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
internal fun AiChangesPanel(
    state: AiState, languages: EditorLanguages, onUndo: (AiChange) -> Unit,
    @Suppress("UNUSED_PARAMETER") onCheckpoints: () -> Unit,
    loadCheckpoints: suspend () -> List<AiCheckpoint>, loadChanges: suspend (String) -> List<AiCheckpointChange>,
    modifier: Modifier = Modifier
) {
    var selectedTurn by rememberSaveable(state.projectId, state.conversationId, state.turn) { mutableIntStateOf(state.turn) }
    var retry by remember { mutableIntStateOf(0) }
    var turnMenu by remember(state.projectId, state.conversationId) { mutableStateOf(false) }
    val read = rememberChangesRead(state, selectedTurn, retry, loadCheckpoints, loadChanges)
    val rows = rememberChangeRows(state, selectedTurn, read.snapshot)
    val summaries = rememberChangeSummaries(rows)
    val turns = (listOf(state.turn) + state.timeline.map { it.turn } + state.events.map { it.turn } +
        read.snapshot.checkpoints.map { it.turn }).filter { it > 0 }.distinct().sortedDescending()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_file_changes_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
            if (selectedTurn > 0) Box {
                TextButton(onClick = { turnMenu = true }) { Text(stringResource(R.string.project_validation_turn, selectedTurn)) }
                DropdownMenu(turnMenu, { turnMenu = false }) {
                    turns.forEach { turn -> DropdownMenuItem(text = { Text(stringResource(R.string.project_validation_turn, turn)) },
                        onClick = { selectedTurn = turn; turnMenu = false }) }
                }
            }
        }
        Text(state.timeline.firstOrNull { it.turn == selectedTurn && it.role == AiRole.User }?.text
            ?: stringResource(R.string.ai_file_changes_prompt_missing), style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChangesList(state, selectedTurn, read.snapshot, rows, summaries, languages, onUndo, { retry++ }, Modifier.weight(1f))
    }
}

@Composable
private fun rememberChangeSummaries(rows: List<AiReview>): Map<String, AiLineSummary>? {
    val calculated by produceState<Pair<List<AiReview>, Map<String, AiLineSummary>>?>(null, rows) {
        value = rows to withContext(Dispatchers.Default) { rows.associate { it.path to aiLineSummary(it) } }
    }
    return calculated?.takeIf { it.first == rows }?.second
}

@Composable
private fun ChangesStatus(snapshot: AiChangesSnapshot, empty: Boolean, retry: () -> Unit) {
    val message = when (snapshot.status) {
        AiChangesStatus.Loading -> R.string.loading
        AiChangesStatus.NoTask -> R.string.ai_changes_no_task
        AiChangesStatus.Missing -> R.string.ai_changes_missing_checkpoint
        AiChangesStatus.Unsealed -> R.string.ai_checkpoint_unsealed
        AiChangesStatus.Failed -> R.string.ai_changes_read_failed
        AiChangesStatus.Running -> R.string.ai_changes_running
        AiChangesStatus.Restoring -> R.string.ai_changes_restoring
        AiChangesStatus.Ready -> if (empty) R.string.ai_changes_snapshot_empty else null
    }
    message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
        color = if (snapshot.status == AiChangesStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    if (snapshot.status == AiChangesStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (snapshot.status == AiChangesStatus.Failed) TextButton(onClick = retry) { Text(stringResource(R.string.editor_retry)) }
}

@Composable
private fun ChangesList(state: AiState, turn: Int, snapshot: AiChangesSnapshot, rows: List<AiReview>,
    summaries: Map<String, AiLineSummary>?, languages: EditorLanguages, onUndo: (AiChange) -> Unit,
    retry: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(state.projectId, state.conversationId, turn, state.workspaceRevision, snapshot) { mutableStateOf<String?>(null) }
    var fullReview by remember(state.projectId, state.conversationId, turn, state.workspaceRevision, snapshot) { mutableStateOf<AiReview?>(null) }
    val sorted = remember(rows, summaries) { rows.sortedWith(compareByDescending<AiReview> {
        summaries?.get(it.path)?.let { count -> count.added.toLong() + count.removed } ?: 0L
    }.thenBy { it.path }) }
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        item {
            ChangesStatus(snapshot, rows.isEmpty(), retry)
            ChangesLimitations(state, turn, snapshot, rows)
        }
        items(sorted, key = { it.path }) { item ->
            Column {
                val expansion = stringResource(if (expanded == item.path) R.string.mobile_expanded else R.string.mobile_collapsed)
                Column(Modifier.fillMaxWidth().semantics { stateDescription = expansion }
                    .clickable(role = Role.Button) { expanded = if (expanded == item.path) null else item.path }
                    .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if ('/' in item.path) Text(item.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        summaries?.get(item.path)?.let { ChangeNumbers(it) }
                        if (expanded == item.path) IconButton(onClick = { fullReview = item }, modifier = Modifier.size(48.dp)) {
                            WorkbenchIcon(Glyph.File, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                description = stringResource(R.string.ai_changes_full_review))
                        }
                        WorkbenchIcon(if (expanded == item.path) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp))
                    }
                    if (snapshot.changes.none { it.path == item.path }) Text(stringResource(R.string.ai_file_changes_observed),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded == item.path) {
                    InlineChangeDiff(item)
                    val undo = if (turn == state.turn) aiCurrentTurnEditorChanges(state).lastOrNull { it.path == item.path } else null
                    if (undo != null) TextButton(onClick = { onUndo(undo) },
                        enabled = !state.active && !state.restoring && !state.permissionsLoading) {
                        Text(stringResource(R.string.ai_file_changes_undo_last))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    fullReview?.let { AiCodeReview(it, languages) { fullReview = null } }
}

@Composable
private fun ChangesLimitations(state: AiState, turn: Int, snapshot: AiChangesSnapshot, rows: List<AiReview>) {
    snapshot.checkpoint?.let {
        if (it.limited) Text(stringResource(R.string.project_validation_snapshot_limited), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (it.restored) Text(stringResource(R.string.ai_changes_restored_history), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (turn == state.turn && state.commandReviewLimited) Text(stringResource(R.string.ai_command_review_limited),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (rows.any { row -> snapshot.changes.none { it.path == row.path } }) Text(stringResource(R.string.ai_changes_observed_summary),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChangeNumbers(count: AiLineSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_file_changes_added, count.added), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium, color = changeAddedColor())
        Text(stringResource(R.string.ai_file_changes_removed, count.removed), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium, color = changeRemovedColor())
    }
}

@Composable private fun changeAddedColor() = if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF75D49B) else androidx.compose.ui.graphics.Color(0xFF318354)
@Composable private fun changeRemovedColor() = if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFFFF9191) else androidx.compose.ui.graphics.Color(0xFFC34F57)

@Composable
private fun ChangeRatio(added: Int, removed: Int) {
    val total = added.toLong() + removed
    Row(Modifier.fillMaxWidth().height(3.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        .background(MaterialTheme.colorScheme.outlineVariant)) {
        if (total > 0) {
            if (added > 0) Box(Modifier.weight((added.toDouble() / total).toFloat()).fillMaxHeight().background(changeAddedColor()))
            if (removed > 0) Box(Modifier.weight((removed.toDouble() / total).toFloat()).fillMaxHeight().background(changeRemovedColor()))
        }
    }
}

/** Bounded lightweight preview; complete before/after buffers remain in the full Sora review. */
@Composable
private fun InlineChangeDiff(review: AiReview) {
    val rendered by produceState<Pair<AiReview, String>?>(null, review) {
        value = review to withContext(Dispatchers.Default) { fileDiff(review.before.orEmpty(), review.after.orEmpty()) }
    }
    val text = rendered?.takeIf { it.first == review }?.second
    val presence = when {
        review.directory && review.before == null -> R.string.ai_changes_directory_created
        review.directory && review.after == null -> R.string.ai_changes_directory_deleted
        review.directory -> R.string.ai_review_directory
        review.before == null -> R.string.ai_changes_file_created
        review.after == null -> R.string.ai_changes_file_deleted
        else -> null
    }
    presence?.let { Text(stringResource(it), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (review.directory) return
    if (review.beforeMode != review.afterMode) Text(stringResource(R.string.ai_review_file_permissions,
        review.beforeMode ?: stringResource(R.string.ai_review_mode_unknown), review.afterMode ?: stringResource(R.string.ai_review_mode_unknown)),
        fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (review.before == review.after) Text(stringResource(R.string.ai_review_body_unchanged), style = MaterialTheme.typography.bodySmall)
    if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (text.isNotEmpty()) CompactDiffPreview(text)
}

@Composable
private fun CompactDiffPreview(text: String) {
    // Avoid composing a whole megabyte diff in a bottom sheet. The secondary review has the complete data.
    val lines = remember(text) { text.lineSequence().take(401).toList().let { all ->
        all.take(400).map { it.take(1200).replace("\t", "    ") } to
            (all.size > 400 || all.any { it.length > 1200 })
    } }
    val density = LocalDensity.current
    val font = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
        fontSize = 11.sp, lineHeight = 18.sp)
    val measure = rememberTextMeasurer()
    val characterWidth = measure.measure("M", style = font).size.width
    val rowHeight = with(density) { 20.sp.toDp() }
    val description = stringResource(R.string.ai_review_content)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val added = changeAddedColor()
    val removed = changeRemovedColor()
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            val contentWidth = maxOf(maxWidth, with(density) {
                (characterWidth * (lines.first.maxOfOrNull { it.length } ?: 0)).toDp()
            } + 16.dp)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.width(contentWidth)
                    .height((rowHeight * lines.first.size.coerceAtLeast(1)).coerceAtMost(240.dp))
                    .semantics { contentDescription = description }) {
                    items(lines.first.size) { index ->
                        val line = lines.first[index]
                        val color = when {
                            line.startsWith('+') -> added
                            line.startsWith('-') -> removed
                            else -> neutral
                        }
                        val background = when {
                            line.startsWith('+') -> added.copy(alpha = .10f)
                            line.startsWith('-') -> removed.copy(alpha = .10f)
                            else -> Color.Transparent
                        }
                        Text(line, Modifier.fillMaxWidth().background(background)
                            .heightIn(min = rowHeight).padding(horizontal = 8.dp),
                            style = font, color = color, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (lines.second) Text(stringResource(R.string.ai_changes_preview_limited),
            style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.padding(top = 4.dp))
    }
}

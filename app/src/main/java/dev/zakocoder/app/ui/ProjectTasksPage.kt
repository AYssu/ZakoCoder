package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiReference
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.CancellationException

@Composable internal fun commandKind(kind: String): String = stringResource(when (kind) { "build" -> R.string.project_build; "test" -> R.string.project_test; "run" -> R.string.project_run; "dependency" -> R.string.install_dependencies; else -> R.string.ai_command })
@Composable internal fun runStatus(status: String): String = stringResource(when (status) { "running" -> R.string.ai_phase_executing; "passed" -> R.string.project_check_passed; "failed" -> R.string.project_check_failed; "timeout" -> R.string.project_check_timeout; "cancelled" -> R.string.ai_status_cancelled; else -> R.string.project_check_interrupted })

@Composable private fun SourceVerification(run: ProjectRun, state: ProjectTaskState, active: Boolean) {
    val verification = run.verification(if (active || state.busy || state.sourceChecking) null else state.currentSource)
    Text(stringResource(when (verification) {
        ProjectVerification.Unchanged -> R.string.project_source_unchanged
        ProjectVerification.Changed -> R.string.project_source_changed
        ProjectVerification.ChangedDuringRun -> R.string.project_source_during
        ProjectVerification.Pending -> R.string.project_source_pending
        ProjectVerification.Unknown -> R.string.project_source_unknown
    }), style = MaterialTheme.typography.bodySmall, color = if (verification in setOf(ProjectVerification.Changed, ProjectVerification.ChangedDuringRun)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    when (run.configuration(state.commands)) {
        "changed" -> Text(stringResource(R.string.project_source_command_changed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        "timeout_changed" -> Text(stringResource(R.string.project_source_timeout_changed), style = MaterialTheme.typography.bodySmall)
        "timeout_unknown" -> Text(stringResource(R.string.project_source_timeout_unknown), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SourceEvidenceControls(state: ProjectTaskState, active: Boolean, refresh: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = refresh, enabled = !active && !state.busy && !state.loading && !state.sourceChecking) { Text(stringResource(R.string.project_source_refresh)) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { details = true }) { WorkbenchIcon(Glyph.Info, description = stringResource(R.string.project_source_details)) }
    }
    if (state.sourceChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.currentSource?.let { Text(stringResource(R.string.project_source_counts, it.files, it.directories), style = MaterialTheme.typography.labelSmall) }
    if (state.sourceCheckedAt > 0) Text(stringResource(R.string.project_source_checked_at,
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(state.sourceCheckedAt))), style = MaterialTheme.typography.labelSmall)
    if (details) WorkbenchSheet(stringResource(R.string.project_source_details), { details = false }) {
        Text(stringResource(R.string.project_source_scope), style = MaterialTheme.typography.bodyMedium)
        if (state.currentSource?.limited == true) Text(stringResource(R.string.project_source_unknown), color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun UnsavedValidationNotice(count: Int) {
    if (count > 0) Text(stringResource(R.string.project_unsaved_validation, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable private fun ConfirmProjectCommand(command: ProjectCommand, unsavedFiles: Int, execute: () -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.project_command_execute)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UnsavedValidationNotice(unsavedFiles)
            Text(command.shell() + "\n\n" + stringResource(R.string.project_command_effects))
        } },
        confirmButton = { TextButton(onClick = execute) { Text(stringResource(R.string.project_command_execute)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ProjectTasksPage(workbench: Workbench, projectId: String, onAi: (String) -> Unit, validationConversation: String? = null, validationTurn: Int = 0, close: () -> Unit) {
    val state by workbench.projectTasks.state.collectAsStateWithLifecycle()
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val host by workbench.state.collectAsStateWithLifecycle()
    val editor by workbench.editor.state.collectAsStateWithLifecycle()
    val unsavedFiles = editor.tabs.count { it.projectId == projectId && it.dirty }
    val originMatches = validationConversation == null || (ai.projectId == projectId && ai.conversationId == validationConversation && validationTurn in 1..ai.turn)
    val canChange = originMatches && !state.busy && !state.loading && !state.saving && !ai.active && !ai.restoring && !host.busy
    var editing by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var script by rememberSaveable(projectId) { mutableStateOf("") }
    var cwd by rememberSaveable(projectId) { mutableStateOf("") }
    var timeout by rememberSaveable(projectId) { mutableStateOf("") }
    var executing by remember { mutableStateOf<ProjectCommand?>(null) }
    var selected by remember { mutableStateOf<ProjectRun?>(null) }
    LaunchedEffect(projectId, state.busy) { if (!state.busy && state.projectId != projectId) workbench.projectTasks.select(projectId) }
    LaunchedEffect(projectId, state.loading, state.runs, ai.active) { if (state.projectId == projectId && !state.loading && !state.busy && !ai.active) workbench.projectTasks.refreshEvidence() }
    if (state.projectId != projectId) {
        AiFullScreen(stringResource(R.string.project_commands), close) { Text(stringResource(R.string.project_other_busy), Modifier.padding(16.dp)) }
        return
    }
    AiFullScreen(stringResource(R.string.project_commands), close) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item(key = "running-command") {
                val run = state.runs.firstOrNull { it.status == "running" }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(run?.let { commandKind(it.kind) + " · " + runStatus(it.status) }
                            ?: stringResource(R.string.ai_phase_executing), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.project_run_output_preview), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                            Column(Modifier.padding(10.dp)) {
                                state.log.takeLast(1600).trimEnd().ifBlank { stringResource(R.string.project_run_waiting_output) }
                                    .lines().takeLast(4).forEach { line ->
                                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { selected = run }, enabled = run != null, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.project_validation_open_log))
                            }
                            OutlinedButton(onClick = workbench.projectTasks::stop) { Text(stringResource(R.string.ai_stop)) }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.project_commands_help), style = MaterialTheme.typography.bodySmall)
                if (validationConversation != null) {
                    Text(stringResource(R.string.project_validation_turn, validationTurn), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.project_validation_user_review), style = MaterialTheme.typography.bodySmall)
                }
                UnsavedValidationNotice(unsavedFiles) }
            items(listOf("build", "test", "run")) { kind ->
                val command = state.commands.firstOrNull { it.kind == kind }
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(commandKind(kind), style = MaterialTheme.typography.titleSmall)
                    val preview = command?.script?.let(::commandScriptPreview)
                    Text(preview ?: stringResource(R.string.project_command_unset), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (command != null && preview != command.script) Text(stringResource(R.string.project_command_preview_help),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row { TextButton(onClick = { val original = command ?: ProjectCommand(kind, if (kind == "build" && dev.zakocoder.app.projects.ProjectKind.Android in host.project?.kinds.orEmpty()) "gradle --no-daemon :app:assembleDebug" else ""); script = original.script; cwd = original.cwd; timeout = original.timeoutSeconds.toString(); editing = kind }, enabled = canChange) { Text(stringResource(R.string.project_command_configure)) }
                        TextButton(onClick = { executing = command }, enabled = command != null && canChange) { Text(stringResource(R.string.project_command_execute)) } }
                } }
            }
            item { Text(stringResource(R.string.project_runs), style = MaterialTheme.typography.titleSmall)
                SourceEvidenceControls(state, ai.active, workbench.projectTasks::refreshEvidence) }
            if (state.runs.isEmpty()) item { Text(stringResource(R.string.project_runs_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.runs, key = { it.id }) { run -> OutlinedCard(onClick = { selected = run }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(commandKind(run.kind) + " · " + runStatus(run.status), color = if (run.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(run.exit?.let { stringResource(R.string.project_exit_value, it) } ?: stringResource(R.string.project_exit_unknown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceVerification(run, state, ai.active)
                Text(run.script.take(200), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            } } }
        }
    }
    editing?.let { kind ->
        Dialog(onDismissRequest = { editing = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(commandKind(kind), style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(script, { if (it.length <= 16000) script = it }, label = { Text(stringResource(R.string.project_command_script)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                            OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(R.string.project_command_cwd)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                            OutlinedTextField(timeout, { timeout = it }, label = { Text(stringResource(R.string.project_command_timeout)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
                            TextButton(onClick = { workbench.projectTasks.save(ProjectCommand(kind, script, cwd, timeout.toInt())) { saved -> if (saved) editing = null } }, enabled = canChange && script.isNotBlank() && timeout.toIntOrNull() in 1..86400) { Text(stringResource(R.string.editor_save)) }
                        }
                    }
                }
            }
        }
    }
    executing?.let { command -> ConfirmProjectCommand(command, unsavedFiles, { if (canChange && state.projectId == projectId) workbench.projectTasks.run(command, validationConversation.orEmpty(), if (validationConversation == null) 0 else validationTurn); executing = null }, { executing = null }) }
    selected?.let { original ->
        val run = state.runs.firstOrNull { it.id == original.id } ?: original
        ProjectRunLog(workbench, projectId, run, onAi) { selected = null }
    }
}

@Composable
internal fun AiValidationPanel(workbench: Workbench, modifier: Modifier = Modifier, onCommands: (Int) -> Unit, onReference: () -> Unit,
    loadCheckpointChanges: suspend (String) -> List<dev.zakocoder.app.ai.AiCheckpointChange> = workbench.ai::checkpointChanges) {
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val tasks by workbench.projectTasks.state.collectAsStateWithLifecycle()
    val host by workbench.state.collectAsStateWithLifecycle()
    val editor by workbench.editor.state.collectAsStateWithLifecycle()
    val unsavedFiles = editor.tabs.count { it.projectId == ai.projectId && it.dirty }
    var rerun by remember(ai.projectId, ai.conversationId) { mutableStateOf<ProjectCommand?>(null) }
    LaunchedEffect(ai.projectId, ai.active) { ai.projectId?.let(workbench.projectTasks::select) }
    LaunchedEffect(ai.projectId, ai.active, ai.workspaceRevision, tasks.loading, tasks.runs) {
        if (tasks.projectId == ai.projectId && !ai.active && !tasks.busy && !tasks.loading) workbench.projectTasks.refreshEvidence()
    }
    var checkpointResult by remember(ai.projectId, ai.conversationId, ai.turn, ai.active, ai.workspaceRevision) {
        mutableStateOf<Result<List<dev.zakocoder.app.ai.AiCheckpoint>>?>(null)
    }
    var restorations by remember(ai.projectId) { mutableStateOf(emptyList<dev.zakocoder.app.ai.AiRestoration>()) }
    var restorationFailed by remember(ai.projectId) { mutableStateOf(true) }
    LaunchedEffect(ai.projectId, ai.workspaceRevision, ai.active) {
        restorationFailed = true
        try { restorations = workbench.ai.workspaceRestorations(); restorationFailed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { restorations = emptyList() }
    }
    var selectedTurn by rememberSaveable(ai.projectId, ai.conversationId, ai.turn) { mutableIntStateOf(ai.turn) }
    var turnMenu by remember { mutableStateOf(false) }
    var selectedRun by remember(ai.projectId, ai.conversationId) { mutableStateOf<ProjectRun?>(null) }
    LaunchedEffect(ai.projectId, ai.conversationId, ai.turn, ai.active, ai.workspaceRevision) {
        checkpointResult = try { Result.success(workbench.ai.checkpointList().filter { it.conversation == ai.conversationId }) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    }
    val checkpoints = checkpointResult?.getOrNull().orEmpty()
    val checkpoint = checkpoints.firstOrNull { it.turn == selectedTurn }
    // Reset during composition, before a new IO effect starts: old bodies must never
    // be actionable under a different turn/checkpoint or workspace generation.
    var changeResult by remember(ai.projectId, ai.conversationId, selectedTurn, checkpoint?.id, checkpoint?.sealed, ai.active, ai.workspaceRevision) {
        mutableStateOf<Result<List<dev.zakocoder.app.ai.AiCheckpointChange>>?>(null)
    }
    var review by remember(ai.projectId, ai.conversationId, selectedTurn, checkpoint?.id, ai.active, ai.workspaceRevision) {
        mutableStateOf<dev.zakocoder.app.ai.AiReview?>(null)
    }
    LaunchedEffect(ai.projectId, ai.conversationId, selectedTurn, checkpoint?.id, checkpoint?.sealed, ai.active, ai.workspaceRevision) {
        if (!ai.active && checkpoint?.sealed == true) changeResult = try { Result.success(loadCheckpointChanges(checkpoint.id)) }
            catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    }
    val changes = changeResult?.getOrNull().orEmpty()
    val taskRuns = tasks.runs.filter { it.source in setOf("ai", "user") && it.conversation == ai.conversationId }
    val runs = taskRuns.filter { it.turn == selectedTurn }
    val turns = (listOf(ai.turn) + checkpoints.map { it.turn } + taskRuns.map { it.turn }).filter { it > 0 }.distinct().sortedDescending()
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (restorationFailed) Text(stringResource(R.string.project_validation_workspace_unknown), color = MaterialTheme.colorScheme.error)
            else if (runs.any { run -> restorations.any { it.invalidates(run.started) } }) Text(stringResource(R.string.project_validation_restored), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.project_validation_help), style = MaterialTheme.typography.bodySmall)
            UnsavedValidationNotice(unsavedFiles)
            if (turns.size > 1) Box {
                TextButton(onClick = { turnMenu = true }) { Text(stringResource(R.string.project_validation_turn, selectedTurn)) }
                DropdownMenu(turnMenu, { turnMenu = false }) { turns.forEach { turn ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_validation_turn, turn)) }, onClick = { selectedTurn = turn; turnMenu = false })
                } }
            }
            OutlinedButton(onClick = { onCommands(selectedTurn) }, enabled = ai.projectId != null) { Text(stringResource(R.string.project_validation_commands)) }
            SourceEvidenceControls(tasks, ai.active, workbench.projectTasks::refreshEvidence)
        }
        items(listOf("build", "test", "run")) { kind ->
            val run = runs.firstOrNull { it.kind == kind }
            ListItem(headlineContent = { Text(commandKind(kind)) }, supportingContent = { Column {
                Text(run?.let { if (restorationFailed || restorations.any { e -> e.invalidates(it.started) }) stringResource(R.string.project_validation_historical) else runStatus(it.status) } ?: stringResource(R.string.project_not_verified))
                run?.let { SourceVerification(it, tasks, ai.active) }
            } }, trailingContent = { Column {
                Text(run?.exit?.let { stringResource(R.string.project_exit_value, it) } ?: "—", style = MaterialTheme.typography.labelSmall)
                tasks.commands.firstOrNull { it.kind == kind }?.let { command ->
                    TextButton(onClick = { rerun = command }, enabled = !ai.active && !ai.restoring && !tasks.busy && !tasks.loading && !tasks.saving && !host.busy && tasks.projectId == ai.projectId) {
                        Text(stringResource(R.string.project_validation_rerun, commandKind(kind)))
                    }
                }
            } })
        }
        items(runs, key = { it.id }) { run ->
            OutlinedCard(onClick = { selectedRun = run }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(commandKind(run.kind) + " · " + runStatus(run.status), color = if (run.exit != null && run.exit != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (run.source == "user") Text(stringResource(R.string.project_validation_user_review), style = MaterialTheme.typography.labelSmall)
                    SourceVerification(run, tasks, ai.active)
                    Text(run.script, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
                    Text(stringResource(R.string.project_validation_open_log), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { Text(stringResource(R.string.project_validation_changes), style = MaterialTheme.typography.titleSmall) }
        if (checkpoint?.limited == true) item { Text(stringResource(R.string.project_validation_snapshot_limited), style = MaterialTheme.typography.bodySmall) }
        val changeMessage = when {
            checkpointResult == null -> R.string.loading
            checkpointResult?.isFailure == true -> R.string.project_validation_changes_unavailable
            checkpoint == null -> R.string.project_validation_no_snapshot
            ai.active || !checkpoint.sealed -> R.string.ai_checkpoint_unsealed
            changeResult == null -> R.string.loading
            changeResult?.isFailure == true -> R.string.project_validation_changes_unavailable
            changes.isEmpty() -> R.string.project_changes_empty
            else -> null
        }
        changeMessage?.let { message -> item {
            Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
                color = if (message == R.string.project_validation_changes_unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        items(changes, key = { it.path }) { change ->
            TextButton(onClick = { review = dev.zakocoder.app.ai.AiReview(change.path, change.before, change.after, change.directory, change.beforeMode, change.afterMode) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { WorkbenchIcon(if (change.directory) Glyph.Projects else Glyph.File); Text(if (change.directory) stringResource(R.string.ai_checkpoint_directory_path, change.path) else change.path, Modifier.weight(1f)); WorkbenchIcon(Glyph.Next) }
            }
        }
    }
    selectedRun?.let { run -> ai.projectId?.let { id -> ProjectRunLog(workbench, id, tasks.runs.firstOrNull { it.id == run.id } ?: run, { onReference() }) { selectedRun = null } } }
    review?.let { AiCodeReview(it, workbench.editor.languages) { review = null } }
    rerun?.let { command -> ConfirmProjectCommand(command, unsavedFiles, {
        if (!ai.active && !ai.restoring && !tasks.busy && !host.busy && tasks.projectId == ai.projectId)
            workbench.projectTasks.run(command, ai.conversationId, selectedTurn)
        rerun = null
    }, { rerun = null }) }
}

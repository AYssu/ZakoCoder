package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.git.GitEntry
import dev.zakocoder.app.git.GitState
import dev.zakocoder.app.git.preferredRemote
import dev.zakocoder.app.git.tracksRemote
import dev.zakocoder.app.git.destinationBranch

internal data class GitWorkflowActions(
    val refresh: () -> Unit = {}, val initialize: () -> Unit = {},
    val stage: (Set<String>) -> Unit = {}, val unstage: (Set<String>) -> Unit = {},
    val commit: (String, String, String) -> Unit = { _, _, _ -> },
    val fetch: (String) -> Unit = {}, val pull: (String, Boolean) -> Unit = { _, _ -> },
    val push: (String, Boolean) -> Unit = { _, _ -> },
    val createBranch: (String) -> Unit = {}, val switchBranch: (String) -> Unit = {},
    val mergeBranch: (String, String, String) -> Unit = { _, _, _ -> }, val resolve: (Set<String>) -> Unit = {},
    val abortMerge: () -> Unit = {}, val stop: () -> Unit = {}, val openFile: (String) -> Unit = {},
    val credentials: ((String) -> Unit)? = null
)

@Composable
internal fun GitWorkflowContent(state: GitState, close: () -> Unit, actions: GitWorkflowActions) {
    var selected by remember(state.projectId) { mutableStateOf(emptySet<String>()) }
    var dialog by rememberSaveable(state.projectId) { mutableStateOf("") }
    var target by rememberSaveable(state.projectId) { mutableStateOf("") }
    var remote by rememberSaveable(state.projectId) { mutableStateOf("") }
    LaunchedEffect(state.entries) { selected = selected.intersect(state.entries.map { it.path }.toSet()) }
    LaunchedEffect(state.remotes, state.upstream) { remote = state.preferredRemote(remote) }
    val selectedRemote = state.remotes.firstOrNull { it.name == remote }
    val tracksSelectedRemote = state.tracksRemote(remote)
    val remoteBranch = state.destinationBranch(remote)
    val destination = selectedRemote?.let { it.url + "\n" + "refs/heads/$remoteBranch" }.orEmpty()
    val ready = state.repository && state.statusKnown && !state.busy && !state.limited
    val networkReady = ready && remote.isNotBlank() && !state.merging
    AiFullScreen(stringResource(R.string.git_title), close) {
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(gitOperationMessage(state.operation), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = actions.stop) { Text(stringResource(R.string.git_workflow_cancel)) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.git_workflow_branch), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = actions.refresh, enabled = !state.busy) { Text(stringResource(R.string.refresh)) }
                }
                if (state.repository) GitPanel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WorkbenchIcon(Glyph.Swap, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.detached) stringResource(R.string.git_workflow_detached) else state.branch.ifBlank { stringResource(R.string.git_workflow_unborn) }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { dialog = "branches" }, enabled = ready && !state.merging) {
                            WorkbenchIcon(Glyph.ChevronDown, description = stringResource(R.string.git_workflow_branches))
                        }
                    }
                    Text(if (state.upstream.isBlank()) stringResource(R.string.git_workflow_no_upstream) else stringResource(R.string.git_workflow_upstream, state.upstream), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.upstream.isNotBlank()) {
                        Text(stringResource(R.string.git_workflow_counts_source, state.upstream), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.git_workflow_counts, state.ahead, state.behind), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.git_workflow_counts_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.git_help), style = MaterialTheme.typography.bodySmall)
                    GitAction(stringResource(R.string.git_initialize), enabled = !state.busy && state.statusKnown, onClick = { dialog = "init" })
                }
            }
            if (state.repository) item {
                GitPanel {
                    if (state.remotes.isEmpty()) Text(stringResource(R.string.git_workflow_no_remote), style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(stringResource(R.string.git_workflow_remote), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { dialog = "remotes" }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val current = state.remotes.firstOrNull { it.name == remote }
                            Column(Modifier.weight(1f)) {
                                Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleSmall)
                                Text(current?.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            WorkbenchIcon(Glyph.ChevronDown, modifier = Modifier.size(16.dp), description = stringResource(R.string.git_workflow_remote))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitAction(stringResource(R.string.git_workflow_fetch), networkReady, Modifier.weight(1f)) { actions.fetch(remote) }
                        GitAction(stringResource(R.string.git_workflow_pull), networkReady && !state.detached, Modifier.weight(1f)) { dialog = "pull" }
                        GitAction(stringResource(R.string.git_workflow_push), networkReady && !state.detached, Modifier.weight(1f)) { dialog = "push" }
                    }
                    if (selectedRemote?.url?.startsWith("https://") == true) actions.credentials?.let { credentials -> TextButton(onClick = { credentials(selectedRemote.url) }, enabled = !state.busy) { Text(stringResource(R.string.git_workflow_credentials)) } }
                    else if (selectedRemote != null) Text(stringResource(R.string.git_workflow_ssh_credentials), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedRemote != null && !state.detached) Text(stringResource(R.string.git_workflow_destination, remote, remoteBranch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.error.isNotBlank()) item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.git_workflow_failed), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        SelectionContainer { Text(gitFailureMessage(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                    }
                }
            }
            if (state.limited) item { Text(stringResource(R.string.git_status_limited), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.merging || state.conflicts.isNotEmpty()) {
                item {
                    GitPanel {
                        Text(stringResource(if (state.conflicts.isEmpty()) R.string.git_workflow_merging else R.string.git_workflow_conflicts, state.conflicts.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(stringResource(if (state.conflicts.isEmpty()) R.string.git_workflow_merging_ready else R.string.git_workflow_conflict_help), style = MaterialTheme.typography.bodySmall)
                        if (state.merging) TextButton(onClick = { dialog = "abort" }, enabled = !state.busy) { Text(stringResource(R.string.git_workflow_abort), color = MaterialTheme.colorScheme.error) }
                    }
                }
                items(state.conflicts, key = { "conflict:$it" }) { path ->
                    GitPanel {
                        Text(path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GitAction(stringResource(R.string.git_workflow_open), !state.busy, Modifier.weight(1f)) { actions.openFile(path) }
                            GitAction(stringResource(R.string.git_workflow_resolve), ready, Modifier.weight(1f)) { target = path; dialog = "resolve" }
                        }
                    }
                }
            }
            if (state.repository) item {
                Text(stringResource(R.string.git_workflow_changes, state.entries.size), style = MaterialTheme.typography.titleSmall)
                if (state.entries.isEmpty()) Text(stringResource(R.string.git_clean), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.entries.filterNot { it.path in state.conflicts }, key = { "file:${it.path}" }) { entry ->
                Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().toggleable(entry.path in selected, enabled = ready && (entry.path in selected || selected.size < 100), role = Role.Checkbox,
                        onValueChange = { selected = if (it) selected + entry.path else selected - entry.path }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(entry.path in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text(stringResource(entryStatus(entry)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                GitPanel {
                    Text(stringResource(R.string.git_workflow_output), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(state.output.ifBlank { stringResource(R.string.git_workflow_output_empty) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.repository) Surface {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.git_selection_count, selected.size, state.entries.count { it.index != ' ' && it.index != '?' }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitAction(stringResource(R.string.git_stage), ready && selected.isNotEmpty(), Modifier.weight(1f)) { actions.stage(selected); selected = emptySet() }
                    Button(onClick = { dialog = "commit" }, enabled = ready && state.conflicts.isEmpty() && state.entries.any { it.index != ' ' && it.index != '?' }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text(stringResource(R.string.git_commit)) }
                }
                if (selected.any { path -> state.entries.any { it.path == path && it.index != ' ' && it.index != '?' } }) TextButton(onClick = { actions.unstage(selected); selected = emptySet() }, enabled = ready) { Text(stringResource(R.string.git_workflow_unstage)) }
            }
        }
    }
    when (dialog) {
        "branches" -> GitBranchesSheet(state, actions, dismiss = { dialog = "" }, merge = { target = it; dialog = "merge" })
        "remotes" -> WorkbenchSheet(stringResource(R.string.git_workflow_remote), { dialog = "" }) {
            state.remotes.forEach { value -> SelectablePanel(value.name == remote, { remote = value.name; dialog = "" }) {
                Column(Modifier.weight(1f)) { Text(value.name); Text(value.url, style = MaterialTheme.typography.bodySmall) }
            } }
        }
        "commit" -> GitCommitSheet({ dialog = "" }) { message, name, email -> actions.commit(message, name, email); dialog = "" }
        "init" -> GitConfirm(R.string.git_initialize, stringResource(R.string.git_help), { dialog = "" }) { actions.initialize(); dialog = "" }
        "merge" -> GitMergeSheet(target, { dialog = "" }) { name, email -> actions.mergeBranch(target, name, email); dialog = "" }
        "resolve" -> GitConfirm(R.string.git_workflow_resolve_title, stringResource(R.string.git_workflow_resolve_help, target), { dialog = "" }) { actions.resolve(setOf(target)); dialog = "" }
        "abort" -> GitConfirm(R.string.git_workflow_abort_title, stringResource(R.string.git_workflow_abort_help), { dialog = "" }) { actions.abortMerge(); dialog = "" }
        "pull" -> GitConfirm(R.string.git_workflow_pull_title, stringResource(R.string.git_workflow_pull_help, destination), { dialog = "" }) { actions.pull(remote, false); dialog = "" }
        "push" -> GitConfirm(R.string.git_workflow_push_title, stringResource(R.string.git_workflow_push_help, destination) + if (!tracksSelectedRemote) "\n\n" + stringResource(R.string.git_workflow_push_upstream) else "", { dialog = "" }) { actions.push(remote, !tracksSelectedRemote); dialog = "" }
    }
}

@Composable private fun GitPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable internal fun GitAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text(label) }
}
private fun entryStatus(entry: GitEntry) = when {
    entry.index == '?' -> R.string.git_untracked
    entry.index == 'U' || entry.working == 'U' || "${entry.index}${entry.working}" in setOf("AA", "DD") -> R.string.git_conflict
    entry.index != ' ' && entry.working != ' ' -> R.string.git_staged_and_modified
    entry.index != ' ' -> R.string.git_staged
    else -> R.string.git_modified
}
@Composable private fun GitConfirm(title: Int, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.git_workflow_confirm)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun gitFailureMessage(state: GitState): String = when (state.failure?.name) {
    "NotRepository" -> stringResource(R.string.git_not_repository)
    "UrlRewrite" -> stringResource(R.string.git_workflow_error_url_rewrite)
    "NetworkPolicy" -> stringResource(R.string.git_workflow_error_network_policy)
    "Authentication" -> stringResource(R.string.git_workflow_error_auth)
    "Network" -> stringResource(R.string.git_workflow_error_network)
    "Conflict" -> stringResource(R.string.git_workflow_error_conflict)
    "NonFastForward" -> stringResource(R.string.git_workflow_error_diverged)
    "DirtyWorktree" -> stringResource(R.string.git_workflow_error_dirty)
    "MissingUpstream" -> stringResource(R.string.git_workflow_error_upstream)
    "InvalidInput" -> stringResource(R.string.git_workflow_error_input)
    "Rejected" -> stringResource(R.string.git_workflow_error_rejected)
    "Unavailable" -> stringResource(R.string.git_workflow_error_unavailable)
    "Cancelled" -> stringResource(if (state.statusKnown) R.string.git_workflow_cancelled else R.string.git_workflow_cancelled_unknown)
    "TimedOut" -> stringResource(if (state.statusKnown) R.string.git_workflow_timed_out else R.string.git_workflow_timed_out_unknown)
    "StatusUnavailable" -> stringResource(R.string.git_workflow_status_unknown)
    else -> state.error
}

@Composable private fun gitOperationMessage(operation: String): String {
    val label = when (operation) {
        "fetch" -> R.string.git_workflow_fetch
        "pull" -> R.string.git_workflow_pull
        "push" -> R.string.git_workflow_push
        "refresh" -> R.string.git_workflow_refresh
        "stage" -> R.string.git_stage
        "unstage" -> R.string.git_workflow_unstage
        "commit" -> R.string.git_commit
        "init" -> R.string.git_initialize
        "createBranch" -> R.string.git_workflow_create
        "switchBranch" -> R.string.git_workflow_switch_operation
        "merge" -> R.string.git_workflow_merge_operation
        "abortMerge" -> R.string.git_workflow_abort
        else -> return stringResource(R.string.git_workflow_running)
    }
    return stringResource(R.string.git_workflow_running_operation, stringResource(label))
}

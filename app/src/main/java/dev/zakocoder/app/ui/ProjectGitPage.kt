package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench

@Composable
internal fun ProjectGitPage(workbench: Workbench, projectId: String, close: () -> Unit) {
    val state by workbench.git.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var committing by rememberSaveable { mutableStateOf(false) }
    var initializing by remember { mutableStateOf(false) }
    LaunchedEffect(projectId, state.busy) { if (!state.busy && state.projectId != projectId) workbench.git.select(projectId) }
    LaunchedEffect(state.entries) { selected = selected.intersect(state.entries.map { it.path }.toSet()) }
    if (state.projectId != projectId) {
        AiFullScreen(stringResource(R.string.git_title), close) { Text(stringResource(R.string.project_other_busy), Modifier.padding(16.dp)) }
        return
    }
    AiFullScreen(stringResource(R.string.git_title), close) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.git_help), style = MaterialTheme.typography.bodySmall) }
            item { Row { TextButton(onClick = workbench.git::refresh, enabled = !state.busy) { Text(stringResource(R.string.refresh)) }
                if (!state.repository) TextButton(onClick = { initializing = true }, enabled = !state.busy) { Text(stringResource(R.string.git_initialize)) } } }
            if (state.limited) item { Text(stringResource(R.string.git_status_limited), color = MaterialTheme.colorScheme.error) }
            if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (state.repository && state.entries.isEmpty()) item { Text(stringResource(R.string.git_clean)) }
            item { Text(stringResource(R.string.git_selection_count, selected.size, state.entries.count { it.index != ' ' && it.index != '?' }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.entries, key = { it.path }) { entry ->
                OutlinedCard { Row(Modifier.fillMaxWidth().toggleable(value = entry.path in selected, enabled = !state.busy && (entry.path in selected || selected.size < 100), role = Role.Checkbox,
                    onValueChange = { checked -> selected = if (checked) selected + entry.path else selected - entry.path }).padding(8.dp)) {
                    Checkbox(entry.path in selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f).padding(top = 8.dp)) { Text(entry.path, style = MaterialTheme.typography.bodyMedium); Text(stringResource(when {
                        entry.index == '?' -> R.string.git_untracked
                        entry.index == 'U' || entry.working == 'U' || "${entry.index}${entry.working}" in setOf("AA", "DD") -> R.string.git_conflict
                        entry.index != ' ' && entry.working != ' ' -> R.string.git_staged_and_modified
                        entry.index != ' ' -> R.string.git_staged
                        else -> R.string.git_modified
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
            }
            if (state.repository) item {
                OutlinedButton(onClick = { workbench.git.stage(selected); selected = emptySet() }, enabled = selected.isNotEmpty() && !state.busy) { Text(stringResource(R.string.git_stage)) }
                Button(onClick = { committing = true }, enabled = !state.busy && !state.limited && state.entries.any { it.index != ' ' && it.index != '?' }) { Text(stringResource(R.string.git_commit)) }
            }
            if (state.output.isNotBlank()) item { Text(state.output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (initializing) AlertDialog(onDismissRequest = { initializing = false }, title = { Text(stringResource(R.string.git_initialize)) }, text = { Text(stringResource(R.string.git_help)) }, confirmButton = { TextButton(onClick = { initializing = false; workbench.git.initialize() }) { Text(stringResource(R.string.git_initialize)) } }, dismissButton = { TextButton(onClick = { initializing = false }) { Text(stringResource(R.string.cancel)) } })
    if (committing) {
        val messageLabel = stringResource(R.string.git_message)
        val nameLabel = stringResource(R.string.git_name)
        val emailLabel = stringResource(R.string.git_email)
        var message by rememberSaveable { mutableStateOf("") }; var name by rememberSaveable { mutableStateOf("") }; var email by rememberSaveable { mutableStateOf("") }
        Dialog(onDismissRequest = { committing = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.git_commit), style = MaterialTheme.typography.headlineSmall)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.git_commit_help), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(message, { if (it.length <= 2000) message = it }, label = { Text(messageLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = messageLabel }, minLines = 2, maxLines = 4)
                            OutlinedTextField(name, { if (it.length <= 100) name = it }, label = { Text(nameLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                            OutlinedTextField(email, { if (it.length <= 200) email = it }, label = { Text(emailLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = emailLabel }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { committing = false }) { Text(stringResource(R.string.cancel)) }
                            TextButton(onClick = { workbench.git.commit(message, name, email); committing = false }, enabled = message.isNotBlank() && name.isNotBlank() && '@' in email) { Text(stringResource(R.string.git_commit)) }
                        }
                    }
                }
            }
        }
    }
}

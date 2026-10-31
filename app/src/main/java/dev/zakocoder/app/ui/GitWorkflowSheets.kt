package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.git.GitState

@Composable
internal fun GitBranchesSheet(state: GitState, actions: GitWorkflowActions, dismiss: () -> Unit, merge: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    WorkbenchSheet(stringResource(R.string.git_workflow_branches), dismiss) {
        val nameLabel = stringResource(R.string.git_workflow_branch_name)
        OutlinedTextField(name, { if (it.length <= 200 && it.none(Char::isISOControl)) name = it }, label = { Text(nameLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel }, singleLine = true)
        GitAction(stringResource(R.string.git_workflow_create), name.isNotBlank() && !state.busy && !state.merging, Modifier.fillMaxWidth()) { actions.createBranch(name.trim()); dismiss() }
        HorizontalDivider()
        state.branches.forEach { branch ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(branch, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                if (branch == state.branch && !state.detached) Text(stringResource(R.string.git_workflow_current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitAction(stringResource(R.string.git_workflow_switch), !state.busy && !state.merging, Modifier.weight(1f)) { actions.switchBranch(branch); dismiss() }
                    GitAction(stringResource(R.string.git_workflow_merge), !state.busy && !state.merging && !state.detached, Modifier.weight(1f)) { merge(branch) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (state.upstream.isNotBlank() && !state.detached) GitAction(stringResource(R.string.git_workflow_pull_merge), !state.busy && !state.merging, Modifier.fillMaxWidth()) { merge(state.upstream) }
    }
}

@Composable
internal fun GitCommitSheet(dismiss: () -> Unit, commit: (String, String, String) -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val messageLabel = stringResource(R.string.git_message)
    val nameLabel = stringResource(R.string.git_name)
    val emailLabel = stringResource(R.string.git_email)
    WorkbenchSheet(stringResource(R.string.git_commit), dismiss, stringResource(R.string.git_commit_help)) {
        OutlinedTextField(message, { if (it.length <= 2000) message = it }, label = { Text(messageLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = messageLabel }, minLines = 2, maxLines = 4)
        OutlinedTextField(name, { if (it.length <= 100) name = it }, label = { Text(nameLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(email, { if (it.length <= 200) email = it }, label = { Text(emailLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = emailLabel }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
        Button(onClick = { commit(message, name, email) }, enabled = message.isNotBlank() && name.isNotBlank() && '@' in email,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text(stringResource(R.string.git_commit)) }
    }
}

@Composable
internal fun GitMergeSheet(branch: String, dismiss: () -> Unit, merge: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    val nameLabel = stringResource(R.string.git_name)
    val emailLabel = stringResource(R.string.git_email)
    WorkbenchSheet(stringResource(R.string.git_workflow_merge_title), dismiss, stringResource(R.string.git_workflow_merge_help, branch)) {
        Text(stringResource(R.string.git_commit_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { if (it.length <= 100) name = it }, label = { Text(nameLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel }, singleLine = true)
        OutlinedTextField(email, { if (it.length <= 200) email = it }, label = { Text(emailLabel) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = emailLabel }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done))
        GitAction(stringResource(R.string.git_workflow_merge), name.isNotBlank() && '@' in email, Modifier.fillMaxWidth()) { merge(name, email) }
    }
}

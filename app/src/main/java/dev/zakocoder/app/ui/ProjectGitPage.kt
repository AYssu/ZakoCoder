package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench

@Composable
internal fun ProjectGitPage(workbench: Workbench, projectId: String, close: () -> Unit, onCredentials: ((String) -> Unit)? = null) {
    val state by workbench.git.state.collectAsStateWithLifecycle()
    var credentialUrl by remember(projectId) { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId, state.busy) {
        if (!state.busy && state.projectId != projectId) workbench.git.select(projectId)
    }
    if (state.projectId != projectId) {
        AiFullScreen(stringResource(R.string.git_title), close) {
            Text(stringResource(R.string.project_other_busy), Modifier.padding(16.dp))
        }
        return
    }
    GitWorkflowContent(state, close, GitWorkflowActions(
        refresh = workbench.git::refresh,
        initialize = workbench.git::initialize,
        stage = workbench.git::stage,
        unstage = workbench.git::unstage,
        commit = workbench.git::commit,
        fetch = { workbench.git.fetch(it) },
        pull = { remote, merge -> workbench.git.pull(remote, merge) },
        push = { remote, upstream -> workbench.git.push(remote, upstream) },
        createBranch = workbench.git::createBranch,
        switchBranch = workbench.git::switchBranch,
        mergeBranch = { branch, name, email -> workbench.git.mergeBranch(branch, name, email) },
        resolve = workbench.git::resolve,
        abortMerge = workbench.git::abortMerge,
        stop = workbench.git::stop,
        openFile = { path -> workbench.editor.open(projectId, path); close() },
        credentials = { url -> if (onCredentials != null) onCredentials(url) else credentialUrl = url }
    ))
    credentialUrl?.let { url -> GitCredentialsSheet(projectId, url, workbench.gitCredentials, close = { credentialUrl = null }) }
}

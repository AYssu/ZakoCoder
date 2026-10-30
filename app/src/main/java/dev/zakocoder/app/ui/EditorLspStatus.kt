package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.zakocoder.app.ai.AiLogRedactor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.lsp.*

@Composable
internal fun EditorLspStatus(controller: EditorLspController, projectId: String, path: String,
    onInstall: (LspServer) -> Unit, onRetry: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    EditorLspStatusContent(state, projectId, path, onInstall, onRetry)
}

/** Stateless rendering is shared with device UI tests; it never creates a process or starts IO. */
@Composable
internal fun EditorLspStatusContent(state: EditorLspState, projectId: String, path: String,
    onInstall: (LspServer) -> Unit, onRetry: () -> Unit,
) {
    var expanded by remember(projectId, path) { mutableStateOf(false) }
    var installing by remember(projectId, path) { mutableStateOf(false) }
    var scriptExpanded by remember(projectId, path) { mutableStateOf(false) }
    var diagnosticsExpanded by remember(projectId, path) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val server = LspServer.forPath(path)
    val current = if (server == null) state.copy(phase = LspPhase.Unsupported, server = null, diagnosticCount = 0, code = "")
        else if (state.projectId != projectId || state.path != path || state.server != null && state.server != server) EditorLspState(LspPhase.Idle, server) else state
    val localDiagnostics = remember(current.stderr) { AiLogRedactor("").clean(current.stderr).takeLast(4000) }
    val label = lspStatusLabel(current)
    val description = stringResource(R.string.editor_lsp_status_description, label)
    TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (current.phase == LspPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    if (expanded) WorkbenchSheet(stringResource(R.string.editor_lsp_title), { expanded = false }) {
        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.editor_lsp_file), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                server?.let {
                    Text(stringResource(R.string.editor_lsp_server), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(lspServerLabel(it)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(lspPhaseHelp(current.phase)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current.phase == LspPhase.Checking || current.phase == LspPhase.Starting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (current.phase == LspPhase.Ready) {
            Text(stringResource(R.string.editor_lsp_diagnostic_count, current.diagnosticCount), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.editor_lsp_diagnostic_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current.code.isNotBlank()) Text(stringResource(R.string.editor_lsp_failure_code, current.code.take(80)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (current.phase == LspPhase.Failed && localDiagnostics.isNotBlank()) {
            TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                Text(stringResource(if (diagnosticsExpanded) R.string.editor_lsp_hide_diagnostics else R.string.editor_lsp_show_diagnostics))
            }
            if (diagnosticsExpanded) {
                Text(stringResource(R.string.editor_lsp_local_diagnostics_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    SelectionContainer {
                        Text(localDiagnostics, Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(localDiagnostics)) }) { Text(stringResource(R.string.editor_lsp_copy_diagnostics)) }
            }
        }
        if (current.phase == LspPhase.MissingServer && server != null) {
            Button(onClick = { installing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.editor_lsp_install)) }
        }
        if (current.phase in setOf(LspPhase.Idle, LspPhase.MissingServer, LspPhase.Failed, LspPhase.RuntimeUnavailable)) {
            OutlinedButton(onClick = { expanded = false; onRetry() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.editor_lsp_retry)) }
        }
    }
    if (installing && server != null) AlertDialog(onDismissRequest = { installing = false },
        title = { Text(stringResource(R.string.editor_lsp_install_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(lspServerLabel(server)), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.editor_lsp_install_network), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (server == LspServer.Python) R.string.editor_lsp_python_dependencies else R.string.editor_lsp_typescript_dependencies), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.editor_lsp_install_location, server.executable), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                TextButton(onClick = { scriptExpanded = !scriptExpanded }) { Text(stringResource(if (scriptExpanded) R.string.editor_lsp_hide_script else R.string.editor_lsp_show_script)) }
                if (scriptExpanded) SelectionContainer { Text(server.installScript, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }, confirmButton = { TextButton(onClick = { installing = false; expanded = false; onInstall(server) }) { Text(stringResource(R.string.editor_lsp_confirm_install)) } },
        dismissButton = { TextButton(onClick = { installing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun lspStatusLabel(state: EditorLspState): String {
    if (state.phase == LspPhase.Ready && state.diagnosticCount > 0) return stringResource(R.string.editor_lsp_diagnostics, state.diagnosticCount)
    return stringResource(when (state.phase) {
        LspPhase.Idle -> R.string.editor_lsp_idle
        LspPhase.Unsupported -> R.string.editor_lsp_unsupported
        LspPhase.RuntimeUnavailable -> R.string.editor_lsp_runtime
        LspPhase.Checking -> R.string.editor_lsp_checking
        LspPhase.MissingServer -> R.string.editor_lsp_missing
        LspPhase.Starting -> R.string.editor_lsp_starting
        LspPhase.Ready -> R.string.editor_lsp_ready
        LspPhase.Failed -> R.string.editor_lsp_failed
    })
}
private fun lspServerLabel(server: LspServer) = if (server == LspServer.Python) R.string.editor_lsp_python else R.string.editor_lsp_typescript
private fun lspPhaseHelp(phase: LspPhase) = when (phase) {
    LspPhase.Idle -> R.string.editor_lsp_idle_help
    LspPhase.Unsupported -> R.string.editor_lsp_unsupported_help
    LspPhase.RuntimeUnavailable -> R.string.editor_lsp_runtime_help
    LspPhase.Checking -> R.string.editor_lsp_checking_help
    LspPhase.MissingServer -> R.string.editor_lsp_missing_help
    LspPhase.Starting -> R.string.editor_lsp_starting_help
    LspPhase.Ready -> R.string.editor_lsp_ready_help
    LspPhase.Failed -> R.string.editor_lsp_failed_help
}

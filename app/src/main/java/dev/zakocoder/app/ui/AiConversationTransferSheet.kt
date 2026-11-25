package dev.zakocoder.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF operations belong to the controller; this sheet only owns explicit user choices. */
@Composable
internal fun AiConversationTransferSheet(ai: AiController, state: AiState, action: AiSlashCommandId,
    workspaceName: String, onDismiss: () -> Unit) {
    val importing = action == AiSlashCommandId.Import
    val recap = action == AiSlashCommandId.Recap
    val projectId = state.projectId
    val conversationId = state.conversationId
    val scope = rememberCoroutineScope()
    var exportToken by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(exportToken != null) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var document by remember { mutableStateOf<TransferDocument?>(null) }
    var preview by remember { mutableStateOf("") }
    val current by ai.state.collectAsStateWithLifecycle()
    val ready = projectId != null && current.projectId == projectId && current.conversationId == conversationId &&
        !current.active && !current.restoring && !current.permissionsLoading && !current.permissionsSaving && !current.historyError

    fun finishExport(uri: android.net.Uri?) {
        val token = exportToken
        if (token == null) {
            busy = false
            if (uri != null) feedback = R.string.conversation_transfer_failed
            return
        }
        scope.launch {
            try {
                if (uri == null) {
                    ai.discardConversationExport(token)
                    exportToken = null
                } else {
                    ai.writePreparedConversationDocument(uri, token)
                    exportToken = null
                    feedback = R.string.conversation_saved
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // This sheet has no retry action that owns the token, so discard the retained snapshot now.
                try { ai.discardConversationExport(token) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The bounded cache expires even if provider cleanup fails. */ }
                exportToken = null
                feedback = transferFailure(error)
            }
            finally { busy = false }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::finishExport)
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::finishExport)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false
        else scope.launch {
            try {
                val loaded = ai.previewConversationImport(uri)
                val text = withContext(Dispatchers.Default) { AiConversationTransfer.markdown(loaded).take(4000) }
                document = loaded
                preview = text
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = transferFailure(error) }
            finally { busy = false }
        }
    }
    fun export(format: AiConversationTransfer.Format) {
        if (!ready || busy) return
        busy = true; feedback = null
        scope.launch {
            try {
                val content = ai.exportConversation(projectId, conversationId, format)
                exportToken = ai.prepareConversationExport(content)
                val suffix = if (format == AiConversationTransfer.Format.Summary) "handoff" else "conversation"
                val name = "ZakoCoder-$suffix-${conversationId.take(8)}"
                if (format == AiConversationTransfer.Format.Json) saveJson.launch("$name.json") else save.launch("$name.md")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                exportToken?.let { ai.discardConversationExport(it) }
                exportToken = null; busy = false; feedback = transferFailure(error)
            }
        }
    }
    WorkbenchSheet(stringResource(when {
        importing -> R.string.conversation_import
        recap -> R.string.conversation_recap
        else -> R.string.conversation_export
    }), onDismiss) {
        val dismiss = LocalWorkbenchSheetDismiss.current
        Text(stringResource(when {
            importing -> R.string.conversation_import_hint
            recap -> R.string.conversation_recap_hint
            else -> R.string.conversation_export_hint
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) {
            WorkbenchLinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.conversation_transfer_busy), style = MaterialTheme.typography.labelSmall)
        }
        feedback?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
            color = if (it == R.string.conversation_saved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
        if (importing) {
            Text(stringResource(R.string.conversation_import_target, workspaceName), style = MaterialTheme.typography.bodyMedium)
            WorkbenchSecondaryButton(onClick = {
                busy = true; feedback = null; document = null; preview = ""
                try { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                catch (_: Exception) { busy = false; feedback = R.string.conversation_transfer_failed }
            }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (document == null) R.string.conversation_choose_file else R.string.conversation_choose_again))
            }
            document?.let { loaded ->
                Text(loaded.title, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.conversation_preview), style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(preview, Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.conversation_preview_partial), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.conversation_import_confirm_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkbenchPrimaryButton(onClick = {
                    if (projectId != null) {
                        busy = true; feedback = null
                        scope.launch {
                            try { if (ai.importConversation(projectId, loaded)) dismiss() else feedback = R.string.conversation_import_failed }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { feedback = transferFailure(error) }
                            finally { busy = false }
                        }
                    }
                }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.conversation_import_confirm))
                }
            }
        } else if (recap) {
            WorkbenchPrimaryButton(onClick = { export(AiConversationTransfer.Format.Summary) }, enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.conversation_save_recap)) }
        } else {
            TransferFormatRow(R.string.conversation_export_markdown, R.string.conversation_export_markdown_hint, ready && !busy) {
                export(AiConversationTransfer.Format.Markdown)
            }
            TransferFormatRow(R.string.conversation_export_json, R.string.conversation_export_json_hint, ready && !busy) {
                export(AiConversationTransfer.Format.Json)
            }
        }
    }
}

@Composable
private fun TransferFormatRow(title: Int, hint: Int, enabled: Boolean, onClick: () -> Unit) {
    WorkbenchSecondaryButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun transferFailure(error: Exception): Int = when {
    error is AiConversationTransferException -> when (error.reason) {
        AiConversationTransferProblem.TooLarge -> R.string.conversation_transfer_large
        AiConversationTransferProblem.UnsupportedVersion -> R.string.conversation_transfer_version
        else -> R.string.conversation_transfer_invalid
    }
    else -> R.string.conversation_transfer_failed
}

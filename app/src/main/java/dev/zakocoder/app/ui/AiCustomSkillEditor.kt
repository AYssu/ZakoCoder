package dev.zakocoder.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiController
import dev.zakocoder.app.ai.AiSkillArchive
import dev.zakocoder.app.ai.AiSkills
import dev.zakocoder.app.ai.AiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Project-scoped forms; opening/importing never enables a skill or contacts a model. */
@Composable
internal fun AiCustomSkillActions(state: AiState, controller: AiController) {
    val scope = rememberCoroutineScope()
    val owner = "${state.projectId}:${state.conversationId}"
    val current by rememberUpdatedState(state)
    var opened by rememberSaveable(owner) { mutableStateOf(false) }
    var imported by rememberSaveable(owner) { mutableStateOf(false) }
    var name by rememberSaveable(owner) { mutableStateOf("") }
    var description by rememberSaveable(owner) { mutableStateOf("") }
    var body by rememberSaveable(owner) { mutableStateOf("") }
    var archive by remember(owner) { mutableStateOf<ByteArray?>(null) }
    var archiveNames by remember(owner) { mutableStateOf<List<String>>(emptyList()) }
    var importedZip by rememberSaveable(owner) { mutableStateOf(false) }
    var archiveUri by rememberSaveable(owner) { mutableStateOf("") }
    var archiveDigest by rememberSaveable(owner) { mutableStateOf("") }
    var reading by remember(owner) { mutableStateOf(false) }
    var saving by rememberSaveable(owner) { mutableStateOf(false) }
    var operationId by rememberSaveable(owner) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val busy = reading || saving
    var error by remember(owner) { mutableIntStateOf(0) }
    var saved by remember(owner) { mutableStateOf(false) }
    var importOwner by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val editable = state.projectId != null && !state.active && !state.restoring && !state.permissionsLoading &&
        !state.permissionsSaving && !state.skillsLoading && !state.historyError && !busy
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requested = importOwner
        importOwner = null
        if (uri != null && requested == "${current.projectId}:${current.conversationId}" && !current.active && !current.restoring) {
            reading = true; error = 0; saved = false
            scope.launch {
                try {
                    val bytes = controller.readSkillPackage(uri)
                    val zipped = AiSkillArchive.isZip(bytes)
                    val names = if (zipped) controller.inspectSkillArchive(bytes) else emptyList()
                    val digest = if (zipped) controller.skillPackageDigest(bytes) else ""
                    val raw = if (zipped) "" else {
                        require(bytes.size <= AiSkills.MAX_CUSTOM_BYTES)
                        AiSkillArchive.decode(bytes).removePrefix("\uFEFF").also { require(it.length <= AiSkills.MAX_CUSTOM_CHARS && '\u0000' !in it) }
                    }
                    if (requested == "${current.projectId}:${current.conversationId}" && !current.active && !current.restoring) {
                        body = raw; archive = if (zipped) bytes else null; archiveNames = names
                        if (!zipped || archiveDigest != digest) operationId = java.util.UUID.randomUUID().toString()
                        archiveUri = if (zipped) uri.toString() else ""
                        archiveDigest = digest
                        importedZip = zipped; imported = true; opened = true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (requested == "${current.projectId}:${current.conversationId}") error = R.string.ai_skill_custom_read_error }
                finally { reading = false }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { imported = false; importedZip = false; archive = null; opened = true; error = 0; saved = false }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ai_skill_add_custom))
        }
        TextButton(onClick = {
            importOwner = owner
            launcher.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream", "application/zip", "application/x-zip-compressed"))
        }, enabled = editable) { Text(stringResource(R.string.ai_skill_import_custom)) }
        if (state.projectId == null) Text(stringResource(R.string.ai_skill_custom_need_project), style = MaterialTheme.typography.bodySmall)
        if (busy && !opened) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != 0 && !opened) Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (saved) Text(stringResource(R.string.ai_skill_custom_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
    // Reopen the same SAF document after Activity recreation, verifying its exact bytes before
    // resuming a stable save. Binary data is never placed in the saved-instance-state Bundle.
    LaunchedEffect(owner, importedZip) {
        if (!importedZip || archive != null) return@LaunchedEffect
        reading = true
        try {
            require(archiveUri.isNotEmpty() && archiveDigest.isNotEmpty())
            val bytes = controller.readSkillPackage(archiveUri.toUri())
            require(controller.skillPackageDigest(bytes) == archiveDigest)
            val names = controller.inspectSkillArchive(bytes)
            archive = bytes; archiveNames = names
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            opened = false; saving = false; importedZip = false
            error = R.string.ai_skill_custom_read_error
        } finally { reading = false }
    }
    // A recreated Activity resumes the same operation. The stable on-disk target makes a
    // retry idempotent even if the previous Activity stopped observing a successful save.
    LaunchedEffect(owner, saving, operationId, archive) {
        if (!saving || (importedZip && archive == null)) return@LaunchedEffect
        val project = state.projectId
        if (project == null) { saving = false; return@LaunchedEffect }
        try {
            if (controller.saveProjectSkill(project, state.conversationId, name, description, body, imported, operationId, archive)) {
                opened = false; saved = true; body = ""; name = ""; description = ""
                archive = null; archiveNames = emptyList(); importedZip = false; archiveUri = ""; archiveDigest = ""
                operationId = java.util.UUID.randomUUID().toString()
            } else error = R.string.ai_skill_custom_error
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.ai_skill_custom_error }
        saving = false
    }
    if (opened) AiFullScreen(stringResource(if (imported) R.string.ai_skill_import_custom else R.string.ai_skill_add_custom), { if (!busy) opened = false }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.ai_skill_custom_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!imported) {
                    OutlinedTextField(name, { if (it.length <= 100) name = it }, label = { Text(stringResource(R.string.ai_skill_custom_name)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { if (it.length <= 500) description = it }, label = { Text(stringResource(R.string.ai_skill_custom_description)) }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth())
                } else Text(stringResource(if (importedZip) R.string.ai_skill_custom_archive_hint else R.string.ai_skill_custom_import_hint), style = MaterialTheme.typography.bodySmall)
                if (importedZip) {
                    Text(stringResource(R.string.ai_skill_custom_archive_count, archiveNames.size), style = MaterialTheme.typography.titleSmall)
                    archiveNames.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                } else OutlinedTextField(body, { if (it.length <= AiSkills.MAX_CUSTOM_CHARS) body = it }, label = {
                    Text(stringResource(if (imported) R.string.ai_skill_custom_document else R.string.ai_skill_custom_body))
                }, minLines = 8, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            Button(onClick = {
                saving = true; error = 0
            }, enabled = editable && (if (importedZip) archive != null else body.isNotBlank() && (imported || (name.isNotBlank() && description.isNotBlank()))), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(if (busy) R.string.editor_saving else R.string.editor_save))
            }
        }
    }
}

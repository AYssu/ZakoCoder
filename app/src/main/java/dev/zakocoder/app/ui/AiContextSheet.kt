package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

@Composable
internal fun AiContextSheet(ai: AiController, state: AiState, onImages: (() -> Unit)? = null, close: () -> Unit) {
    var directory by remember(state.projectId) { mutableStateOf("") }
    var directoryRetry by remember(state.projectId, directory) { mutableIntStateOf(0) }
    val listing = rememberDirectoryListing(state.projectId, directory, directoryRetry) { ai.referenceFiles(directory) }
    val entries = listing.entries
    val loading = listing.loading
    val failed = listing.failure != null
    var errorText by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    WorkbenchSheet(stringResource(R.string.ai_context_attach), close) {
        if (onImages != null) {
            OutlinedButton(onClick = onImages, enabled = !state.active && !state.imagesLoading && state.pendingImages.size < AiImages.MAX_PER_TURN,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_images_add)) }
            Text(stringResource(R.string.ai_images_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.ai_context_attach_help), style = MaterialTheme.typography.bodySmall)
        Row { TextButton(onClick = { tab = 0 }) { Text(stringResource(R.string.browse_files)) }; TextButton(onClick = { tab = 1 }) { Text(stringResource(R.string.ai_reference_error_log)) } }
        if (tab == 1) {
            OutlinedTextField(errorText, { if (it.length <= 6000) errorText = it }, label = { Text(stringResource(R.string.ai_reference_error_log)) }, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
            TextButton(onClick = { ai.addReference(AiReference("build-output", errorText, "error")); close() }, enabled = errorText.isNotBlank() && state.references.size < 4 && !state.active) { Text(stringResource(R.string.ai_context_add)) }
        } else {
            Text(directory.ifEmpty { stringResource(R.string.project_root) }, style = MaterialTheme.typography.bodySmall)
            if (directory.isNotEmpty()) TextButton(onClick = { directory = directory.substringBeforeLast('/', "") }) { Text(stringResource(R.string.editor_back)) }
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                failed -> {
                    Text(stringResource(R.string.directory_read_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { directoryRetry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.directory_retry))
                    }
                }
                entries.isEmpty() -> Text(stringResource(R.string.directory_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(entries, key = { it.first }) { (path, isDirectory) ->
                    ListItem(headlineContent = { Text(path.substringAfterLast('/')) }, leadingContent = { WorkbenchIcon(if (isDirectory) Glyph.Projects else Glyph.File) }, modifier = Modifier.clickable(enabled = !state.active && state.references.size < 4) {
                        if (isDirectory) directory = path else { ai.addFileReference(path); close() }
                    })
                }
            }
        }
        if (state.ruleSources.isNotEmpty()) Text(stringResource(R.string.ai_rules_sources, state.ruleSources.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        if (state.policy.permission(AiTool.ReadFile) != AiPermission.Allow) Text(stringResource(R.string.ai_rules_permission_unavailable), style = MaterialTheme.typography.bodySmall)
        if (state.rulesLimited) Text(stringResource(R.string.ai_rules_limited), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

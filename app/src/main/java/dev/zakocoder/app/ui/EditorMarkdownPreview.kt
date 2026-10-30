package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

/** Renders the current editor buffer as native text; no HTML engine or remote image loads. */
@Composable
internal fun EditorMarkdownPreview(text: String, modifier: Modifier = Modifier) {
    var link by remember { mutableStateOf<String?>(null) }
    val handler = LocalUriHandler.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.editor_preview_buffer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (text.isEmpty()) Text(stringResource(R.string.editor_preview_empty), style = MaterialTheme.typography.bodyMedium)
        else AiMarkdown(text, onLink = { link = it })
    }
    link?.let { destination ->
        AlertDialog(onDismissRequest = { link = null }, title = { Text(stringResource(R.string.ai_open_link)) },
            text = { Text(stringResource(R.string.ai_open_link_detail, destination)) },
            confirmButton = { TextButton(onClick = {
                try { handler.openUri(destination) } catch (_: android.content.ActivityNotFoundException) { }
                finally { link = null }
            }) { Text(stringResource(R.string.ai_open_browser)) } },
            dismissButton = { TextButton(onClick = { link = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

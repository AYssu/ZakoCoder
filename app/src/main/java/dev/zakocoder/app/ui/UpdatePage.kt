package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.runtime.StorageUsage
import dev.zakocoder.app.updates.*

@Composable
internal fun UpdateBindings(updates: AppUpdates, onOpen: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, updates) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) updates.resumed() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (state.prompt) AlertDialog(onDismissRequest = updates::dismissPrompt,
        title = { Text(stringResource(R.string.update_available, state.release?.tag.orEmpty())) },
        text = { Text(stringResource(R.string.update_security)) },
        confirmButton = { TextButton(onClick = { updates.dismissPrompt(); onOpen() }) { Text(stringResource(R.string.update_view)) } },
        dismissButton = { TextButton(onClick = updates::dismissPrompt) { Text(stringResource(R.string.update_later)) } })
}

@Composable
internal fun UpdatePage(updates: AppUpdates, onBack: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    AiFullScreen(stringResource(R.string.update_title), onBack) {
        UpdateContent(state, updates::configure, { updates.check() }, updates::download, updates::cancelDownload, updates::install)
    }
}

@Composable
internal fun UpdateContent(state: UpdateState, configure: (Boolean, UpdateChannel) -> Unit, check: () -> Unit,
    download: () -> Unit, cancel: () -> Unit, install: () -> Unit) {
    val busy = state.phase == UpdatePhase.Checking || state.phase == UpdatePhase.Downloading
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.update_current, state.currentVersion), style = MaterialTheme.typography.titleMedium)
        DetailGroup(stringResource(R.string.update_title)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.update_automatic), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.update_automatic_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.automatic, enabled = state.initialized && !busy, onCheckedChange = { configure(it, state.channel) })
            }
            GroupDivider()
            Text(stringResource(R.string.update_channel), Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge)
            UpdateChannel.entries.forEach { channel ->
                Row(Modifier.fillMaxWidth().clickable(enabled = state.initialized && !busy, role = Role.RadioButton) {
                    configure(state.automatic, channel)
                }.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.channel == channel, onClick = null, enabled = state.initialized && !busy)
                    Text(stringResource(if (channel == UpdateChannel.Stable) R.string.update_stable else R.string.update_beta))
                }
            }
        }
        if (state.phase != UpdatePhase.Downloading && state.phase != UpdatePhase.Ready) OutlinedButton(onClick = check,
            enabled = state.initialized && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (state.phase == UpdatePhase.Checking) R.string.update_checking else R.string.update_check))
        }
        if (state.phase == UpdatePhase.Checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.phase == UpdatePhase.Current) Text(stringResource(R.string.update_latest), style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        state.release?.let { release ->
            Text(stringResource(R.string.update_available, release.tag), style = MaterialTheme.typography.titleMedium)
            if (state.phase == UpdatePhase.Downloading) {
                LinearProgressIndicator(progress = { if (state.total > 0) (state.received.toFloat() / state.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_downloading, StorageUsage.format(state.received), StorageUsage.format(state.total)), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            } else if (state.phase == UpdatePhase.Ready) {
                Text(stringResource(R.string.update_ready), style = MaterialTheme.typography.bodySmall)
                Button(onClick = install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.update_install)) }
            } else Button(onClick = download, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_download, StorageUsage.format(release.apk.size)))
            }
            Text(stringResource(R.string.update_notes), style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(release.notes.ifBlank { stringResource(R.string.update_notes_empty) }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.update_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
}

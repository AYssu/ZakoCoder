package dev.zakocoder.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.memory.AgentMemory
import dev.zakocoder.app.memory.MemoryController
import dev.zakocoder.app.memory.MemoryFailure
import dev.zakocoder.app.memory.MemoryPolicy
import java.util.Date
import kotlinx.coroutines.launch

/** Installation-wide settings; deliberately has no workspace parameter. */
@Composable
internal fun AgentMemoryPage(controller: MemoryController, close: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val back = rememberWorkbenchBackMotion(onBack = close)
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AgentMemory?>(null) }
    var info by rememberSaveable { mutableStateOf(false) }
    val editable = state.loaded && !state.busy && !state.loading
    val saved = state.entries.filterNot { it.pending }
    val proposals = remember(state.entries, state.query) { MemoryPolicy.search(state.proposals, state.query) }
    val visible = when (filter) {
        1 -> state.visible.filter { it.core }
        2 -> proposals
        else -> state.visible
    }
    LaunchedEffect(controller) { controller.load() }
    Column(Modifier.fillMaxSize().then(back.modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back.close, modifier = Modifier.size(48.dp)) {
                WorkbenchIcon(Glyph.Back, description = stringResource(R.string.agent_memory_back))
            }
            Text(stringResource(R.string.agent_memory_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = rememberWorkbenchClickFeedback { info = true }) {
                WorkbenchIcon(Glyph.Info, description = stringResource(R.string.agent_memory_about))
            }
            IconButton(onClick = rememberWorkbenchClickFeedback { adding = true; controller.clearError() }, enabled = editable) {
                WorkbenchIcon(Glyph.Add, description = stringResource(R.string.agent_memory_add))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_memory_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            WorkbenchTextField(state.query, controller::search, Modifier.fillMaxWidth(),
                accessibilityLabel = stringResource(R.string.agent_memory_search),
                placeholder = { Text(stringResource(R.string.agent_memory_search)) },
                leadingIcon = { WorkbenchIcon(Glyph.Search, description = null) },
                trailingIcon = if (state.query.isNotEmpty()) ({
                    IconButton(onClick = rememberWorkbenchClickFeedback { controller.search("") }) {
                        WorkbenchIcon(Glyph.Close, description = stringResource(R.string.agent_memory_clear_search))
                    }
                }) else null, singleLine = true, enabled = state.loaded)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.agent_memory_filter_all to saved.size,
                    R.string.agent_memory_filter_core to saved.count { it.core },
                    R.string.agent_memory_filter_pending to state.proposals.size).forEachIndexed { index, (label, count) ->
                    Column(Modifier.weight(1f)) {
                        TextButton(onClick = rememberWorkbenchClickFeedback { filter = index },
                            modifier = Modifier.fillMaxWidth().semantics { selected = filter == index }) {
                            Text(stringResource(label, count), maxLines = 1, style = MaterialTheme.typography.labelMedium,
                                color = if (filter == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(
                            if (filter == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                    }
                }
            }
        }
        state.error?.let { error -> Box(Modifier.padding(horizontal = 16.dp)) { MemoryError(error) { controller.load() } } }
        // Reserve the loading strip, so local searches never move the list down and back up.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.loading || state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filter == 2 && state.proposals.isNotEmpty()) item("pending-hint") {
                Text(stringResource(R.string.agent_memory_pending_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.loading && !state.searching && state.loaded && visible.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkbenchIcon(if (state.query.isNotBlank()) Glyph.Search else Glyph.File, description = null, modifier = Modifier.size(32.dp))
                    Text(stringResource(when {
                        state.query.isNotBlank() -> R.string.agent_memory_no_results
                        filter == 1 -> R.string.agent_memory_core_empty
                        filter == 2 -> R.string.agent_memory_pending_empty
                        else -> R.string.agent_memory_empty
                    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { it.id }) { entry ->
                MemoryCard(entry, editable, open = { detailId = entry.id },
                    onEnabled = { controller.setEnabled(entry.id, it) })
            }
        }
    }
    state.entries.firstOrNull { it.id == detailId }?.let { entry ->
        MemoryDetail(entry, editable, error = state.error, close = { detailId = null }, edit = {
            detailId = null; editorId = entry.id; controller.clearError()
        }, remove = { deleting = entry }, accept = { scope.launch {
            controller.acceptProposal(entry.id).join()
            if (controller.state.value.error == null) detailId = null
        } })
    }
    if (adding || editorId != null) MemoryEditor(controller, state.entries.firstOrNull { it.id == editorId }) {
        adding = false; editorId = null
    }
    if (info) WorkbenchAlertDialog(onDismissRequest = { info = false },
        title = { Text(stringResource(R.string.agent_memory_about)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.agent_memory_intro))
            Text(stringResource(R.string.agent_memory_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { info = false }) { Text(stringResource(R.string.close)) } })
    deleting?.let { entry ->
        val scope = rememberCoroutineScope()
        WorkbenchAlertDialog(onDismissRequest = { if (!state.busy) deleting = null },
            title = { Text(stringResource(R.string.agent_memory_delete_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.agent_memory_delete_hint, entry.title))
                state.error?.let { MemoryError(it) }
            } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { scope.launch {
                controller.remove(entry.id).join()
                if (controller.state.value.error == null) { deleting = null; detailId = null }
            } }) { Text(stringResource(if (entry.pending) R.string.agent_memory_dismiss else R.string.agent_memory_delete),
                color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun MemoryCard(entry: AgentMemory, editable: Boolean, open: () -> Unit, onEnabled: (Boolean) -> Unit) {
    WorkbenchCard(modifier = Modifier.fillMaxWidth(), onClick = open) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.core) Text(stringResource(R.string.agent_memory_core_badge), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (!entry.pending) {
                    val enabledDescription = stringResource(R.string.agent_memory_enable_label, entry.title)
                    WorkbenchSwitch(entry.enabled, onEnabled, enabled = editable,
                        modifier = Modifier.semantics { contentDescription = enabledDescription })
                } else WorkbenchIcon(Glyph.Next, description = stringResource(R.string.agent_memory_review), modifier = Modifier.size(18.dp))
            }
            Text(entry.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(memorySource(entry), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (!entry.enabled && !entry.pending) stringResource(R.string.agent_memory_disabled)
                    else DateFormat.format("MM-dd", Date(entry.updatedAt)).toString(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun memorySource(entry: AgentMemory): String = when {
    entry.source == "user" -> stringResource(R.string.agent_memory_user_source)
    entry.source.startsWith("conversation:") -> stringResource(R.string.agent_memory_assistant_source)
    else -> entry.source
}

@Composable
private fun MemoryDetail(entry: AgentMemory, editable: Boolean, error: MemoryFailure?, close: () -> Unit, edit: () -> Unit,
    remove: () -> Unit, accept: () -> Unit) {
    AiFullScreen(stringResource(R.string.agent_memory_details), close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.agent_memory_source_updated, memorySource(entry),
                DateFormat.format("yyyy-MM-dd HH:mm", Date(entry.updatedAt)).toString()),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.pending) Text(stringResource(R.string.agent_memory_pending_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            if (entry.core) Text(stringResource(R.string.agent_memory_core_badge), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(entry.content, style = MaterialTheme.typography.bodyMedium) }
            error?.let { MemoryError(it) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = editable, onClick = rememberWorkbenchClickFeedback(remove)) {
                Text(stringResource(if (entry.pending) R.string.agent_memory_dismiss else R.string.agent_memory_delete),
                    color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = editable, onClick = rememberWorkbenchClickFeedback(edit)) { Text(stringResource(R.string.agent_memory_edit)) }
            if (entry.pending) WorkbenchPrimaryButton(enabled = editable, onClick = accept) {
                Text(stringResource(R.string.agent_memory_confirm_save))
            }
        }
    }
}

@Composable
private fun MemoryEditor(controller: MemoryController, entry: AgentMemory?, close: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(entry?.id) { mutableStateOf(entry?.title.orEmpty()) }
    var content by rememberSaveable(entry?.id) { mutableStateOf(entry?.content.orEmpty()) }
    var core by rememberSaveable(entry?.id) { mutableStateOf(entry?.core ?: false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val dirty = title != entry?.title.orEmpty() || content != entry?.content.orEmpty() || core != (entry?.core ?: false)
    val valid = title.isNotBlank() && content.isNotBlank() && title.length <= MemoryPolicy.MAX_TITLE && content.length <= MemoryPolicy.MAX_CONTENT
    AiFullScreen(stringResource(if (entry == null) R.string.agent_memory_add else R.string.agent_memory_edit), {
        if (!state.busy) { if (dirty) discard = true else close() }
    }, animateExit = !dirty && !state.busy) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WorkbenchTextField(title, { title = it.take(MemoryPolicy.MAX_TITLE) }, Modifier.fillMaxWidth(),
                label = stringResource(R.string.agent_memory_name), singleLine = true, enabled = !state.busy)
            WorkbenchTextField(content, { content = it.take(MemoryPolicy.MAX_CONTENT) }, Modifier.fillMaxWidth(),
                label = stringResource(R.string.agent_memory_content), minLines = 5, maxLines = 12, enabled = !state.busy,
                supportingText = { Text(stringResource(R.string.agent_memory_content_count, content.length, MemoryPolicy.MAX_CONTENT)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.agent_memory_core), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.agent_memory_core_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkbenchSwitch(core, { core = it }, enabled = !state.busy)
            }
            Text(stringResource(R.string.agent_memory_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { MemoryError(it) }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            WorkbenchPrimaryButton(enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = { scope.launch {
                    (if (entry == null) controller.add(title, content, core) else controller.update(entry.id, title, content, core)).join()
                    if (controller.state.value.error == null) close()
                } }) { Text(stringResource(R.string.agent_memory_save)) }
        }
    }
    if (discard) WorkbenchAlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.agent_memory_discard_title)) },
        text = { Text(stringResource(R.string.agent_memory_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; close() }) { Text(stringResource(R.string.agent_memory_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun MemoryError(error: MemoryFailure, retry: (() -> Unit)? = null) {
    WorkbenchCard(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (error) {
                MemoryFailure.Corrupt -> R.string.agent_memory_error_corrupt
                MemoryFailure.UnsupportedVersion -> R.string.agent_memory_error_version
                MemoryFailure.InvalidEntry -> R.string.agent_memory_error_invalid
                MemoryFailure.Capacity -> R.string.agent_memory_error_capacity
                MemoryFailure.ReadWrite -> R.string.agent_memory_error_io
            }), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.agent_memory_reload)) }
        }
    }
}

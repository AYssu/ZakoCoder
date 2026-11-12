package dev.zakocoder.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.AiConversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class DrawerThreads(val project: String?, val conversation: String, val conversations: List<AiConversation>,
    val locked: Boolean, val loading: Boolean, val historyError: Boolean)

/** Only the conversation region scrolls; utility navigation belongs to the fixed drawer footer. */
@Composable
internal fun ConversationDrawer(workbench: Workbench, state: WorkbenchState, visible: Boolean, busy: Boolean,
    error: Boolean, onManage: () -> Unit, onOpen: (String, String?) -> Unit) {
    val threads by remember(workbench) {
        workbench.ai.state.map { DrawerThreads(it.projectId, it.conversationId, it.conversations,
            it.active || it.restoring || it.permissionsSaving, it.permissionsLoading, it.historyError) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(DrawerThreads(null, "", emptyList(), true, true, false))
    var expanded by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(visible, state.selectedId) {
        if (visible && !initialized && state.selectedId != null) {
            expanded = listOfNotNull(state.selectedId); initialized = true
        }
    }
    val canCreate = !busy && !state.busy && !threads.locked && !threads.loading && !threads.historyError
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { state.selectedId?.let { onOpen(it, null) } ?: onManage() }, enabled = canCreate,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
            WorkbenchIcon(Glyph.Add, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drawer_new_conversation), Modifier.weight(1f), fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drawer_conversations), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onManage, enabled = !busy) { Text(stringResource(R.string.drawer_manage_workspaces), fontSize = 12.sp) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        if (error) Text(stringResource(R.string.drawer_open_failed), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (state.projects.isEmpty()) item {
                Text(stringResource(R.string.drawer_no_workspaces), Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.projects, key = { it.id }) { project ->
                val open = project.id in expanded
                val current = threads.project == project.id
                var stored by remember(project.id) { mutableStateOf<List<AiConversation>>(emptyList()) }
                var loading by remember(project.id) { mutableStateOf(true) }
                var failed by remember(project.id) { mutableStateOf(false) }
                var retry by remember(project.id) { mutableIntStateOf(0) }
                LaunchedEffect(open, visible, current, retry) {
                    if (!open || !visible || current) return@LaunchedEffect
                    loading = true; failed = false
                    try { stored = workbench.ai.listConversations(project.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { loading = false }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = if (open) expanded - project.id else expanded + project.id }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkbenchIcon(Glyph.Projects, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(project.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    WorkbenchIcon(if (open) Glyph.ChevronDown else Glyph.Next, modifier = Modifier.size(16.dp),
                        description = stringResource(if (open) R.string.drawer_collapse_workspace else R.string.drawer_expand_workspace))
                }
                AnimatedVisibility(open) {
                    Column {
                        val isLoading = if (current) threads.loading else loading
                        val isFailed = if (current) threads.historyError else failed
                        val conversations = if (current) threads.conversations else stored
                        when {
                            isLoading -> Text(stringResource(R.string.loading), Modifier.padding(start = 52.dp, top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
                            isFailed -> TextButton(onClick = { if (current) onManage() else retry++ }, modifier = Modifier.padding(start = 40.dp)) {
                                Text(stringResource(R.string.drawer_history_retry), fontSize = 12.sp)
                            }
                            else -> {
                                conversations.forEach { conversation ->
                                    val selected = current && threads.conversation == conversation.id
                                    Text(conversation.title.ifBlank { stringResource(R.string.ai_empty_conversation) },
                                        Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else androidx.compose.ui.graphics.Color.Transparent)
                                            .semantics { this.selected = selected }
                                            .clickable(enabled = !busy && !state.busy && !threads.loading && (!threads.locked || selected)) { onOpen(project.id, conversation.id) }
                                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 14.dp),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 18.sp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onOpen(project.id, null) }, enabled = canCreate,
                                    modifier = Modifier.padding(start = 40.dp).heightIn(min = 48.dp)) {
                                    WorkbenchIcon(Glyph.Add, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.drawer_new_conversation), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

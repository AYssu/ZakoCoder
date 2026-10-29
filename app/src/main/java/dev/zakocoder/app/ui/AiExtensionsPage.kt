package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState

@Composable
internal fun AiExtensionsPage(workbench: Workbench, workbenchState: WorkbenchState, close: () -> Unit) {
    BackHandler(onBack = close)
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var projects by remember { mutableStateOf(false) }
    var conversations by remember { mutableStateOf(false) }
    val editable = !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving && !ai.historyError
    LaunchedEffect(workbenchState.selectedId) {
        if (ai.projectId == null && editable) workbench.ai.select(workbenchState.selectedId)
    }
    val project = workbenchState.projects.singleOrNull { it.id == ai.projectId }
    val conversation = ai.conversations.singleOrNull { it.id == ai.conversationId }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = close, modifier = Modifier.size(48.dp)) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.ai_extensions_back)) }
            Text(stringResource(R.string.ai_extensions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        SecondaryTabRow(selectedTabIndex = tab) {
            listOf(stringResource(R.string.ai_skills), stringResource(R.string.mcp_title)).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (tab == 0) Text(stringResource(R.string.extensions_context), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { projects = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    WorkbenchIcon(Glyph.Projects, Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(project?.name ?: stringResource(R.string.select_project), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
                }
                if (tab == 0 && project != null) TextButton(onClick = { conversations = true }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    WorkbenchIcon(Glyph.History, Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(conversation?.title ?: stringResource(R.string.ai_new_conversation), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
                }
                if (!editable && tab == 0) Text(stringResource(R.string.extensions_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!editable && tab == 1) Text(stringResource(R.string.extensions_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (tab == 0) {
            if (project == null) Text(stringResource(R.string.ai_select_project_first), Modifier.padding(16.dp))
            else AiSkillsPanel(if (ai.restoring || ai.permissionsSaving) ai.copy(active = true) else ai, workbench.ai, Modifier.weight(1f))
        } else McpManagementPanel(workbench.mcp, project?.id, editable, Modifier.weight(1f))
    }
    if (projects) WorkbenchSheet(stringResource(R.string.select_project), { projects = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(workbenchState.projects, key = { it.id }) { item -> TextButton(onClick = { workbench.ai.select(item.id); projects = false }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.name) } }
        }
    }
    if (conversations) WorkbenchSheet(stringResource(R.string.ai_history), { conversations = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            item { TextButton(onClick = { workbench.ai.conversation(); conversations = false }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_new_conversation)) } }
            items(ai.conversations, key = { it.id }) { item -> TextButton(onClick = { workbench.ai.conversation(item.id); conversations = false }, enabled = editable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        }
    }
}

package dev.zakocoder.app.ui


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.terminal.TerminalItem
import dev.zakocoder.app.ui.theme.Type

@Composable
internal fun TerminalLibraryPage(workbench: Workbench, state: WorkbenchState, onOpen: (String) -> Unit, onEnvironment: () -> Unit, onProjects: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val previews by workbench.terminal.previews.collectAsStateWithLifecycle()
    val canCreate = state.installed && state.projects.isNotEmpty() && !state.busy && state.sessions.size < 4
    val groups = state.sessions.groupBy { it.projectId }.entries.sortedBy { if (it.key == state.selectedId) 0 else 1 }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeading(stringResource(R.string.nav_terminal)) {
            if (state.sessions.isNotEmpty()) TextButton(onClick = { creating = true }, enabled = canCreate) {
                WorkbenchIcon(Glyph.Add, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.new_terminal), style = Type.body)
            } else Text(stringResource(R.string.terminal_quota, 0), style = Type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.sessions.isNotEmpty()) Text(
            stringResource(R.string.terminal_library_summary, state.sessions.size, state.sessions.count { it.session.isRunning }),
            Modifier.padding(top = 4.dp, bottom = 16.dp), style = Type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.sessions.isEmpty()) item {
                WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(Glyph.Terminal, Modifier.size(40.dp))
                        Text(stringResource(R.string.terminal_library_empty), style = Type.item)
                        Text(stringResource(R.string.terminal_library_help), style = Type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WorkbenchPrimaryButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = {
                            when {
                                !state.installed -> onEnvironment()
                                state.projects.isEmpty() -> onProjects()
                                else -> creating = true
                            }
                        }) { Text(stringResource(when {
                            !state.installed -> R.string.install_runtime
                            state.projects.isEmpty() -> R.string.drawer_manage_workspaces
                            else -> R.string.new_terminal
                        })) }
                    }
                }
            }
            groups.forEach { (projectId, sessions) ->
                item(key = "workspace-$projectId") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkbenchIcon(Glyph.Projects, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sessions.first().projectName, Modifier.weight(1f), style = Type.metaStrong,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (projectId == state.selectedId) Text(stringResource(R.string.home_current_project), style = Type.label,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(sessions, key = { it.id }) { item ->
                    TerminalSessionCard(item, previews[item.id].orEmpty()) { onOpen(item.id) }
                }
            }
            item {
                Text(stringResource(if (state.sessions.size >= 4) R.string.terminal_limit else R.string.terminal_local_lifecycle),
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = Type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (creating) NewTerminalSheet(workbench, state) { creating = false }
}

@Composable
private fun TerminalSessionCard(item: TerminalItem, preview: String, onOpen: () -> Unit) {
    val enterLabel = stringResource(R.string.enter_terminal_number, item.number)
    WorkbenchCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().semantics { contentDescription = enterLabel }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIcon(Glyph.Terminal, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.terminal_number, item.number), Modifier.weight(1f), style = Type.body)
                StatusDot(terminalStatusColor(item))
                Text(terminalStatusLabel(item), style = Type.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkbenchIcon(Glyph.Next, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(Modifier.fillMaxWidth(), color = TerminalBackground, shape = RoundedCornerShape(8.dp)) {
                Text(preview.ifBlank { stringResource(R.string.terminal_waiting_output) },
                    Modifier.heightIn(min = 80.dp).padding(12.dp), style = Type.terminal,
                    color = TerminalForeground, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun terminalStatusColor(item: TerminalItem) = when {
    item.session.isRunning -> ReadyGreen
    item.session.exitStatus != 0 -> FailureRed
    else -> TertiaryText
}

@Composable
internal fun terminalStatusLabel(item: TerminalItem): String = stringResource(when {
    item.session.isRunning -> R.string.session_running
    item.session.exitStatus != 0 -> R.string.session_failed
    else -> R.string.session_ended
})

@Composable
internal fun NewTerminalSheet(workbench: Workbench, state: WorkbenchState, onDismiss: () -> Unit) {
    var projectId by remember(state.selectedId) { mutableStateOf(state.selectedId ?: state.projects.firstOrNull()?.id) }
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .85f }
    WorkbenchPanelSheet(onDismiss = onDismiss) { requestDismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.new_terminal), Modifier.weight(1f), style = Type.section)
                IconButton(onClick = requestDismiss) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.close)) }
            }
            Text(stringResource(R.string.terminal_choose_project), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = Type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.projects.forEach { project ->
                    SelectablePanel(projectId == project.id, { projectId = project.id }) {
                        IconTile(Glyph.Projects, Modifier.size(34.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(project.name, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(project.kinds.map { stringResource(it.label) }.joinToString(stringResource(R.string.type_separator)).ifEmpty { stringResource(R.string.project_type_unset) } +
                                " · " + stringResource(R.string.project_session_count, state.sessions.count { it.projectId == project.id }),
                                style = Type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        SelectionCheck(projectId == project.id)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.terminal_shell_help), style = Type.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkbenchPrimaryButton(onClick = { onDismiss(); workbench.startTerminal(projectId) }, modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && state.installed && state.sessions.size < 4 && state.projects.any { it.id == projectId }) {
                    Text(stringResource(R.string.create_terminal))
                }
            }
        }
    }
}

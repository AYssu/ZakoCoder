package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.terminal.TerminalItem

@Composable
internal fun TerminalLibraryPage(workbench: Workbench, state: WorkbenchState, onOpen: (String) -> Unit, onEnvironment: () -> Unit, onProjects: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val previews by workbench.terminal.previews.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {

            PageHeading(stringResource(R.string.nav_terminal)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(4) { index -> Box(Modifier.size(8.dp).background(if (index < state.sessions.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape)) }
                    Text(stringResource(R.string.terminal_quota, state.sessions.size), Modifier.padding(start = 3.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = 20.dp)) {
        if (state.sessions.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                EmptyArtwork(Glyph.Terminal)
                Text(stringResource(R.string.terminal_library_empty), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.terminal_library_help), style = MaterialTheme.typography.bodySmall, color = TertiaryText, textAlign = TextAlign.Center)
                if (!state.installed) OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = onEnvironment) { Text(stringResource(R.string.install_runtime)) }
                else if (state.projects.isEmpty()) OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = onProjects) { Text(stringResource(R.string.select_project_first)) }
            }
        }
        items(state.sessions, key = { it.id }) { item ->
            val enterLabel = stringResource(R.string.enter_terminal_number, item.number)
            Surface(onClick = { onOpen(item.id) }, modifier = Modifier.semantics { contentDescription = enterLabel },
                shape = RoundedCornerShape(18.dp), shadowElevation = cardElevation(), border = cardBorder()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 15.dp, end = 15.dp, top = 13.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(terminalStatusColor(item))
                        Text(stringResource(R.string.terminal_number, item.number), fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.projectName, Modifier.weight(1f), textAlign = TextAlign.End,
                                fontSize = 11.5.sp, lineHeight = 16.sp, color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(" · " + terminalStatusLabel(item), fontSize = 11.5.sp, lineHeight = 16.sp, color = TertiaryText)
                        }
                    }
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp), color = TerminalBackground, shape = RoundedCornerShape(11.dp)) {
                        Text(previews[item.id].orEmpty().ifBlank { stringResource(R.string.terminal_waiting_output) },
                            Modifier.heightIn(min = 86.dp).padding(horizontal = 13.dp, vertical = 11.dp), fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp, lineHeight = 20.7.sp, color = TerminalForeground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                        WorkbenchIcon(Glyph.Next, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            DashedTerminalAction(stringResource(if (state.sessions.size >= 4) R.string.terminal_limit else R.string.new_terminal),
                state.installed && state.projects.isNotEmpty() && !state.busy && state.sessions.size < 4) { creating = true }
        }
        item { Text(stringResource(if (state.sessions.size >= 4) R.string.terminal_limit else R.string.terminal_local_lifecycle),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = TertiaryText, textAlign = TextAlign.Center) }
    }
    }
    if (creating) NewTerminalSheet(workbench, state) { creating = false }
}

@Composable
internal fun terminalStatusColor(item: TerminalItem) = when {
    item.session.isRunning -> ReadyGreen
    item.session.exitStatus != 0 -> FailureRed
    else -> androidx.compose.ui.graphics.Color(0xFF9AA7B8)
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
    WorkbenchSheet(stringResource(R.string.new_terminal), onDismiss, stringResource(R.string.terminal_choose_project)) {
        state.projects.forEach { project ->
            SelectablePanel(projectId == project.id, { projectId = project.id }) {
                IconTile(Glyph.Projects, Modifier.size(34.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(project.kinds.map { stringResource(it.label) }.joinToString(stringResource(R.string.type_separator)).ifEmpty { stringResource(R.string.project_type_unset) } +
                        " · " + stringResource(R.string.project_session_count, state.sessions.count { it.projectId == project.id }),
                        style = MaterialTheme.typography.bodySmall, color = TertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SelectionCheck(projectId == project.id)
            }
        }
        WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { onDismiss(); workbench.startTerminal(projectId) }, modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && state.installed && state.sessions.size < 4 && state.projects.any { it.id == projectId }) { Text(stringResource(R.string.create_terminal)) }
        Text(stringResource(R.string.terminal_shell_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

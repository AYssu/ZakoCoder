package dev.zakocoder.app.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.terminal.TerminalItem

private val TerminalChrome: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF0B1016) else Color(0xFF11161D)
private val TerminalText = TerminalForeground
private val TerminalMuted = TerminalSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalPage(workbench: Workbench, state: WorkbenchState, selectedId: String?, onSelect: (String) -> Unit, onBack: () -> Unit) {
    val darkAppearance = LocalWorkbenchDark.current
    val current = state.sessions.singleOrNull { it.id == selectedId } ?: state.sessions.lastOrNull()
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var menu by remember { mutableStateOf(false) }
    var previewing by remember(current?.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(current?.id) { while (true) { now = android.os.SystemClock.elapsedRealtime(); kotlinx.coroutines.delay(60_000) } }
    var switching by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<TerminalItem?>(null) }
    val modifiers by workbench.terminal.modifiers.collectAsStateWithLifecycle()
    val leaveTerminal = { view?.let(workbench.terminal::hideKeyboard); onBack() }
    if (previewing && current != null) TerminalPreviewSheet(current, { workbench.terminal.previewAddresses(current.id) }, { previewing = false; view?.requestFocus() })
    BackHandler { leaveTerminal() }
    Column(Modifier.fillMaxSize().background(TerminalBackground)) {
        Surface(color = TerminalChrome, contentColor = TerminalText) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leaveTerminal) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.back_to_terminals), tint = TerminalText) }
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(if (current == null) stringResource(R.string.nav_terminal) else stringResource(R.string.terminal_number, current.number),
                        fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(current?.let { it.projectName + " · " + terminalStatusLabel(it) + if (it.session.isRunning) " · " + stringResource(R.string.session_minutes, ((now - it.startedAt).coerceAtLeast(0) / 60_000).toInt()) else "" } ?: state.project?.name ?: stringResource(R.string.select_project_first),
                        style = MaterialTheme.typography.bodySmall, color = TerminalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { switching = true }) {
                    BadgedBox(badge = { if (state.sessions.isNotEmpty()) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(state.sessions.size.toString()) } }) {
                        WorkbenchIcon(Glyph.Swap, description = stringResource(R.string.switch_terminals), tint = TerminalText)
                    }
                }
                IconButton(onClick = { view?.let(workbench.terminal::toggleKeyboard) }) {
                    WorkbenchIcon(Glyph.Keyboard, description = stringResource(R.string.keyboard), tint = TerminalMuted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { WorkbenchIcon(Glyph.More, description = stringResource(R.string.terminal_actions), tint = TerminalText) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.terminal_preview)) }, enabled = current != null,
                            onClick = { menu = false; view?.let(workbench.terminal::hideKeyboard); previewing = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.new_terminal)) }, enabled = state.installed && current != null && !state.busy && state.sessions.size < 4,
                            onClick = { menu = false; workbench.startTerminal(current?.projectId) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.close_terminal), color = MaterialTheme.colorScheme.error) },
                            enabled = current != null, onClick = { menu = false; closing = current })
                    }
                }
            }
        }
        if (current == null) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                WorkbenchIcon(Glyph.Terminal, modifier = Modifier.size(40.dp), tint = TerminalMuted)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.terminal_empty_title), style = MaterialTheme.typography.headlineSmall, color = TerminalText)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(when { !state.installed -> R.string.terminal_needs_environment; state.project == null -> R.string.select_project_first; else -> R.string.terminal_direct_help }),
                    style = MaterialTheme.typography.bodyMedium, color = TerminalMuted)
                Spacer(Modifier.height(24.dp))
                WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { workbench.startTerminal(current?.projectId) }, enabled = state.installed && state.project != null && !state.busy) { Text(stringResource(R.string.new_terminal)) }
            }
        } else {
                AndroidView(factory = { workbench.terminal.view(it, current, state.settings.terminalFontSp).also { created -> view = created } },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                    update = { workbench.terminal.bind(it, current); workbench.terminal.appearance(it, current, darkAppearance); it.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, state.settings.terminalFontSp.toFloat(), it.resources.displayMetrics).toInt()); it.onScreenUpdated() },
                    onRelease = { workbench.terminal.detach(it); if (view === it) view = null })
            if (!current.session.isRunning) Text(stringResource(R.string.terminal_exit, current.session.exitStatus),
                Modifier.fillMaxWidth().background(TerminalChrome).padding(12.dp), color = TerminalMuted, style = MaterialTheme.typography.bodySmall)
            Surface(color = TerminalChrome, contentColor = TerminalText) {
                val shortcutsLabel = stringResource(R.string.terminal_shortcuts)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = shortcutsLabel }.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TerminalKey(stringResource(R.string.escape_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_ESCAPE) } }
                    TerminalKey(stringResource(R.string.tab_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_TAB) } }
                    TerminalKey(stringResource(R.string.control_key), Modifier.width(42.dp), modifiers.control) { workbench.terminal.toggleControl() }
                    TerminalKey(stringResource(R.string.alt_key), Modifier.width(36.dp), modifiers.alt) { workbench.terminal.toggleAlt() }
                    TerminalKey(stringResource(R.string.ctrl_c), Modifier.width(56.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_C, control = true) } }
                    TerminalIconKey(Glyph.Up, stringResource(R.string.up_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_DPAD_UP) } }
                    TerminalIconKey(Glyph.Down, stringResource(R.string.down_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_DPAD_DOWN) } }
                    TerminalIconKey(Glyph.Left, stringResource(R.string.left_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_DPAD_LEFT) } }
                    TerminalIconKey(Glyph.Right, stringResource(R.string.right_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_DPAD_RIGHT) } }
                    TerminalKey(stringResource(R.string.home_key), Modifier.width(44.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_MOVE_HOME) } }
                    TerminalKey(stringResource(R.string.end_key), Modifier.width(36.dp)) { view?.let { workbench.terminal.key(it, KeyEvent.KEYCODE_MOVE_END) } }
                }
            }
        }
    }
    if (switching) WorkbenchSheet(stringResource(R.string.switch_terminals), { switching = false },
        stringResource(R.string.terminal_session_count, state.sessions.size), darkBackdrop = true) {
            state.sessions.forEach { item ->
                Surface(shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp,
                    if (item.id == current?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    color = if (item.id == current?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val closeLabel = stringResource(R.string.close_terminal_number, item.number)
                        Row(Modifier.weight(1f).combinedClickable(onClick = { onSelect(item.id); switching = false },
                            onLongClickLabel = closeLabel, onLongClick = { closing = item }).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(terminalStatusColor(item))
                            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.terminal_number, item.number), style = MaterialTheme.typography.titleSmall)
                                Text(item.projectName + " · " + terminalStatusLabel(item),
                                    style = MaterialTheme.typography.bodySmall, color = TertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(Modifier.padding(end = 14.dp)) { SelectionCheck(item.id == current?.id) }
                    }
                }
            }
            OutlinedButton(onClick = { switching = false; workbench.startTerminal(current?.projectId) }, enabled = state.installed && state.project != null && !state.busy && state.sessions.size < 4,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                WorkbenchIcon(Glyph.Add, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_terminal))
            }
            Text(stringResource(R.string.terminal_close_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.sessions.size >= 4) stringResource(R.string.terminal_limit) else current?.let { stringResource(R.string.new_terminal_project, it.projectName) } ?: stringResource(R.string.select_project_first),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    closing?.let { item -> AlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.close_terminal_number, item.number)) },
        text = { Text(stringResource(R.string.close_terminal_confirmation)) },
        confirmButton = { TextButton(onClick = { closing = null; if (state.sessions.size == 1) view?.let(workbench.terminal::hideKeyboard); workbench.closeTerminal(item.id) }) { Text(stringResource(R.string.close_terminal)) } },
        dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun TerminalKey(label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Box(modifier.height(44.dp).semantics { this.selected = selected }.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 2.dp), shape = RoundedCornerShape(7.dp),
            color = if (selected) BrandFill else if (LocalWorkbenchDark.current) Color(0xFF1A2129) else Color(0xFF22282F),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0xFF31383F))) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                Text(label, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TerminalIconKey(glyph: Glyph, label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(44.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 2.dp), shape = RoundedCornerShape(7.dp),
            color = if (LocalWorkbenchDark.current) Color(0xFF1A2129) else Color(0xFF22282F), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF31383F))) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(glyph, Modifier.size(16.dp), description = label, tint = TerminalText) }
        }
    }
}

package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.basic.DropdownItem

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import kotlinx.coroutines.withTimeoutOrNull
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.terminal.TerminalItem

private val TerminalChrome = Color.Black
private val TerminalText = TerminalForeground
private val TerminalMuted = TerminalSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalPage(workbench: Workbench, state: WorkbenchState, selectedId: String?, onSelect: (String) -> Unit,
    onBack: () -> Unit, contentPadding: PaddingValues = PaddingValues(0.dp),
    requestedProjectId: String? = state.selectedId, onEnvironment: () -> Unit = {}) {
    val current = state.sessions.singleOrNull { it.id == selectedId } ?: state.sessions.lastOrNull { it.projectId == requestedProjectId }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var menu by remember { mutableStateOf(false) }
    var menuKeyboardVisible by remember { mutableStateOf(false) }
    var restoreMenuInput by remember { mutableStateOf(false) }
    var previewing by remember(current?.id) { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<TerminalItem?>(null) }
    val modifiers by workbench.terminal.modifiers.collectAsStateWithLifecycle()
    LaunchedEffect(restoreMenuInput, view, current?.id) {
        if (restoreMenuInput) {
            val terminal = view
            if (terminal != null) withTimeoutOrNull(1500) {
                // The popup exit can finish before Android returns focus to the host window.
                while (terminal.isAttachedToWindow && !terminal.hasWindowFocus()) withFrameNanos { }
                if (terminal.isAttachedToWindow && view === terminal) {
                    terminal.requestFocus()
                    if (menuKeyboardVisible) workbench.terminal.keyboard(terminal)
                }
            }
            restoreMenuInput = false
        }
    }
    val leaveTerminal = { view?.let(workbench.terminal::hideKeyboard); onBack() }
    if (previewing && current != null) TerminalPreviewSheet(current, { workbench.terminal.previewAddresses(current.id) }, { previewing = false; view?.requestFocus() })
    val back = rememberWorkbenchBackMotion(enabled = !menu && !previewing && !switching && closing == null, onBack = leaveTerminal)
    BackHandler(enabled = menu) { menu = false }
    TerminalSystemBars()
    Column(Modifier.fillMaxSize().then(back.modifier).background(Color.Black)
        .padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()
        .padding(top = 8.dp)) {
        if (current == null) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                WorkbenchIcon(Glyph.Terminal, modifier = Modifier.size(40.dp), tint = TerminalMuted)
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.terminal_empty_title), style = MaterialTheme.typography.headlineSmall, color = TerminalText)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(when { !state.installed -> R.string.terminal_needs_environment; state.project == null -> R.string.select_project_first; else -> R.string.terminal_direct_help }),
                    style = MaterialTheme.typography.bodyMedium, color = TerminalMuted)
                Spacer(Modifier.height(24.dp))
                WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { workbench.startTerminal(requestedProjectId) }, enabled = state.installed && state.projects.any { it.id == requestedProjectId } && !state.busy && state.sessions.size < 4) { Text(stringResource(R.string.new_terminal)) }
                if (!state.installed) WorkbenchTextButton(onClick = onEnvironment) { Text(stringResource(R.string.nav_environment), color = TerminalText) }
            }
        } else {
                AndroidView(factory = { workbench.terminal.view(it, current, state.settings.terminalFontSp).also { created -> view = created } },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
                    update = { workbench.terminal.bind(it, current); workbench.terminal.appearance(it, current); it.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, state.settings.terminalFontSp.toFloat(), it.resources.displayMetrics).toInt()); it.onScreenUpdated() },
                    onRelease = { workbench.terminal.detach(it); if (view === it) view = null })
            if (!current.session.isRunning) Text(stringResource(R.string.terminal_exit, current.session.exitStatus),
                Modifier.fillMaxWidth().background(TerminalChrome).padding(12.dp), color = TerminalMuted, style = MaterialTheme.typography.bodySmall)
            TerminalControls(modifiers.control, modifiers.alt,
                onKey = { key, control -> view?.let { workbench.terminal.key(it, key, control = control) } },
                onControl = workbench.terminal::toggleControl, onAlt = workbench.terminal::toggleAlt,
                onKeyboard = { view?.let(workbench.terminal::toggleKeyboard) }, onSwitch = { switching = true },
                menu = menu, onMenu = { opening ->
                    if (opening) menuKeyboardVisible = view?.let {
                        androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())
                    } == true
                    menu = opening
                }, onMenuCancelled = { restoreMenuInput = true },
                canCreate = state.installed && !state.busy && state.sessions.size < 4,
                onPreview = { menu = false; view?.let(workbench.terminal::hideKeyboard); previewing = true },
                onCreate = { menu = false; workbench.startTerminal(current.projectId) },
                onClose = { menu = false; closing = current })

        }
    }
    if (switching) WorkbenchSheet(stringResource(R.string.switch_terminals), { switching = false },
        stringResource(R.string.terminal_session_count, state.sessions.size), darkBackdrop = true) {
            val finish = LocalWorkbenchSheetAction.current
            state.sessions.forEach { item ->
                WorkbenchCard(border = if (item.id == current?.id) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    color = if (item.id == current?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val closeLabel = stringResource(R.string.close_terminal_number, item.number)
                        Row(Modifier.weight(1f).combinedClickable(onClick = { finish { onSelect(item.id) } },
                            onLongClickLabel = closeLabel, onLongClick = { closing = item }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(terminalStatusColor(item))
                            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.terminal_number, item.number), style = MaterialTheme.typography.titleSmall)
                                Text(item.projectName + " · " + terminalStatusLabel(item),
                                    style = MaterialTheme.typography.bodySmall, color = TertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Box(Modifier.padding(end = 16.dp)) { SelectionCheck(item.id == current?.id) }
                    }
                }
            }
            WorkbenchSecondaryButton(onClick = { finish { workbench.startTerminal(requestedProjectId) } }, enabled = state.installed && state.project != null && !state.busy && state.sessions.size < 4,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                WorkbenchIcon(Glyph.Add, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.new_terminal))
            }
            Text(stringResource(R.string.terminal_close_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.sessions.size >= 4) stringResource(R.string.terminal_limit) else current?.let { stringResource(R.string.new_terminal_project, it.projectName) } ?: stringResource(R.string.select_project_first),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    closing?.let { item -> WorkbenchAlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.close_terminal_number, item.number)) },
        text = { Text(stringResource(R.string.close_terminal_confirmation)) },
        confirmButton = { WorkbenchDialogButton(onClick = { closing = null; if (state.sessions.size == 1) view?.let(workbench.terminal::hideKeyboard); workbench.closeTerminal(item.id) }) { Text(stringResource(R.string.close_terminal)) } },
        dismissButton = { WorkbenchDialogButton(onClick = { closing = null }) { Text(stringResource(R.string.cancel)) } }) }
}

/** Two fixed rows keep every frequent action reachable without horizontal scrolling. */
@Composable
internal fun TerminalControls(control: Boolean, alt: Boolean, onKey: (Int, Boolean) -> Unit,
    onControl: () -> Unit, onAlt: () -> Unit, onKeyboard: () -> Unit, onSwitch: () -> Unit,
    menu: Boolean, onMenu: (Boolean) -> Unit, canCreate: Boolean,
    onPreview: () -> Unit, onCreate: () -> Unit, onClose: () -> Unit,
    onMenuCancelled: () -> Unit = {}) {
    Surface(color = TerminalChrome, contentColor = TerminalText) {
        val shortcutsLabel = stringResource(R.string.terminal_shortcuts)
        Column(Modifier.fillMaxWidth().semantics { contentDescription = shortcutsLabel }.padding(horizontal = 4.dp)) {
            HorizontalDivider(color = Color(0xFF202020))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TerminalKey(stringResource(R.string.escape_key), Modifier.weight(1f)) { onKey(KeyEvent.KEYCODE_ESCAPE, false) }
                TerminalKey(stringResource(R.string.tab_key), Modifier.weight(1f)) { onKey(KeyEvent.KEYCODE_TAB, false) }
                TerminalKey(stringResource(R.string.home_key), Modifier.weight(1f)) { onKey(KeyEvent.KEYCODE_MOVE_HOME, false) }
                TerminalIconKey(Glyph.Up, stringResource(R.string.up_key), Modifier.weight(1f), repeat = true) { onKey(KeyEvent.KEYCODE_DPAD_UP, false) }
                TerminalKey(stringResource(R.string.end_key), Modifier.weight(1f)) { onKey(KeyEvent.KEYCODE_MOVE_END, false) }
                TerminalIconKey(Glyph.Keyboard, stringResource(R.string.keyboard), Modifier.weight(1f), onClick = onKeyboard)
                Box(Modifier.weight(1f)) {
                    TerminalIconKey(Glyph.More, stringResource(R.string.terminal_actions), Modifier.fillMaxWidth()) { onMenu(true) }
                    WorkbenchMiuixTheme(dark = true) {
                        WorkbenchActionMenu(expanded = menu, onDismissRequest = { onMenu(false) }, items = listOf(
                            DropdownItem(text = stringResource(R.string.terminal_preview),
                                icon = { WorkbenchIcon(Glyph.Globe) }, onClick = onPreview),
                            DropdownItem(text = stringResource(R.string.new_terminal), enabled = canCreate,
                                icon = { WorkbenchIcon(Glyph.Add) }, onClick = onCreate),
                            DropdownItem(text = stringResource(R.string.close_terminal),
                                icon = { WorkbenchIcon(Glyph.Close, tint = Color(0xFFF2A5A5)) }, onClick = onClose),
                        ), onCancelled = onMenuCancelled, keyboardIndependent = true)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TerminalKey(stringResource(R.string.control_key), Modifier.weight(1f), control, onControl)
                TerminalKey(stringResource(R.string.alt_key), Modifier.weight(1f), alt, onAlt)
                TerminalIconKey(Glyph.Left, stringResource(R.string.left_key), Modifier.weight(1f), repeat = true) { onKey(KeyEvent.KEYCODE_DPAD_LEFT, false) }
                TerminalIconKey(Glyph.Down, stringResource(R.string.down_key), Modifier.weight(1f), repeat = true) { onKey(KeyEvent.KEYCODE_DPAD_DOWN, false) }
                TerminalIconKey(Glyph.Right, stringResource(R.string.right_key), Modifier.weight(1f), repeat = true) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT, false) }
                TerminalKey(stringResource(R.string.terminal_slash_key), Modifier.weight(1f)) { onKey(KeyEvent.KEYCODE_SLASH, false) }
                TerminalIconKey(Glyph.Swap, stringResource(R.string.switch_terminals), Modifier.weight(1f), onClick = onSwitch)
            }
        }
    }
}

@Composable
private fun TerminalKey(label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    TerminalControlKey(modifier, selected = selected, onClick = onClick) {
        Text(label, color = TerminalText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun TerminalIconKey(glyph: Glyph, label: String, modifier: Modifier, repeat: Boolean = false, onClick: () -> Unit) {
    TerminalControlKey(modifier, repeat = repeat, onClick = onClick) {
        WorkbenchIcon(glyph, Modifier.size(18.dp), description = label, tint = TerminalText)
    }
}

/** Keep the system clock and indicators visible on black; restore the actual parent's bar state. */
@Composable
private fun TerminalSystemBars() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val insets = window?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it.decorView) }
        val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
        val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
        val behavior = controller?.systemBarsBehavior
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        val contrast = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        controller?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            // Immersive navigation consumes the first Back on Android 15 to reveal bars.
            // Keep the black navigation safe area visible so Back always reaches the page.
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        onDispose {
            controller?.apply {
                behavior?.let { systemBarsBehavior = it }
                lightStatus?.let { isAppearanceLightStatusBars = it }
                lightNavigation?.let { isAppearanceLightNavigationBars = it }
                if (statusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
                if (navigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
            }
            if (android.os.Build.VERSION.SDK_INT >= 29 && contrast != null) window?.isNavigationBarContrastEnforced = contrast
        }
    }
}

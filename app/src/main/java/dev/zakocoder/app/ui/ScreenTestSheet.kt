package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.basic.RadioButton
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.screentest.ScreenTestController
import dev.zakocoder.app.screentest.ScreenTestAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Display-only projection; fixtures never write fake reports to the production controller. */
internal data class ScreenTestUiModel(
    val packageName: String? = null,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val summary: String = "",
    val hasReport: Boolean = false,
    val access: ScreenTestAccess = ScreenTestAccess.Accessibility,
    val active: Boolean = false,
    val shareScreenshots: Boolean = false,
    val rootGranted: Boolean = false,
    val overlayEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val overlayVisible: Boolean = false,
    val overlayFailure: String = "",
)

@Composable
internal fun ScreenTestSheet(controller: ScreenTestController, projectId: String?, projectName: String, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var working by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var shareConfirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reportOpen by remember(projectId) { mutableStateOf(false) }
    var report by remember(projectId) { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.screen_test_operation_failed)
    val unavailable = stringResource(R.string.screen_test_service_unavailable)
    val rootDenied = stringResource(R.string.screen_test_root_denied)
    val overlaySettingsUnavailable = stringResource(R.string.screen_overlay_settings_unavailable)
    val ownerId = state.projectId ?: ScreenTestController.DEVICE_TASK_OWNER
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        owner.lifecycle.addObserver(observer)
        controller.refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    WorkbenchSheet(stringResource(R.string.screen_test_title), onDismiss, subtitle = stringResource(R.string.screen_test_subtitle)) {
        ScreenTestPanel(
            model = ScreenTestUiModel(packageName = state.packageName, enabled = state.enabled, connected = state.connected,
                busy = state.busy, summary = state.summary, hasReport = state.reportPath != null, access = state.access,
                active = state.active, shareScreenshots = state.shareScreenshots, rootGranted = state.rootGranted,
                overlayEnabled = state.overlayEnabled, overlayGranted = state.overlayGranted,
                overlayVisible = state.overlayVisible, overlayFailure = state.overlayFailure),
            working = working,
            error = error,
            onEnable = {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { error = unavailable }
                catch (_: SecurityException) { error = unavailable }
            },
            onAccess = controller::setAccess,
            onRoot = {
                scope.launch {
                    working = true; error = null
                    try { if (!controller.requestRootAccess()) error = rootDenied }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                    finally { working = false }
                }
            },
            onShare = { enabled ->
                if (enabled) state.projectId?.let { id -> controller.taskLease(id)?.let { lease -> shareConfirmation = id to lease } }
                else state.projectId?.let {
                    try { controller.setScreenshotSharing(it, false) }
                    catch (failure: IllegalStateException) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                }
            },
            onOverlayEnabled = controller::setOverlayEnabled,
            onOverlaySettings = {
                try { controller.openOverlaySettings() }
                catch (_: android.content.ActivityNotFoundException) { error = overlaySettingsUnavailable }
                catch (_: SecurityException) { error = overlaySettingsUnavailable }
            },
            onStop = controller::stop,
            onReport = {
                reportOpen = true; report = null
                scope.launch {
                    try { report = controller.readReport(ownerId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reportOpen = false; error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                }
            },
        )
    }
    shareConfirmation?.let { (authorizedOwner, authorizedLease) ->
        ScreenTestScreenshotConsent(onDismiss = { shareConfirmation = null }, onConfirm = {
            shareConfirmation = null
            try {
                check(controller.state.value.active && controller.taskLease(authorizedOwner) == authorizedLease) { fallback }
                controller.setScreenshotSharing(authorizedOwner, true, authorizedLease)
            } catch (failure: IllegalStateException) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
        })
    }
    if (reportOpen) WorkbenchSheet(stringResource(R.string.screen_test_report_title), { reportOpen = false }) {
        ScreenTestReport(report)
    }
}

@Composable
internal fun ScreenTestPanel(model: ScreenTestUiModel, working: Boolean = false, error: String? = null,
                             onEnable: () -> Unit, onStop: () -> Unit, onReport: () -> Unit,
                             onAccess: (ScreenTestAccess) -> Unit = {}, onRoot: () -> Unit = {}, onShare: (Boolean) -> Unit = {},
                             onOverlayEnabled: (Boolean) -> Unit = {},
                             onOverlaySettings: () -> Unit = {}) {
    ScreenTestGroup(stringResource(R.string.screen_device_scope_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIcon(Glyph.Environment, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.screen_device_current), style = MaterialTheme.typography.bodyLarge)
        }
        Text(stringResource(R.string.screen_device_scope_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (model.active) R.string.screen_test_device_ready else R.string.screen_device_task_pending),
            style = MaterialTheme.typography.bodyMedium,
            color = if (model.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        model.packageName?.takeIf { model.active }?.let {
            Text(stringResource(R.string.screen_device_foreground, it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        if (model.active || model.busy) WorkbenchSecondaryButton(onClick = onStop,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.screen_test_stop))
        }
    }
    ScreenTestGroup(stringResource(R.string.screen_test_access)) {
        listOf(ScreenTestAccess.Accessibility, ScreenTestAccess.Root).forEach { access ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = model.access == access, enabled = !working, role = Role.RadioButton) { onAccess(access) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = model.access == access, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(when (access) { ScreenTestAccess.Normal -> R.string.screen_test_access_normal; ScreenTestAccess.Accessibility -> R.string.screen_test_access_accessibility; ScreenTestAccess.Root -> R.string.screen_test_access_root }), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(when (access) { ScreenTestAccess.Normal -> R.string.screen_test_access_normal_hint; ScreenTestAccess.Accessibility -> R.string.screen_test_access_accessibility_hint; ScreenTestAccess.Root -> R.string.screen_test_access_root_hint }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(stringResource(R.string.screen_test_access_change_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (model.access == ScreenTestAccess.Accessibility) ScreenTestGroup(stringResource(R.string.screen_test_service)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIcon(if (model.connected) Glyph.Check else Glyph.Shield, Modifier.size(18.dp),
                tint = if (model.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when { model.connected -> R.string.screen_test_service_ready; model.enabled -> R.string.screen_test_service_connecting; else -> R.string.screen_test_service_off }),
                style = MaterialTheme.typography.bodyLarge)
        }
        Text(stringResource(R.string.screen_test_service_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onEnable, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (model.enabled) R.string.screen_test_manage_service else R.string.screen_test_enable_service))
        }
    }
    if (model.access == ScreenTestAccess.Root) ScreenTestGroup(stringResource(R.string.screen_test_root_title)) {
        if (model.rootGranted) Text(stringResource(R.string.screen_test_root_granted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.screen_test_root_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkbenchSecondaryButton(onClick = onRoot, enabled = !model.busy && !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_root_request))
        }
    }
    if (model.access != ScreenTestAccess.Normal) ScreenTestGroup(stringResource(R.string.screen_test_share_title)) {
        val shareLabel = stringResource(R.string.screen_test_share_label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shareLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            WorkbenchSwitch(checked = model.shareScreenshots, onCheckedChange = onShare, enabled = model.active && !working,
                modifier = Modifier.semantics { contentDescription = shareLabel })
        }
        Text(stringResource(R.string.screen_test_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ScreenTestGroup(stringResource(R.string.screen_overlay_title)) {
        val overlayLabel = stringResource(R.string.screen_overlay_switch)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(overlayLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            WorkbenchSwitch(checked = model.overlayEnabled && model.overlayGranted, onCheckedChange = onOverlayEnabled,
                enabled = model.overlayGranted && !working,
                modifier = Modifier.semantics { contentDescription = overlayLabel })
        }
        Text(stringResource(R.string.screen_overlay_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CopyHelp(stringResource(R.string.screen_overlay_help_title), stringResource(R.string.screen_overlay_help_body))
        if (model.overlayFailure.isNotBlank()) {
            ScreenTestError(stringResource(when (model.overlayFailure) {
                "permission", "window_permission" -> R.string.screen_overlay_failure_permission
                "foreground_service" -> R.string.screen_overlay_failure_background
                else -> R.string.screen_overlay_failure_window
            }))
            Text(stringResource(R.string.screen_overlay_failure_fallback), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.overlayGranted) TextButton(onClick = { onOverlayEnabled(true) }, enabled = !working,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.screen_overlay_retry)) }
        } else Text(stringResource(when {
            !model.overlayGranted -> R.string.screen_overlay_permission_needed
            !model.overlayEnabled -> R.string.screen_overlay_off
            model.overlayVisible -> R.string.screen_overlay_visible
            !model.active -> R.string.screen_overlay_waiting_target
            else -> R.string.screen_overlay_temporarily_hidden
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!model.overlayGranted) TextButton(onClick = onOverlaySettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_overlay_open_settings))
        }
    }
    ScreenTestGroup(stringResource(R.string.screen_test_run)) {
        SelectionContainer { Text(model.summary.ifBlank { stringResource(R.string.screen_test_idle) }, style = MaterialTheme.typography.bodyMedium) }
        if (model.hasReport) TextButton(onClick = onReport, modifier = Modifier.heightIn(min = 48.dp)) {
            WorkbenchIcon(Glyph.File, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.screen_test_report))
        }
    }
    error?.let { ScreenTestError(it) }
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.screen_test_details), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            WorkbenchIcon(if (expanded) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp))
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.screen_test_capture_hint, R.string.screen_test_scope_hint).forEach {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (model.access == ScreenTestAccess.Root) {
                Text(stringResource(R.string.screen_test_root_capture_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.screen_test_root_text_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onEnable, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (model.enabled) R.string.screen_test_manage_service else R.string.screen_test_enable_service))
                }
            }
        }
    }
}

@Composable
private fun ScreenTestGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    WorkbenchCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun ScreenTestError(message: String) {
    WorkbenchCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        SelectionContainer { Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ScreenTestReport(report: String?) {
    if (report == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.screen_test_report_loading)) }
    else SelectionContainer {
        Text(report.ifBlank { stringResource(R.string.screen_test_report_empty) }, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun ScreenTestScreenshotConsent(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    WorkbenchAlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_test_share_confirm_title)) },
        text = { Text(stringResource(R.string.screen_test_share_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_share_confirm_action))
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

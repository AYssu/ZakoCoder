package dev.zakocoder.app.ui

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
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
    val projectName: String = "",
    val hasProject: Boolean = false,
    val packageName: String? = null,
    val apkPath: String? = null,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val summary: String = "",
    val hasReport: Boolean = false,
    val access: ScreenTestAccess = ScreenTestAccess.Normal,
    val active: Boolean = false,
    val shareScreenshots: Boolean = false,
    val rootGranted: Boolean = false,
    val installAllowed: Boolean = false,
)

internal data class ScreenTestApkUi(val path: String, val packageName: String, val label: String, val debuggable: Boolean)

@Composable
internal fun ScreenTestSheet(controller: ScreenTestController, projectId: String?, projectName: String, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var choosing by remember(projectId) { mutableStateOf(false) }
    var apks by remember(projectId) { mutableStateOf(emptyList<ScreenTestApkUi>()) }
    var loading by remember(projectId) { mutableStateOf(false) }
    var working by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var shareConfirmation by remember(projectId) { mutableStateOf(false) }
    var reportOpen by remember(projectId) { mutableStateOf(false) }
    var report by remember(projectId) { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.screen_test_operation_failed)
    val unavailable = stringResource(R.string.screen_test_service_unavailable)
    val rootDenied = stringResource(R.string.screen_test_root_denied, context.packageName)
    val installSettingsUnavailable = stringResource(R.string.screen_test_install_settings_unavailable)
    val current = state.projectId == projectId && projectId != null
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.refresh() }
        owner.lifecycle.addObserver(observer)
        controller.refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun discover() {
        val id = projectId ?: return
        scope.launch {
            loading = true; error = null
            try {
                apks = controller.discoverApks(id).map { ScreenTestApkUi(it.relativePath, it.packageName, it.label, it.debuggable) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
            finally { loading = false }
        }
    }
    WorkbenchSheet(stringResource(R.string.screen_test_title), onDismiss, subtitle = stringResource(R.string.screen_test_subtitle)) {
        ScreenTestPanel(
            model = ScreenTestUiModel(projectName, projectId != null, state.packageName.takeIf { current }, state.apkPath.takeIf { current },
                state.enabled, state.connected, state.busy, state.summary.takeIf { current }.orEmpty(), current && state.reportPath != null,
                state.access, state.active, current && state.shareScreenshots, state.rootGranted, state.installAllowed),
            working = working,
            error = error,
            onChoose = { choosing = true; discover() },
            onEnable = {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { error = unavailable }
                catch (_: SecurityException) { error = unavailable }
            },
            onInstallPermission = {
                try { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
                catch (_: android.content.ActivityNotFoundException) { error = installSettingsUnavailable }
                catch (_: SecurityException) { error = installSettingsUnavailable }
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
                if (enabled) shareConfirmation = true
                else projectId?.let {
                    try { controller.setScreenshotSharing(it, false) }
                    catch (failure: IllegalStateException) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                }
            },
            onStop = controller::stop,
            onReport = {
                val id = projectId
                if (id != null) {
                    reportOpen = true; report = null
                    scope.launch {
                        try { report = controller.readReport(id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { reportOpen = false; error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                    }
                }
            },
        )
    }
    if (shareConfirmation) ScreenTestScreenshotConsent(onDismiss = { shareConfirmation = false }, onConfirm = {
        shareConfirmation = false
        projectId?.let {
            try { controller.setScreenshotSharing(it, true) }
            catch (failure: IllegalStateException) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
        }
    })
    if (choosing) WorkbenchSheet(stringResource(R.string.screen_test_apk_heading), { choosing = false }) {
        ScreenTestApkPicker(apks, loading, working, error, onRefresh = ::discover, onSelect = { apk ->
            val id = projectId
            if (id != null) scope.launch {
                working = true; error = null
                try { controller.selectApk(id, apk.path); choosing = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message?.takeIf { it.isNotBlank() } ?: fallback }
                finally { working = false }
            }
        })
    }
    if (reportOpen) WorkbenchSheet(stringResource(R.string.screen_test_report_title), { reportOpen = false }) {
        ScreenTestReport(report)
    }
}

@Composable
internal fun ScreenTestPanel(model: ScreenTestUiModel, working: Boolean = false, error: String? = null,
                             onChoose: () -> Unit, onEnable: () -> Unit, onStop: () -> Unit, onReport: () -> Unit,
                             onAccess: (ScreenTestAccess) -> Unit = {}, onRoot: () -> Unit = {}, onShare: (Boolean) -> Unit = {},
                             onInstallPermission: () -> Unit = {}) {
    if (!model.hasProject) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(stringResource(R.string.screen_test_no_project), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Text(stringResource(R.string.screen_test_project, model.projectName), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    ScreenTestGroup(stringResource(R.string.screen_test_access)) {
        ScreenTestAccess.entries.forEach { access ->
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
    ScreenTestGroup(stringResource(R.string.screen_test_target)) {
        Text(model.packageName ?: stringResource(R.string.screen_test_target_none), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, fontFamily = if (model.packageName != null) FontFamily.Monospace else FontFamily.Default)
        model.apkPath?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(stringResource(R.string.screen_test_target_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val action = if (model.apkPath == null) R.string.screen_test_choose_apk else R.string.screen_test_change_apk
        if (model.apkPath == null) Button(onClick = onChoose, enabled = !model.busy && !working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(action)) }
        else TextButton(onClick = onChoose, enabled = !model.busy && !working, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(action)) }
        if (model.installAllowed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIcon(Glyph.Check, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.screen_test_install_allowed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else TextButton(onClick = onInstallPermission, enabled = !model.busy && !working, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_allow_install))
        }
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
        OutlinedButton(onClick = onRoot, enabled = !model.busy && !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_root_request))
        }
    }
    if (model.access != ScreenTestAccess.Normal) ScreenTestGroup(stringResource(R.string.screen_test_share_title)) {
        val shareLabel = stringResource(R.string.screen_test_share_label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shareLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = model.shareScreenshots, onCheckedChange = onShare, enabled = model.apkPath != null && !working,
                modifier = Modifier.semantics { contentDescription = shareLabel })
        }
        Text(stringResource(R.string.screen_test_share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ScreenTestGroup(stringResource(R.string.screen_test_run)) {
        SelectionContainer { Text(model.summary.ifBlank { stringResource(R.string.screen_test_idle) }, style = MaterialTheme.typography.bodyMedium) }
        if (model.active || model.busy) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(stringResource(R.string.screen_test_stop))
        }
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
            listOf(R.string.screen_test_install_hint, R.string.screen_test_capture_hint, R.string.screen_test_scope_hint).forEach {
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
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
internal fun ScreenTestApkPicker(apks: List<ScreenTestApkUi>, loading: Boolean, working: Boolean, error: String?,
                                 onRefresh: () -> Unit, onSelect: (ScreenTestApkUi) -> Unit) {
    TextButton(onClick = onRefresh, enabled = !loading && !working, modifier = Modifier.heightIn(min = 48.dp)) {
        WorkbenchIcon(Glyph.Refresh, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.screen_test_refresh))
    }
    if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.screen_test_apk_loading), style = MaterialTheme.typography.bodySmall) }
    else if (apks.isEmpty()) {
        ScreenTestGroup(stringResource(R.string.screen_test_apk_empty)) {
            Text(stringResource(R.string.screen_test_apk_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else apks.forEach { apk -> ScreenTestGroup(apk.label.ifBlank { apk.packageName }) {
        Text(apk.packageName, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        Text(apk.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val canSelect = apk.debuggable && !working
        OutlinedButton(onClick = { onSelect(apk) }, enabled = canSelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            border = BorderStroke(1.dp, if (canSelect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) {
            Text(stringResource(if (apk.debuggable) R.string.screen_test_apk_authorize else R.string.screen_test_apk_not_debuggable))
        }
    } }
    error?.let { ScreenTestError(it) }
}

@Composable
private fun ScreenTestError(message: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
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
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_test_share_confirm_title)) },
        text = { Text(stringResource(R.string.screen_test_share_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_share_confirm_action))
        } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

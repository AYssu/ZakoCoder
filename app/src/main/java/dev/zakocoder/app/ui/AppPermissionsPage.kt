package dev.zakocoder.app.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zakocoder.app.R
import dev.zakocoder.app.permissions.AppPermission
import dev.zakocoder.app.permissions.AppPermissionStatus
import dev.zakocoder.app.permissions.AppPermissions
import dev.zakocoder.app.settings.RunningNotificationStatus

@Composable
internal fun AppPermissionsPage(rootGranted: Boolean, rootBusy: Boolean = false, rootError: String = "",
    onRequestRoot: () -> Unit, onToolPermissions: () -> Unit, onBack: () -> Unit,
    toolsEnabled: Boolean = true, backgroundServiceRunning: Boolean = false, backgroundServiceError: Boolean = false) {
    val context = LocalContext.current
    val access = remember(context) { AppPermissions(context) }
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var states by remember { mutableStateOf<List<AppPermissionStatus>?>(null) }
    var navigationError by remember { mutableStateOf(false) }
    var confirmRoot by remember { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(access, revision) { states = access.snapshot() }
    if (confirmRoot) AlertDialog(onDismissRequest = { confirmRoot = false },
        title = { Text(stringResource(R.string.app_permissions_root)) },
        text = { Text(stringResource(R.string.app_permissions_root_confirm)) },
        confirmButton = { TextButton(onClick = { confirmRoot = false; onRequestRoot() }) { Text(stringResource(R.string.app_permissions_root_request)) } },
        dismissButton = { TextButton(onClick = { confirmRoot = false }) { Text(stringResource(R.string.cancel)) } })
    AiFullScreen(stringResource(R.string.app_permissions_title), onBack) {
        AppPermissionsPanel(states, rootGranted, rootBusy, rootError,
            navigationError, onManage = { permission -> navigationError = !access.openSettings(permission) },
            onRequest = { permission ->
                val permissions = access.runtimePermissions(permission)
                if (permissions.isNotEmpty()) permissionRequest.launch(permissions)
                else navigationError = !access.openSettings(permission)
            }, onRoot = { confirmRoot = true }, onTools = onToolPermissions, toolsEnabled = toolsEnabled,
            backgroundServiceRunning = backgroundServiceRunning, backgroundServiceError = backgroundServiceError)
    }
}

/** Pure presentation for both themes and large-font layout checks. No system queries or grants. */
@Composable
internal fun AppPermissionsPanel(states: List<AppPermissionStatus>?, rootGranted: Boolean, rootBusy: Boolean,
    rootError: String, navigationError: Boolean = false, onManage: (AppPermission) -> Unit,
    onRequest: (AppPermission) -> Unit, onRoot: () -> Unit, onTools: () -> Unit,
    toolsEnabled: Boolean = true, backgroundServiceRunning: Boolean = false, backgroundServiceError: Boolean = false) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.app_permissions_intro), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (states == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.app_permissions_loading),
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        if (navigationError) item { Text(stringResource(R.string.app_permissions_settings_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        item { PermissionGroup(stringResource(R.string.app_permissions_system)) {
            PermissionRow(AppPermission.Notifications, states, onManage, onRequest)
            GroupDivider()
            PermissionRow(AppPermission.Files, states, onManage, onRequest)
            GroupDivider()
            PermissionRow(AppPermission.InstallPackages, states, onManage, onRequest)
        } }
        item { PermissionGroup(stringResource(R.string.app_permissions_special)) {
            PermissionRow(AppPermission.Overlay, states, onManage, onRequest)
            GroupDivider()
            PermissionRow(AppPermission.Accessibility, states, onManage, onRequest)
            GroupDivider()
            PermissionContent(stringResource(R.string.app_permissions_root), stringResource(R.string.app_permissions_root_hint),
                stringResource(if (rootBusy) R.string.app_permissions_root_busy else if (rootGranted) R.string.app_permissions_granted else R.string.app_permissions_disabled),
                rootGranted, Glyph.Shield, if (rootBusy) null else onRoot, stringResource(R.string.app_permissions_root_request))
            if (rootError.isNotBlank()) Text(rootError, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } }
        item { PermissionGroup(stringResource(R.string.app_permissions_background)) {
            PermissionContent(stringResource(R.string.app_permissions_service),
                stringResource(if (backgroundServiceError) R.string.app_permissions_service_error_hint else R.string.app_permissions_service_hint),
                stringResource(when { backgroundServiceError -> R.string.app_permissions_service_limited
                    backgroundServiceRunning -> R.string.app_permissions_service_running
                    else -> R.string.app_permissions_service_idle }), backgroundServiceRunning && !backgroundServiceError,
                Glyph.Terminal, null, "")
            GroupDivider()
            PermissionRow(AppPermission.Battery, states, onManage, onRequest)
            Text(stringResource(R.string.app_permissions_background_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { PermissionGroup(stringResource(R.string.app_permissions_tools)) {
            PermissionContent(stringResource(R.string.app_permissions_tools),
                stringResource(if (toolsEnabled) R.string.app_permissions_tools_hint else R.string.app_permissions_tools_unavailable), "", null,
                Glyph.Shield, onTools.takeIf { toolsEnabled }, stringResource(R.string.app_permissions_manage))
        } }
    }
}

@Composable
private fun PermissionGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = cardBorder()) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun PermissionRow(permission: AppPermission, states: List<AppPermissionStatus>?, onManage: (AppPermission) -> Unit,
    onRequest: (AppPermission) -> Unit) {
    val state = states?.firstOrNull { it.permission == permission }
    val title = when (permission) {
        AppPermission.Notifications -> R.string.app_permissions_notifications
        AppPermission.Files -> if (Build.VERSION.SDK_INT >= 30) R.string.app_permissions_files else R.string.app_permissions_files_legacy
        AppPermission.InstallPackages -> R.string.app_permissions_install
        AppPermission.Overlay -> R.string.app_permissions_overlay
        AppPermission.Accessibility -> R.string.app_permissions_accessibility
        AppPermission.Battery -> R.string.app_permissions_battery
    }
    val hint = when (permission) {
        AppPermission.Notifications -> R.string.app_permissions_notifications_hint
        AppPermission.Files -> if (Build.VERSION.SDK_INT >= 30) R.string.app_permissions_files_hint else R.string.app_permissions_files_legacy_hint
        AppPermission.InstallPackages -> R.string.app_permissions_install_hint
        AppPermission.Overlay -> R.string.app_permissions_overlay_hint
        AppPermission.Accessibility -> R.string.app_permissions_accessibility_hint
        AppPermission.Battery -> R.string.app_permissions_battery_hint
    }
    val status = if (permission == AppPermission.Notifications && state?.notificationStatus != null) stringResource(state.notificationStatus.label())
        else stringResource(when {
            state?.granted == null -> R.string.app_permissions_unknown
            permission == AppPermission.Battery -> if (state.granted) R.string.app_permissions_battery_exempt else R.string.app_permissions_battery_optimized
            state.granted -> R.string.app_permissions_granted
            else -> R.string.app_permissions_disabled
        })
    PermissionContent(stringResource(title), stringResource(hint), status, state?.granted,
        if (permission == AppPermission.Notifications) Glyph.Bell else if (permission == AppPermission.Files) Glyph.Projects else Glyph.Shield,
        { onManage(permission) }, stringResource(R.string.app_permissions_manage))
    // Runtime grants are separate from the always-available settings route, including permanent denial.
    if (state?.granted == false && ((permission == AppPermission.Notifications && state.notificationStatus == RunningNotificationStatus.PermissionRequired) ||
            (permission == AppPermission.Files && Build.VERSION.SDK_INT < 30))) {
        TextButton(onClick = { onRequest(permission) }, modifier = Modifier.padding(start = 48.dp, end = 16.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.app_permissions_root_request))
        }
    }
}

@Composable
private fun PermissionContent(title: String, hint: String, status: String, granted: Boolean?, glyph: Glyph,
    onClick: (() -> Unit)?, actionLabel: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchIcon(glyph, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (granted == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel.isNotEmpty()) TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) { Text(actionLabel, style = MaterialTheme.typography.labelMedium) }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

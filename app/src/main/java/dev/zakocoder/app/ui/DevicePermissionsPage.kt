package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.device.DeviceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation

/** The same global mode selection is used by settings, onboarding and capability prerequisites. */
@Composable
internal fun DevicePermissionsPage(workbench: Workbench, onBack: () -> Unit,
    onToolPermissions: () -> Unit = {}, toolsEnabled: Boolean = false,
    backgroundServiceRunning: Boolean = false, backgroundServiceError: Boolean = false,
    embedded: Boolean = false, backEnabled: Boolean = true) {
    val state by workbench.deviceAccess.state.collectAsStateWithLifecycle()
    AppPermissionsPage(rootGranted = state.rootGranted, rootBusy = state.busy, rootError = "",
        onRequestRoot = {}, onToolPermissions = onToolPermissions, onBack = onBack,
        toolsEnabled = toolsEnabled, backgroundServiceRunning = backgroundServiceRunning,
        backgroundServiceError = backgroundServiceError, embedded = embedded, backEnabled = backEnabled,
        showLegacyDevicePermissions = false, deviceConfiguration = { DeviceModeConfiguration(workbench) })
}

@Composable
internal fun DeviceModeConfiguration(workbench: Workbench) {
    val state by workbench.deviceAccess.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmRoot by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val failureText = stringResource(R.string.device_permissions_error)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(state.loaded, state.mode) {
        if (state.loaded && state.mode == DeviceMode.Root && !state.busy && !state.rootGranted) {
            workbench.deviceAccess.ensureRoot()
            workbench.deviceAccess.refreshPermissions()
        }
    }
    DisposableEffect(owner, workbench.deviceAccess) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                if (workbench.deviceAccess.state.value.mode == DeviceMode.Root) workbench.deviceAccess.ensureRoot()
                workbench.deviceAccess.refreshPermissions()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DeviceModePanel(rootMode = state.mode == DeviceMode.Root, rootGranted = state.rootGranted,
        busy = !state.loaded || state.busy, error = localError ?: state.error?.let { failureText },
        onBasic = { localError = null; workbench.deviceAccess.useBasic() }, onRoot = { confirmRoot = true })
    if (confirmRoot) WorkbenchAlertDialog(onDismissRequest = { confirmRoot = false },
        title = { Text(stringResource(R.string.device_mode_root)) },
        text = { Text(stringResource(R.string.device_mode_root_confirm)) },
        confirmButton = { WorkbenchDialogButton(onClick = {
            confirmRoot = false
            scope.launch {
                localError = null
                try { workbench.deviceAccess.enableRoot() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { localError = failureText }
            }
        }) { Text(stringResource(R.string.device_mode_root_enable)) } },
        dismissButton = { WorkbenchDialogButton(onClick = { confirmRoot = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun DeviceModePanel(rootMode: Boolean, rootGranted: Boolean, busy: Boolean,
    error: String?, onBasic: () -> Unit, onRoot: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.device_mode_title), Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                DeviceModeRow(stringResource(R.string.device_mode_basic), stringResource(R.string.device_mode_basic_hint),
                    selected = !rootMode, enabled = !busy, glyph = Glyph.Shield, onClick = onBasic)
                GroupDivider()
                DeviceModeRow(stringResource(R.string.device_mode_root), stringResource(R.string.device_mode_root_hint),
                    selected = rootMode, enabled = !busy, glyph = Glyph.Unlocked, onClick = onRoot)
                if (rootMode || busy) Text(stringResource(when {
                    busy -> R.string.device_mode_authorizing
                    rootGranted -> R.string.device_mode_root_ready
                    else -> R.string.device_mode_root_unavailable
                }), Modifier.padding(start = 48.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rootGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!error.isNullOrBlank()) Text(error, Modifier.padding(start = 48.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(stringResource(R.string.device_mode_scope_hint), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceModeRow(title: String, description: String, selected: Boolean,
    enabled: Boolean, glyph: Glyph, onClick: () -> Unit) {
    RadioButtonPreference(title = title, summary = description, selected = selected,
        enabled = enabled, onClick = onClick, radioButtonLocation = RadioButtonLocation.End,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        startAction = { WorkbenchIcon(glyph, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

/** Screen preferences are global; SSH configuration belongs to a workspace. Neither grants system access. */
internal data class CapabilityPackagesUiModel(
    val projectName: String = "",
    val hasProject: Boolean = false,
    val screenEnabled: Boolean = false,
    val sshEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val browserEnabled: Boolean = false,
    val deviceShellEnabled: Boolean = false,
    val rootReady: Boolean = false,
)

@Composable
internal fun CapabilityPackagesSheet(model: CapabilityPackagesUiModel,
                                    onScreenEnabled: (Boolean) -> Unit, onSshEnabled: (Boolean) -> Unit,
                                    onScreenDetails: () -> Unit, onSshDetails: () -> Unit, onDismiss: () -> Unit, fullPage: Boolean = false,
                                    onBrowserEnabled: (Boolean) -> Unit = {}, onBrowserDetails: () -> Unit = {},
                                    contentPadding: PaddingValues = PaddingValues(0.dp),
                                    onDeviceShellEnabled: (Boolean) -> Unit = {}, onPermissions: () -> Unit = {}) {
    if (fullPage) {
        val back = rememberWorkbenchBackMotion(onBack = onDismiss)
        Column(Modifier.fillMaxSize().then(back.modifier).background(MaterialTheme.colorScheme.background)
            .padding(contentPadding).consumeWindowInsets(contentPadding).imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back.close) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.ai_extensions_back)) }
                Text(stringResource(R.string.capability_packages_title), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.capability_packages_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CapabilityPackagesPanel(model, onScreenEnabled, onSshEnabled, onScreenDetails, onSshDetails, onBrowserEnabled, onBrowserDetails, onDeviceShellEnabled, onPermissions)
            }
        }
        return
    }
    WorkbenchSheet(stringResource(R.string.capability_packages_title), onDismiss,
        subtitle = stringResource(R.string.capability_packages_subtitle)) {
        CapabilityPackagesPanel(model, onScreenEnabled, onSshEnabled, onScreenDetails, onSshDetails, onBrowserEnabled, onBrowserDetails, onDeviceShellEnabled, onPermissions)
    }
}

@Composable
internal fun CapabilityPackagesPanel(model: CapabilityPackagesUiModel,
                                    onScreenEnabled: (Boolean) -> Unit, onSshEnabled: (Boolean) -> Unit,
                                    onScreenDetails: () -> Unit, onSshDetails: () -> Unit,
                                    onBrowserEnabled: (Boolean) -> Unit = {}, onBrowserDetails: () -> Unit = {},
                                    onDeviceShellEnabled: (Boolean) -> Unit = {}, onPermissions: () -> Unit = {}) {
    CapabilitySectionTitle(stringResource(R.string.capability_category_device))
    if (!model.rootReady) {
        Text(stringResource(R.string.capability_device_root_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onPermissions, modifier = Modifier.heightIn(min = 48.dp)) {
            WorkbenchIcon(Glyph.Shield, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.device_permissions_open))
        }
    }
    CapabilityPackageCard(title = stringResource(R.string.capability_device_shell_title),
        description = stringResource(R.string.capability_device_shell_description),
        hint = stringResource(R.string.capability_device_shell_hint), configureLabel = stringResource(R.string.capability_device_shell_configure), glyph = Glyph.Terminal,
        scopeLabel = stringResource(R.string.capability_screen_global), enabled = model.deviceShellEnabled,
        busy = model.busy, onEnabled = onDeviceShellEnabled, onDetails = onPermissions)
    CapabilityPackageCard(title = stringResource(R.string.virtual_screen_title),
        description = stringResource(R.string.virtual_screen_description),
        hint = stringResource(R.string.virtual_screen_hint), configureLabel = stringResource(R.string.virtual_screen_configure), glyph = Glyph.Environment,
        scopeLabel = stringResource(R.string.capability_screen_global), enabled = model.screenEnabled, busy = model.busy,
        onEnabled = { if (it && !model.rootReady) onPermissions() else onScreenEnabled(it) }, onDetails = onScreenDetails)
    CapabilitySectionTitle(stringResource(R.string.capability_category_web))
    CapabilityPackageCard(title = stringResource(R.string.browser_title),
        description = stringResource(R.string.browser_tool_description), hint = stringResource(R.string.browser_capability_hint),
        configureLabel = stringResource(R.string.browser_capability_open), glyph = Glyph.Globe,
        scopeLabel = stringResource(R.string.capability_screen_global), enabled = model.browserEnabled,
        busy = model.busy, onEnabled = onBrowserEnabled, onDetails = onBrowserDetails)
    CapabilitySectionTitle(stringResource(R.string.capability_category_remote))
    CapabilityPackageCard(title = stringResource(R.string.capability_ssh_title),
        description = stringResource(R.string.capability_ssh_description),
        hint = stringResource(R.string.capability_ssh_hint), configureLabel = stringResource(R.string.capability_ssh_configure), glyph = Glyph.Terminal,
        scopeLabel = if (model.hasProject) stringResource(R.string.capability_packages_project, model.projectName) else stringResource(R.string.capability_ssh_workspace_required),
        enabled = model.sshEnabled, busy = model.busy || !model.hasProject, onEnabled = onSshEnabled, onDetails = onSshDetails)
    CapabilityNotice(stringResource(R.string.capability_packages_loading_hint))
    model.error?.let { CapabilityNotice(it, error = true) }
}

@Composable
private fun CapabilitySectionTitle(title: String) {
    Text(title, Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CapabilityPackageCard(title: String, description: String, hint: String, configureLabel: String, glyph: Glyph,
                                  scopeLabel: String, enabled: Boolean, busy: Boolean, onEnabled: (Boolean) -> Unit, onDetails: () -> Unit) {
    WorkbenchCard(cornerRadius = 12.dp,
        border = if (enabled) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)) else null) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(glyph, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(scopeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkbenchSwitch(checked = enabled, onCheckedChange = onEnabled, enabled = !busy,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = title })
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Opening settings does not mutate the package selection. A save in
                // another card must not dim and re-enable this navigation button.
                WorkbenchSecondaryButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(configureLabel)
                }
            }
        }
    }
}

@Composable
internal fun CapabilityNotice(text: String, error: Boolean = false) {
    WorkbenchCard(cornerRadius = 12.dp, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

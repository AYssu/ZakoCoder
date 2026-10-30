package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

/** Pure UI projection: enabling a package is a project preference, never a system permission grant. */
internal data class CapabilityPackagesUiModel(
    val projectName: String = "",
    val hasProject: Boolean = false,
    val screenEnabled: Boolean = false,
    val sshEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun CapabilityPackagesSheet(model: CapabilityPackagesUiModel,
                                    onScreenEnabled: (Boolean) -> Unit, onSshEnabled: (Boolean) -> Unit,
                                    onScreenDetails: () -> Unit, onSshDetails: () -> Unit, onDismiss: () -> Unit) {
    WorkbenchSheet(stringResource(R.string.capability_packages_title), onDismiss,
        subtitle = stringResource(R.string.capability_packages_subtitle)) {
        CapabilityPackagesPanel(model, onScreenEnabled, onSshEnabled, onScreenDetails, onSshDetails)
    }
}

@Composable
internal fun CapabilityPackagesPanel(model: CapabilityPackagesUiModel,
                                    onScreenEnabled: (Boolean) -> Unit, onSshEnabled: (Boolean) -> Unit,
                                    onScreenDetails: () -> Unit, onSshDetails: () -> Unit) {
    if (!model.hasProject) {
        CapabilityNotice(stringResource(R.string.capability_packages_no_project))
        return
    }
    Text(stringResource(R.string.capability_packages_project, model.projectName),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    CapabilityPackageCard(title = stringResource(R.string.capability_screen_title),
        description = stringResource(R.string.capability_screen_description),
        hint = stringResource(R.string.capability_screen_hint), configureLabel = stringResource(R.string.capability_screen_configure), glyph = Glyph.Environment,
        enabled = model.screenEnabled, busy = model.busy, onEnabled = onScreenEnabled, onDetails = onScreenDetails)
    CapabilityPackageCard(title = stringResource(R.string.capability_ssh_title),
        description = stringResource(R.string.capability_ssh_description),
        hint = stringResource(R.string.capability_ssh_hint), configureLabel = stringResource(R.string.capability_ssh_configure), glyph = Glyph.Terminal,
        enabled = model.sshEnabled, busy = model.busy, onEnabled = onSshEnabled, onDetails = onSshDetails)
    CapabilityNotice(stringResource(R.string.capability_packages_loading_hint))
    model.error?.let { CapabilityNotice(it, error = true) }
}

@Composable
private fun CapabilityPackageCard(title: String, description: String, hint: String, configureLabel: String, glyph: Glyph,
                                  enabled: Boolean, busy: Boolean, onEnabled: (Boolean) -> Unit, onDetails: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(glyph, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(if (enabled) R.string.capability_packages_enabled else R.string.capability_packages_disabled),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.capability_packages_project_only), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled, enabled = !busy,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = title },
                    thumbContent = null)
            }
            if (enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onDetails, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(configureLabel)
                }
            }
        }
    }
}

@Composable
internal fun CapabilityNotice(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

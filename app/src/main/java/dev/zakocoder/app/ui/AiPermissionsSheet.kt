package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*

@Composable
internal fun AiPermissionsSheet(workbench: Workbench, projectName: String, dismiss: () -> Unit) {
    val state by workbench.ai.state.collectAsStateWithLifecycle()
    var confirming by remember(state.projectId) { mutableStateOf<AiPolicy?>(null) }
    var confirmingPreset by remember(state.projectId) { mutableStateOf(false) }
    var advanced by remember(state.projectId) { mutableStateOf(false) }
    val enabled = !state.permissionsLoading && !state.permissionsSaving && !state.restoring
    val currentPreset = AiPermissionPresets.matching(state.policy)
    fun choose(policy: AiPolicy, dangerous: Boolean) { if (dangerous) confirming = policy else workbench.ai.setPolicy(policy) }
    WorkbenchSheet(stringResource(R.string.ai_permissions), dismiss) {
        Text(projectName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_permission_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.permissionsLoading || state.permissionsSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_permission_preset_current, stringResource(when (currentPreset) {
            AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal
            AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated
            null -> R.string.ai_permission_preset_custom
        })), style = MaterialTheme.typography.titleSmall)
        AiPermissionPreset.entries.forEach { preset ->
            PermissionPresetRow(preset, currentPreset == preset, enabled) {
                val policy = AiPermissionPresets.apply(state.policy, preset)
                if (preset == AiPermissionPreset.Elevated) {
                    confirmingPreset = true
                    confirming = policy
                } else workbench.ai.setPolicy(policy)
            }
        }
        val denied = AiTool.entries.count { state.policy.permission(it) == AiPermission.Deny }
        if (denied > 0) Text(stringResource(R.string.ai_permission_preset_denied, denied),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (advanced) R.string.ai_permission_advanced_hide else R.string.ai_permission_advanced_show))
        }
        if (advanced) {
            PermissionRow(stringResource(R.string.ai_default_permission), state.policy.default, null, enabled) { permission ->
                val value = permission ?: AiPermission.Ask
                confirmingPreset = false
                choose(state.policy.copy(default = value), value == AiPermission.Allow)
            }
            HorizontalDivider()
            AiTool.entries.forEach { tool ->
                PermissionRow(stringResource(tool.label), state.policy.overrides[tool], state.policy.permission(tool), enabled) { value ->
                    val overrides = state.policy.overrides.toMutableMap().apply { if (value == null) remove(tool) else put(tool, value) }
                    confirmingPreset = false
                    choose(state.policy.copy(overrides = overrides), tool.writes && (value ?: state.policy.default) == AiPermission.Allow)
                }
            }
        }
        Text(stringResource(R.string.ai_permission_running_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
    }
    confirming?.let { policy -> AlertDialog(onDismissRequest = { confirming = null }, title = { Text(stringResource(R.string.ai_auto_allow_title)) },
        text = { Text(if (confirmingPreset) stringResource(R.string.ai_permission_preset_warning, projectName) else stringResource(R.string.ai_auto_allow_warning)) },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirming = null; workbench.ai.setPolicy(policy) }) { Text(stringResource(R.string.ai_auto_allow_confirm)) } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun PermissionPresetRow(preset: AiPermissionPreset, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Surface(onClick = choose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.RadioButton }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (preset == AiPermissionPreset.Normal) R.string.ai_permission_preset_normal else R.string.ai_permission_preset_elevated),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (preset == AiPermissionPreset.Normal) R.string.ai_permission_preset_normal_desc else R.string.ai_permission_preset_elevated_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, choice: AiPermission?, effective: AiPermission?, enabled: Boolean, update: (AiPermission?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val permissionDescription = stringResource(R.string.ai_set_tool_permission, title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (effective != null) Text(stringResource(R.string.ai_effective_rule, stringResource(effective.label)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = permissionDescription }) { Text(stringResource(choice?.label ?: R.string.ai_follow_default)) }
            DropdownMenu(menu, { menu = false }) {
                if (effective != null) DropdownMenuItem(text = { Text(stringResource(R.string.ai_follow_default)) }, onClick = { menu = false; update(null) })
                AiPermission.entries.forEach { permission -> DropdownMenuItem(text = { Text(stringResource(permission.label)) }, onClick = { menu = false; update(permission) }) }
            }
        }
    }
}

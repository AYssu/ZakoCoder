package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.basic.RadioButton
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
    var confirmingPreset by remember(state.projectId) { mutableStateOf<AiPermissionPreset?>(null) }
    var advanced by remember(state.projectId) { mutableStateOf(false) }
    var pendingSelection by remember(state.projectId) { mutableStateOf<AiPolicy?>(null) }
    fun saveSelection(policy: AiPolicy) {
        if (policy == state.policy) { dismiss(); return }
        pendingSelection = policy
        workbench.ai.setPolicy(policy)
    }
    LaunchedEffect(state.permissionsSaving, state.policy, pendingSelection) {
        if (pendingSelection != null && !state.permissionsSaving && state.policy == pendingSelection) dismiss()
    }
    val enabled = !state.permissionsLoading && !state.permissionsSaving && !state.restoring
    val currentPreset = AiPermissionPresets.matching(state.policy)
    fun choose(policy: AiPolicy, dangerous: Boolean) { if (dangerous) confirming = policy else saveSelection(policy) }
    WorkbenchSheet(stringResource(R.string.ai_permissions), dismiss) {
        Text(projectName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_permission_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.permissionsLoading || state.permissionsSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_permission_preset_current, stringResource(when (currentPreset) {
            AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal
            AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated
            AiPermissionPreset.Yolo -> R.string.ai_permission_preset_yolo
            AiPermissionPreset.ReadOnly -> R.string.ai_permission_preset_readonly
            null -> R.string.ai_permission_preset_custom
        })), style = MaterialTheme.typography.titleSmall)
        AiPermissionPreset.entries.forEach { preset ->
            PermissionPresetRow(preset, currentPreset == preset, enabled) {
                if (currentPreset == preset) { dismiss(); return@PermissionPresetRow }
                val policy = AiPermissionPresets.apply(state.policy, preset)
                if (preset != AiPermissionPreset.ReadOnly) {
                    confirmingPreset = preset
                    confirming = policy
                } else saveSelection(policy)
            }
        }
        val denied = AiTool.entries.filter { it != AiTool.LoadSkill }.count { state.policy.permission(it) == AiPermission.Deny }
        if (state.policy.yolo) Text(stringResource(R.string.ai_permission_yolo_boundary),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (denied > 0) Text(stringResource(R.string.ai_permission_retained_denials, denied),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (advanced) R.string.ai_permission_advanced_hide else R.string.ai_permission_advanced_show))
        }
        if (advanced) {
            PermissionRow(stringResource(R.string.ai_default_permission), state.policy.default, null, enabled) { permission ->
                val value = permission ?: AiPermission.Ask
                confirmingPreset = null
                choose(state.policy.copy(default = value, yolo = false, mode = null), value == AiPermission.Allow)
            }
            HorizontalDivider()
            AiTool.entries.filter { it != AiTool.LoadSkill }.forEach { tool ->
                PermissionRow(stringResource(tool.label), state.policy.overrides[tool], state.policy.permission(tool), enabled) { value ->
                    val overrides = state.policy.overrides.toMutableMap().apply { if (value == null) remove(tool) else put(tool, value) }
                    confirmingPreset = null
                    choose(state.policy.copy(overrides = overrides, yolo = false, mode = null), tool.writes && (value ?: state.policy.default) == AiPermission.Allow)
                }
            }
        }
        if (pendingSelection != null && !state.permissionsSaving && state.policy != pendingSelection && state.error.isNotBlank()) {
            Text(stringResource(R.string.ai_permission_save_error), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.ai_permission_running_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
    }
    confirming?.let { policy -> WorkbenchAlertDialog(onDismissRequest = { confirming = null }, title = { Text(stringResource(if (confirmingPreset == AiPermissionPreset.Yolo) R.string.ai_permission_yolo_confirm_title else R.string.ai_auto_allow_title)) },
        text = { Text(text = when (confirmingPreset) {
            AiPermissionPreset.Yolo -> stringResource(R.string.ai_permission_yolo_warning, projectName)
            AiPermissionPreset.Normal -> stringResource(R.string.ai_permission_normal_warning, projectName)
            AiPermissionPreset.Elevated -> stringResource(R.string.ai_permission_preset_warning, projectName)
            else -> stringResource(R.string.ai_auto_allow_warning)
        }) },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirming = null; saveSelection(policy) }) { Text(stringResource(R.string.ai_auto_allow_confirm)) } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun PermissionPresetRow(preset: AiPermissionPreset, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    WorkbenchCard(onClick = choose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.RadioButton }, cornerRadius = 12.dp,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIcon(permissionModeGlyph(preset), Modifier.size(20.dp), tint = permissionModeTint(preset))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(when (preset) {
                    AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal
                    AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated
                    AiPermissionPreset.Yolo -> R.string.ai_permission_preset_yolo
                    AiPermissionPreset.ReadOnly -> R.string.ai_permission_preset_readonly
                }),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (preset) {
                    AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal_desc
                    AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated_desc
                    AiPermissionPreset.Yolo -> R.string.ai_permission_preset_yolo_desc
                    AiPermissionPreset.ReadOnly -> R.string.ai_permission_preset_readonly_desc
                }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

internal fun permissionModeGlyph(preset: AiPermissionPreset?): Glyph = when (preset) {
    AiPermissionPreset.Normal -> Glyph.ShieldAsk
    AiPermissionPreset.Elevated -> Glyph.ShieldAuto
    AiPermissionPreset.Yolo -> Glyph.Unlocked
    AiPermissionPreset.ReadOnly -> Glyph.ReadOnly
    null -> Glyph.Shield
}

internal fun permissionModeLabel(preset: AiPermissionPreset?): Int = when (preset) {
    AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal
    AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated
    AiPermissionPreset.Yolo -> R.string.ai_permission_preset_yolo
    AiPermissionPreset.ReadOnly -> R.string.ai_permission_preset_readonly
    null -> R.string.ai_permission_preset_custom
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

@Composable
internal fun permissionModeTint(preset: AiPermissionPreset?): androidx.compose.ui.graphics.Color {
    val palette = dev.zakocoder.app.ui.theme.LocalWorkbenchPalette.current
    return when (preset) {
        AiPermissionPreset.Elevated -> palette.successText
        AiPermissionPreset.Yolo -> dev.zakocoder.app.ui.theme.PermissionModeColors.fullAccess(LocalWorkbenchDark.current)
        AiPermissionPreset.ReadOnly -> dev.zakocoder.app.ui.theme.PermissionModeColors.readOnly(LocalWorkbenchDark.current)
        else -> palette.textSecondary
    }
}

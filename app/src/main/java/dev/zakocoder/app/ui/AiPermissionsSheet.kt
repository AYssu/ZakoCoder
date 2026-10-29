package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*

@Composable
internal fun AiPermissionsSheet(workbench: Workbench, projectName: String, dismiss: () -> Unit) {
    val state by workbench.ai.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<AiPolicy?>(null) }
    fun choose(policy: AiPolicy, dangerous: Boolean) { if (dangerous) confirming = policy else workbench.ai.setPolicy(policy) }
    WorkbenchSheet(stringResource(R.string.ai_permissions), dismiss) {
        Text(projectName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_permission_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.permissionsLoading || state.permissionsSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
        PermissionRow(stringResource(R.string.ai_default_permission), state.policy.default, null, !state.permissionsLoading && !state.permissionsSaving) { permission ->
            val value = permission ?: AiPermission.Ask
            choose(state.policy.copy(default = value), value == AiPermission.Allow)
        }
        HorizontalDivider()
        AiTool.entries.forEach { tool ->
            PermissionRow(stringResource(tool.label), state.policy.overrides[tool], state.policy.permission(tool), !state.permissionsLoading && !state.permissionsSaving) { value ->
                val overrides = state.policy.overrides.toMutableMap().apply { if (value == null) remove(tool) else put(tool, value) }
                choose(state.policy.copy(overrides = overrides), tool.writes && (value ?: state.policy.default) == AiPermission.Allow)
            }
        }
        Text(stringResource(R.string.ai_permission_running_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = dismiss) { Text(stringResource(R.string.close)) }
    }
    confirming?.let { policy -> AlertDialog(onDismissRequest = { confirming = null }, title = { Text(stringResource(R.string.ai_auto_allow_title)) },
        text = { Text(stringResource(R.string.ai_auto_allow_warning)) },
        confirmButton = { TextButton(onClick = { confirming = null; workbench.ai.setPolicy(policy) }) { Text(stringResource(R.string.ai_auto_allow_confirm)) } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } }) }
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

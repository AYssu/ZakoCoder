package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*

@Composable
internal fun AiPermissionsSheet(workbench: Workbench, projectName: String, dismiss: () -> Unit) {
    val state by workbench.ai.state.collectAsStateWithLifecycle()
    if (!LocalWorkbenchPageInteractive.current) return
    var closing by remember(state.projectId) { mutableStateOf(false) }
    fun close() { closing = true }
    var confirming by remember(state.projectId) { mutableStateOf<AiPolicy?>(null) }
    var confirmingPreset by remember(state.projectId) { mutableStateOf<AiPermissionPreset?>(null) }
    var advanced by remember(state.projectId) { mutableStateOf(false) }
    var pendingSelection by remember(state.projectId) { mutableStateOf<AiPolicy?>(null) }
    fun saveSelection(policy: AiPolicy) {
        if (policy == state.policy) { close(); return }
        pendingSelection = policy
        workbench.ai.setPolicy(policy)
    }
    LaunchedEffect(state.permissionsSaving, state.policy, pendingSelection) {
        if (pendingSelection != null && !state.permissionsSaving && state.policy == pendingSelection) close()
    }
    val enabled = !closing && !state.permissionsLoading && !state.permissionsSaving && !state.restoring
    val currentPreset = AiPermissionPresets.matching(state.policy)
    fun choose(policy: AiPolicy, dangerous: Boolean) { if (dangerous) confirming = policy else saveSelection(policy) }
    PermissionPopover(closing, onDismiss = { if (advanced) advanced = false else close() }, onClosed = dismiss) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (advanced) IconButton(onClick = { advanced = false }, modifier = Modifier.size(44.dp)) {
                WorkbenchIcon(Glyph.Back, size = 16.dp, description = stringResource(R.string.ai_permission_advanced_hide))
            }
            Text(stringResource(if (advanced) R.string.ai_permission_advanced_show else R.string.ai_permissions),
                Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!advanced) IconButton(onClick = { advanced = true }, modifier = Modifier.size(44.dp)
                .testTag("ai-permission-more")) {
                WorkbenchIcon(Glyph.More, size = 18.dp, description = stringResource(R.string.ai_permission_more))
            }
        }
        if (state.permissionsLoading || state.permissionsSaving) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (advanced) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).testTag("ai-permission-advanced"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(projectName, fontSize = 11.sp)
                Text(stringResource(R.string.ai_permission_scope), fontSize = 10.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_permission_preset_current, stringResource(permissionModeLabel(currentPreset))),
                    fontSize = 11.sp)
                val denied = AiTool.entries.filter { it != AiTool.LoadSkill }.count { state.policy.permission(it) == AiPermission.Deny }
                if (state.policy.yolo) Text(stringResource(R.string.ai_permission_yolo_boundary), fontSize = 10.sp,
                    lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (denied > 0) Text(stringResource(R.string.ai_permission_retained_denials, denied), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PermissionRow(stringResource(R.string.ai_default_permission), state.policy.default, null, enabled) { permission ->
                    val value = permission ?: AiPermission.Ask
                    confirmingPreset = null
                    choose(state.policy.copy(default = value, yolo = false, mode = null), value == AiPermission.Allow)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                AiTool.entries.filter { it != AiTool.LoadSkill }.forEach { tool ->
                    PermissionRow(stringResource(tool.label), state.policy.overrides[tool], state.policy.permission(tool), enabled) { value ->
                        val overrides = state.policy.overrides.toMutableMap().apply { if (value == null) remove(tool) else put(tool, value) }
                        confirmingPreset = null
                        choose(state.policy.copy(overrides = overrides, yolo = false, mode = null), tool.writes && (value ?: state.policy.default) == AiPermission.Allow)
                    }
                }
                Text(stringResource(R.string.ai_permission_running_note), fontSize = 10.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("ai-permission-presets")) {
                AiPermissionPreset.entries.forEach { preset ->
                    PermissionPresetRow(preset, currentPreset == preset, enabled) {
                        if (currentPreset == preset) { close(); return@PermissionPresetRow }
                        val policy = AiPermissionPresets.apply(state.policy, preset)
                        if (preset != AiPermissionPreset.ReadOnly) {
                            confirmingPreset = preset
                            confirming = policy
                        } else saveSelection(policy)
                    }
                }
            }
        }
        if (pendingSelection != null && !state.permissionsSaving && state.policy != pendingSelection && state.error.isNotBlank()) {
            Text(stringResource(R.string.ai_permission_save_error), Modifier.padding(12.dp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error)
        }
    }
    confirming?.let { policy -> WorkbenchAlertDialog(onDismissRequest = { confirming = null }, title = { Text(stringResource(if (confirmingPreset == AiPermissionPreset.Yolo) R.string.ai_permission_yolo_confirm_title else R.string.ai_auto_allow_title)) },
        text = { Text(text = when (confirmingPreset) {
            AiPermissionPreset.Yolo -> stringResource(R.string.ai_permission_yolo_warning, projectName)
            AiPermissionPreset.Normal -> stringResource(R.string.ai_permission_normal_warning, projectName)
            AiPermissionPreset.Elevated -> stringResource(R.string.ai_permission_preset_warning, projectName)
            else -> stringResource(R.string.ai_auto_allow_warning)
        }) },
        confirmButton = { WorkbenchDialogButton(enabled = enabled, onClick = { confirming = null; saveSelection(policy) }) { Text(stringResource(R.string.ai_auto_allow_confirm)) } },
        dismissButton = { WorkbenchDialogButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun PermissionPresetRow(preset: AiPermissionPreset, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .semantics { this.selected = selected; role = Role.RadioButton }
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = choose)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkbenchIcon(permissionModeGlyph(preset), Modifier.size(16.dp), tint = permissionModeTint(preset))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(permissionModeLabel(preset)), fontSize = 12.sp, lineHeight = 16.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when (preset) {
                AiPermissionPreset.Normal -> R.string.ai_permission_preset_normal_desc
                AiPermissionPreset.Elevated -> R.string.ai_permission_preset_elevated_desc
                AiPermissionPreset.Yolo -> R.string.ai_permission_preset_yolo_desc
                AiPermissionPreset.ReadOnly -> R.string.ai_permission_preset_readonly_desc
            }), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (selected) WorkbenchIcon(Glyph.Check, size = 14.dp, tint = MaterialTheme.colorScheme.primary)
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

internal fun permissionModeShortLabel(preset: AiPermissionPreset?): Int = when (preset) {
    AiPermissionPreset.Normal -> R.string.ai_composer_permission_ask
    AiPermissionPreset.Elevated -> R.string.ai_composer_permission_auto
    AiPermissionPreset.Yolo -> R.string.ai_composer_permission_full
    AiPermissionPreset.ReadOnly -> R.string.ai_composer_permission_only
    null -> R.string.ai_composer_permission_custom
}

/** Keep the compact mode visible without reducing its touch target or spoken explanation. */
@Composable
internal fun AiComposerPermissionButton(
    preset: AiPermissionPreset?,
    overridesCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(permissionModeLabel(preset))
    val state = stringResource(R.string.ai_permission_current, label, overridesCount)
    val tint = permissionModeTint(preset)
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
        modifier = modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clearAndSetSemantics {
            contentDescription = label
            stateDescription = state
            role = Role.Button
            if (!enabled) disabled()
            onClick { if (enabled) { onClick(); true } else false }
        }) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WorkbenchIcon(permissionModeGlyph(preset), Modifier.size(14.dp),
                    tint = tint.copy(alpha = if (enabled) 1f else .38f))
                Text(stringResource(permissionModeShortLabel(preset)), fontSize = 10.sp,
                    lineHeight = 14.sp, maxLines = 1,
                    color = tint.copy(alpha = if (enabled) 1f else .38f))
            }
    }
}

@Composable
private fun PermissionRow(title: String, choice: AiPermission?, effective: AiPermission?, enabled: Boolean, update: (AiPermission?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val permissionDescription = stringResource(R.string.ai_set_tool_permission, title)
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp)
            if (effective != null) Text(stringResource(R.string.ai_effective_rule, stringResource(effective.label)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.semantics { contentDescription = permissionDescription }) { Text(stringResource(choice?.label ?: R.string.ai_follow_default), fontSize = 10.sp) }
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
        AiPermissionPreset.ReadOnly -> if (LocalWorkbenchDark.current)
            androidx.compose.ui.graphics.Color(0xFFC6C6CD)
        else androidx.compose.ui.graphics.Color(0xFF6B6B73)
        AiPermissionPreset.Yolo -> if (LocalWorkbenchDark.current)
            dev.zakocoder.app.ui.theme.PermissionModeColors.readOnly(true)
        else androidx.compose.ui.graphics.Color(0xFF8C6D0D)
        else -> palette.textSecondary
    }
}

/** Keep the real source page visible; only the floating permission card owns its scrolling. */
@Composable
private fun PermissionPopover(closing: Boolean, onDismiss: () -> Unit, onClosed: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val width = with(density) { window.width.toDp() - 32.dp }.coerceAtMost(280.dp)
    val height = with(density) { (window.height * .6f).toDp() }
    val gap = with(density) { 4.dp.roundToPx() }
    val position = remember(gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
            popupContentSize: IntSize): IntOffset {
            val x = (anchorBounds.left - gap * 2).coerceIn(gap, maxOf(gap, windowSize.width - popupContentSize.width - gap))
            val above = anchorBounds.top - popupContentSize.height - gap
            val y = (if (above >= gap) above else anchorBounds.bottom + gap)
                .coerceIn(gap, maxOf(gap, windowSize.height - popupContentSize.height - gap))
            return IntOffset(x, y)
        }
    } }
    val progress = remember { Animatable(0f) }
    val latestClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(closing) {
        progress.animateTo(if (closing) 0f else 1f, tween(160))
        if (closing) latestClosed()
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.graphicsLayer {
            alpha = progress.value
            scaleX = .97f + .03f * progress.value; scaleY = scaleX
            translationY = (1 - progress.value) * 5.dp.toPx()
        }.padding(8.dp)) {
            Surface(Modifier.width(width).heightIn(max = height).testTag("ai-permission-popover"),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.padding(bottom = 8.dp), content = content)
            }
        }
    }
}

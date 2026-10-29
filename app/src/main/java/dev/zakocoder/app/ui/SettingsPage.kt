package dev.zakocoder.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.zakocoder.app.settings.TerminalShell
import dev.zakocoder.app.runtime.ToolStatus
import dev.zakocoder.app.runtime.ToolBundles
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.settings.DevelopmentNotifications
import dev.zakocoder.app.settings.RunningNotificationStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.runtime.StorageUsage
import dev.zakocoder.app.settings.AptSource

@Composable
internal fun DetailGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, Modifier.padding(start = 2.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = cardBorder(), shadowElevation = cardElevation()) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun DetailRow(title: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 160.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onClick != null) WorkbenchIcon(Glyph.Next, Modifier.size(17.dp), tint = TertiaryText)
    }
}

@Composable
internal fun GroupDivider() { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF222A34) else androidx.compose.ui.graphics.Color(0xFFF1F5F9)) }

/** Settings keeps the prototype's smaller, muted grouping separate from detail-sheet headings. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp, top = 4.dp), fontSize = 11.5.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.Normal, letterSpacing = .3.sp, color = TertiaryText)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            border = cardBorder(), shadowElevation = if (LocalWorkbenchDark.current) 0.dp else .5.dp) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, enabled: Boolean = true, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        Text(value, Modifier.widthIn(max = 165.dp), fontSize = 12.5.sp, lineHeight = 18.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = TertiaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onClick != null) WorkbenchIcon(Glyph.Next, Modifier.size(12.dp), tint = if (LocalWorkbenchDark.current) Color(0xFF4A5563) else Color(0xFFC3CEDD))
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }

@Composable
internal fun SettingsPage(workbench: Workbench, state: WorkbenchState, onModels: () -> Unit) {
    val context = LocalContext.current
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val notifications = remember(context) { DevelopmentNotifications(context) }
    var notificationStatus by remember { mutableStateOf(notifications.status()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationStatus = notifications.status() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(notifications, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) notificationStatus = notifications.status() }
        lifecycleOwner.lifecycle.addObserver(observer)
        notificationStatus = notifications.status()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var aiPermissions by remember { mutableStateOf(false) }
    if (aiPermissions) AiPermissionsSheet(workbench, state.projects.find { it.id == workbench.ai.state.value.projectId }?.name.orEmpty(), { aiPermissions = false })
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmZsh by remember { mutableStateOf(false) }
    val notConfigured = stringResource(R.string.not_configured)
    val settings = state.settings
    val unavailable = stringResource(R.string.storage_unavailable)
    val notificationSettingsUnavailable = stringResource(R.string.notification_settings_unavailable)
    Column(Modifier.fillMaxSize()) {
        PageHeading(stringResource(R.string.nav_settings))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 0.dp, bottom = 20.dp)) {
        item { SettingsGroup(stringResource(R.string.nav_ai)) {
            SettingsRow(stringResource(R.string.ai_settings), models.catalog.active?.name ?: notConfigured, onClick = onModels)
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_permissions), state.project?.name ?: stringResource(R.string.select_project_first),
                enabled = state.selectedId != null && !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving,
                onClick = { workbench.ai.select(state.selectedId); if (workbench.ai.state.value.projectId == state.selectedId) aiPermissions = true })
        } }
        item { SettingsGroup(stringResource(R.string.nav_terminal)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.terminal_font), fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.terminal_output_font), fontSize = 11.sp, lineHeight = 15.sp, color = TertiaryText)
                }
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { workbench.saveSettings(settings.copy(terminalFontSp = settings.terminalFontSp - 1)) }, modifier = Modifier.size(48.dp), enabled = !state.busy && settings.terminalFontSp > 10) {
                            WorkbenchIcon(Glyph.Minus, modifier = Modifier.size(12.dp), description = stringResource(R.string.decrease_font))
                        }
                        Text(stringResource(R.string.font_size_compact, settings.terminalFontSp), Modifier.width(44.dp), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { workbench.saveSettings(settings.copy(terminalFontSp = settings.terminalFontSp + 1)) }, modifier = Modifier.size(48.dp), enabled = !state.busy && settings.terminalFontSp < 28) {
                            WorkbenchIcon(Glyph.Add, modifier = Modifier.size(12.dp), description = stringResource(R.string.increase_font))
                        }
                    }
                }
            }
            SettingsDivider()
            SettingsRow(stringResource(R.string.extra_path), settings.additionalPath.ifEmpty { notConfigured }, enabled = !state.busy, onClick = { editing = "path" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.terminal_shell), stringResource(if (settings.terminalShell == TerminalShell.Bash) R.string.shell_bash else R.string.shell_zsh), enabled = !state.busy, onClick = { editing = "shell" })
        } }
        item { SettingsGroup(stringResource(R.string.network_settings)) {
            SettingsRow(stringResource(R.string.apt_source), stringResource(if (settings.aptSource == AptSource.Ubuntu) R.string.apt_official else R.string.apt_tsinghua), enabled = !state.busy, onClick = { editing = "source" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.dns_short), settings.dns.split(',').joinToString(", ") { it.trim() }, mono = true, enabled = !state.busy, onClick = { editing = "dns" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.proxy_short), settings.proxy.ifEmpty { notConfigured }, enabled = !state.busy, onClick = { editing = "proxy" })
        } }
        item { SettingsGroup(stringResource(R.string.storage_title)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.storage_used), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    Text(state.usedBytes?.let(StorageUsage::format) ?: unavailable, fontSize = 12.sp, lineHeight = 18.sp, color = TertiaryText)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val cacheScope = stringResource(R.string.cache_scope)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
                        TextButton(onClick = workbench::clearCache, enabled = !state.busy, shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            modifier = Modifier.semantics { stateDescription = cacheScope }.defaultMinSize(minHeight = 32.dp)) {
                            Text(stringResource(R.string.cache_compact_action), fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cache_compact_size, state.downloadCacheBytes?.let(StorageUsage::format) ?: unavailable),
                        fontSize = 11.sp, lineHeight = 16.sp, color = TertiaryText)
                }
            }
        } }
        item { SettingsGroup(stringResource(R.string.application_settings)) {
            SettingsRow(stringResource(R.string.running_notifications), stringResource(notificationStatus.label()), onClick = { editing = "notifications" })
        } }
        item {
            TextButton(onClick = { editing = "about" }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 0.dp)) {
                Text(stringResource(R.string.settings_version_footer, stringResource(R.string.app_version)), fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, color = TertiaryText)
            }
        }
    }
    }
    if (confirmZsh) AlertDialog(onDismissRequest = { confirmZsh = false }, title = { Text(stringResource(R.string.install_zsh)) },
        text = { Text(stringResource(R.string.package_confirmation, ToolBundles.packages(setOf("zsh"), state.bundleTools).joinToString(", "))) },
        confirmButton = { TextButton(onClick = { confirmZsh = false; editing = null; workbench.installToolBundles(setOf("zsh")) }) { Text(stringResource(R.string.run)) } },
        dismissButton = { TextButton(onClick = { confirmZsh = false }) { Text(stringResource(R.string.cancel)) } })
    editing?.let { field ->
        var draft by remember(field, settings) { mutableStateOf(when (field) { "path" -> settings.additionalPath; "dns" -> settings.dns; "proxy" -> settings.proxy; else -> "" }) }
        var source by remember(field, settings) { mutableStateOf(settings.aptSource) }
        var shell by remember(field, settings) { mutableStateOf(settings.terminalShell) }
        var error by remember(field) { mutableStateOf<String?>(null) }
        val title = stringResource(when (field) { "shell" -> R.string.terminal_shell; "path" -> R.string.extra_path; "dns" -> R.string.dns_short; "proxy" -> R.string.proxy_short; "source" -> R.string.apt_source; "notifications" -> R.string.running_notifications; else -> R.string.about_title })
        val informational = field == "about" || field == "notifications"
        WorkbenchSheet(title, { editing = null }) {
                when (field) {
                    "shell" -> {
                        TerminalShell.entries.forEach { option ->
                            val ready = option == TerminalShell.Bash || state.bundleTools.any { it.id == "zsh" && it.status == ToolStatus.Ready }
                            Row(Modifier.fillMaxWidth().clickable(enabled = ready) { shell = option }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = shell == option, onClick = { shell = option }, enabled = ready)
                                Text(stringResource(if (option == TerminalShell.Bash) R.string.shell_bash else R.string.shell_zsh), Modifier.weight(1f))
                                if (!ready) Text(stringResource(R.string.bundle_missing), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                            }
                        }
                        if (state.bundleTools.none { it.id == "zsh" && it.status == ToolStatus.Ready }) {
                            OutlinedButton(onClick = { confirmZsh = true }, enabled = state.installed && !state.busy && !state.checkingTools, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.install_zsh))
                            }
                        }
                    }
                    "source" -> AptSource.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { source = option }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = source == option, onClick = { source = option })
                            Text(stringResource(if (option == AptSource.Ubuntu) R.string.apt_official else R.string.apt_tsinghua))
                        }
                    }
                    "about" -> {
                        Text(stringResource(R.string.about_description))
                        Text(stringResource(R.string.validation_artifacts_notice), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.licenses_description))
                    }
                    "notifications" -> {
                        Text(stringResource(notificationStatus.label()), color = if (notificationStatus == RunningNotificationStatus.Available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.notification_help))
                        if (notificationStatus == RunningNotificationStatus.PermissionRequired && Build.VERSION.SDK_INT >= 33) OutlinedButton(
                            onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.allow_notifications)) }
                        OutlinedButton(onClick = { if (!notifications.openSettings()) error = notificationSettingsUnavailable }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notification_system_settings))
                        }
                    }
                    else -> OutlinedTextField(draft, { draft = it; error = null }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(when (field) { "path" -> R.string.path_label; "dns" -> R.string.dns_label; else -> R.string.proxy_label })) },
                        singleLine = true, isError = error != null)
                }
                if (!informational) Text(stringResource(when (field) {
                    "shell" -> R.string.shell_help; "source" -> R.string.source_help; "path" -> R.string.path_help;
                    "dns" -> R.string.dns_help; else -> R.string.proxy_help
                }), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = {
                if (informational) {
                    editing = null
                } else {
                    val candidate = when (field) { "shell" -> settings.copy(terminalShell = shell); "source" -> settings.copy(aptSource = source); "path" -> settings.copy(additionalPath = draft); "dns" -> settings.copy(dns = draft); else -> settings.copy(proxy = draft) }
                    try { workbench.validateSettings(candidate); workbench.saveSettings(candidate); editing = null }
                    catch (invalid: IllegalArgumentException) { error = invalid.message }
                }
            }, enabled = informational || !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (informational) R.string.close else R.string.save_settings)) }
            if (!informational) OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { editing = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (informational) R.string.close else R.string.cancel))
            }
        }
    }
}

internal fun RunningNotificationStatus.label(): Int = when (this) {
    RunningNotificationStatus.PermissionRequired -> R.string.notification_permission_required
    RunningNotificationStatus.AppBlocked -> R.string.notification_app_blocked
    RunningNotificationStatus.ChannelBlocked -> R.string.notification_channel_blocked
    RunningNotificationStatus.Available -> R.string.notifications_granted
    RunningNotificationStatus.Unknown -> R.string.notification_status_unknown
}

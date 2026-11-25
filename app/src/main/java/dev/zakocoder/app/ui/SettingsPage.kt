package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import dev.zakocoder.app.settings.TerminalShell
import dev.zakocoder.app.runtime.ToolStatus
import dev.zakocoder.app.runtime.ToolBundles
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.settings.DevelopmentNotifications
import dev.zakocoder.app.settings.RunningNotificationStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.theme.LocalWorkbenchPalette
import dev.zakocoder.app.ui.theme.Space
import dev.zakocoder.app.ui.theme.Type
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.runtime.StorageUsage
import dev.zakocoder.app.settings.AptSource
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent

private data class SettingsSaveAttempt(val accepted: Boolean, val messageBefore: String)

@Composable
internal fun DetailGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = Type.bodyStrong,
            color = MaterialTheme.colorScheme.onSurface)
        MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp,
            insideMargin = PaddingValues(0.dp), colors = MiuixCardDefaults.defaultColors(
                color = MaterialTheme.colorScheme.surface)) {
            content()
        }
    }
}

@Composable
internal fun DetailRow(title: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val click = rememberWorkbenchClickFeedback { onClick?.invoke() }
    if (onClick != null) {
        ArrowPreference(title = title, summary = value.takeIf { it.isNotEmpty() },
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp), onClick = click)
    } else {
        BasicComponent(title = title, summary = value.takeIf { it.isNotEmpty() },
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
internal fun GroupDivider() { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LocalWorkbenchPalette.current.divider) }

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = Space.xs).semantics { heading() }, style = Type.metaStrong,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp,
            insideMargin = PaddingValues(0.dp), colors = MiuixCardDefaults.defaultColors(
                color = MaterialTheme.colorScheme.surface)) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, glyph: Glyph = Glyph.Settings, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val tone = if (enabled) 1f else .45f
    val click = rememberWorkbenchClickFeedback { onClick?.invoke() }
    val icon: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                WorkbenchIcon(glyph, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = tone))
            }
        }
    }
    if (onClick != null) {
        ArrowPreference(title = title, summary = value.takeIf { it.isNotEmpty() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled,
            startAction = icon, onClick = click,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp))
    } else {
        BasicComponent(title = title, summary = value.takeIf { it.isNotEmpty() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled,
            startAction = icon,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant) }

@Composable
internal fun SettingsPage(workbench: Workbench, state: WorkbenchState, onModels: () -> Unit, onExtensions: () -> Unit = {}, onUpdates: () -> Unit = {}, onProxy: () -> Unit = {}, onSetup: () -> Unit = {}, onMemory: () -> Unit = {}, onBrowser: () -> Unit = {}) {
    val context = LocalContext.current
    val installedVersion = installedAppVersion()
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
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    if (usageOpen) {
        val usage by workbench.ai.usageStatistics.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { workbench.ai.refreshUsageStatistics() }
        AiFullScreen(stringResource(R.string.ai_usage_dashboard), { usageOpen = false }) {
            AiUsageDashboard(usage, state.projects.associate { it.id to it.name }, workbench.ai::refreshUsageStatistics)
        }
    }
    var sshLicenseOpen by remember { mutableStateOf(false) }
    if (sshLicenseOpen) {
        var licenseFailed by remember { mutableStateOf(false) }
        val license by produceState<String?>(initialValue = null) {
            try {
                value = withContext(Dispatchers.IO) {
                    context.assets.open("licenses/JSch-BSD.txt").bufferedReader().use { it.readText() }
                }
            } catch (_: java.io.IOException) { licenseFailed = true }
        }
        WorkbenchSheet(stringResource(R.string.ssh_license_title), { sshLicenseOpen = false }) {
            SshLicensePanel(license, licenseFailed)
        }
    }
    var capabilityPackagesOpen by remember { mutableStateOf(false) }
    if (capabilityPackagesOpen) CapabilityPackageBindings(workbench, state.selectedId, state.project?.name.orEmpty()) { capabilityPackagesOpen = false }
    var aiPermissions by remember { mutableStateOf(false) }
    if (aiPermissions) AiPermissionsSheet(workbench, state.projects.find { it.id == ai.projectId }?.name.orEmpty(), { aiPermissions = false })
    var appPermissionsOpen by rememberSaveable { mutableStateOf(false) }
    if (appPermissionsOpen) {
        DevicePermissionsPage(workbench, onBack = { appPermissionsOpen = false }, onToolPermissions = {
            workbench.ai.select(state.selectedId)
            if (workbench.ai.state.value.projectId == state.selectedId) { appPermissionsOpen = false; aiPermissions = true }
        }, toolsEnabled = state.selectedId != null && !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving,
            backgroundServiceRunning = state.backgroundServiceRunning, backgroundServiceError = state.backgroundServiceError)
    }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmZsh by remember { mutableStateOf(false) }
    val notConfigured = stringResource(R.string.not_configured)
    val settings = state.settings
    val unavailable = stringResource(R.string.storage_unavailable)
    val notificationSettingsUnavailable = stringResource(R.string.notification_settings_unavailable)
    val settingsSaveFailed = stringResource(R.string.settings_save_failed)
    val settingsSaved = stringResource(R.string.settings_saved)
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.settings_device_hint), Modifier.padding(bottom = 16.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 0.dp, bottom = 24.dp)) {
        item { SettingsGroup(stringResource(R.string.nav_ai)) {
            SettingsRow(stringResource(R.string.ai_settings), models.catalog.active?.name ?: notConfigured, glyph = Glyph.Ai, onClick = onModels)
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_usage_dashboard), stringResource(R.string.ai_usage_dashboard_hint), glyph = Glyph.History,
                onClick = { usageOpen = true })
            SettingsDivider()
            SettingsRow(stringResource(R.string.agent_memory_title), stringResource(R.string.agent_memory_intro), glyph = Glyph.History, onClick = onMemory)
            SettingsDivider()
            SettingsRow(stringResource(R.string.browser_title), stringResource(R.string.browser_tool_description), glyph = Glyph.Globe, onClick = onBrowser)
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_extensions), stringResource(R.string.ai_extensions_types), glyph = Glyph.Extensions, onClick = onExtensions)
        } }
        item { SettingsGroup(stringResource(R.string.screen_test_development_group)) {
            SettingsRow(stringResource(R.string.capability_packages_title), stringResource(R.string.capability_packages_settings_hint),
                glyph = Glyph.Extensions, onClick = { capabilityPackagesOpen = true })
        } }
        item { SettingsGroup(stringResource(R.string.nav_terminal)) {
            TerminalFontPreference(settings.terminalFontSp, enabled = !state.busy) { selectedSize ->
                workbench.saveSettings(settings.copy(terminalFontSp = selectedSize))
            }
            SettingsDivider()
            SettingsRow(stringResource(R.string.extra_path), settings.additionalPath.ifEmpty { notConfigured }, glyph = Glyph.File, enabled = !state.busy, onClick = { editing = "path" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.terminal_shell), stringResource(if (settings.terminalShell == TerminalShell.Bash) R.string.shell_bash else R.string.shell_zsh), glyph = Glyph.Terminal, enabled = !state.busy, onClick = { editing = "shell" })
        } }
        item { SettingsGroup(stringResource(R.string.network_settings)) {
            SettingsRow(stringResource(R.string.apt_source), stringResource(if (settings.aptSource == AptSource.Ubuntu) R.string.apt_official else R.string.apt_tsinghua), glyph = Glyph.Projects, enabled = !state.busy, onClick = { editing = "source" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.dns_short), settings.dns.split(',').joinToString(", ") { it.trim() }, glyph = Glyph.Globe, enabled = !state.busy, onClick = { editing = "dns" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.proxy_short), state.proxyDisplay.ifEmpty { notConfigured }, glyph = Glyph.Swap, enabled = !state.busy, onClick = onProxy)
        } }
        item { SettingsGroup(stringResource(R.string.storage_title)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(Glyph.Storage, Modifier.size(20.dp)) }
                    }
                    Text(stringResource(R.string.storage_used), Modifier.weight(1f), style = Type.body)
                    Text(state.usedBytes?.let(StorageUsage::format) ?: unavailable, style = Type.section, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val cacheScope = stringResource(R.string.cache_scope)
                    WorkbenchIconButton(onClick = workbench::clearCache, enabled = !state.busy,
                        modifier = Modifier.size(48.dp).semantics { stateDescription = cacheScope }) {
                        WorkbenchIcon(Glyph.Trash, Modifier.size(20.dp),
                            description = stringResource(R.string.cache_compact_action))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.cache_compact_size, state.downloadCacheBytes?.let(StorageUsage::format) ?: unavailable),
                        fontSize = 11.sp, lineHeight = 16.sp, color = TertiaryText)
                }
            }
        } }
        item { SettingsGroup(stringResource(R.string.application_settings)) {
            SettingsRow(stringResource(R.string.setup_reopen), stringResource(R.string.setup_reopen_hint), glyph = Glyph.Settings, onClick = onSetup)
            SettingsDivider()
            SettingsRow(stringResource(R.string.update_title), stringResource(R.string.update_summary), glyph = Glyph.Environment, onClick = onUpdates)
            SettingsDivider()
            SettingsRow(stringResource(R.string.app_permissions_title), stringResource(R.string.app_permissions_hint), glyph = Glyph.Shield,
                onClick = { appPermissionsOpen = true })
            SettingsDivider()
            SettingsRow(stringResource(R.string.running_notifications), stringResource(notificationStatus.label()), glyph = Glyph.Bell, onClick = { editing = "notifications" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.settings_about_entry), stringResource(R.string.settings_version_footer, installedVersion),
                glyph = Glyph.Info, onClick = { editing = "about" })
        } }
    }
    }
    if (confirmZsh) WorkbenchAlertDialog(onDismissRequest = { confirmZsh = false }, title = { Text(stringResource(R.string.install_zsh)) },
        text = { Text(stringResource(R.string.package_confirmation, ToolBundles.packages(setOf("zsh"), state.bundleTools).joinToString(", "))) },
        confirmButton = { WorkbenchDialogButton(onClick = { confirmZsh = false; editing = null; workbench.installToolBundles(setOf("zsh")) }) { Text(stringResource(R.string.run)) } },
        dismissButton = { WorkbenchDialogButton(onClick = { confirmZsh = false }) { Text(stringResource(R.string.cancel)) } })
    editing?.let { field ->
        var draft by remember(field, settings) { mutableStateOf(when (field) { "path" -> settings.additionalPath; "dns" -> settings.dns; "proxy" -> settings.proxy; else -> "" }) }
        var source by remember(field, settings) { mutableStateOf(settings.aptSource) }
        var shell by remember(field, settings) { mutableStateOf(settings.terminalShell) }
        var error by remember(field) { mutableStateOf<String?>(null) }
        var pendingSave by remember(field) { mutableStateOf<SettingsSaveAttempt?>(null) }
        val title = stringResource(when (field) { "shell" -> R.string.terminal_shell; "path" -> R.string.extra_path; "dns" -> R.string.dns_short; "proxy" -> R.string.proxy_short; "source" -> R.string.apt_source; "notifications" -> R.string.running_notifications; else -> R.string.about_title })
        val informational = field == "about" || field == "notifications"
        WorkbenchSheet(title, { editing = null }) {
                val dismissSheet = LocalWorkbenchSheetDismiss.current
                LaunchedEffect(field, pendingSave) {
                    val pending = pendingSave ?: return@LaunchedEffect
                    val current = if (pending.accepted && workbench.state.value.busy) workbench.state.first { !it.busy } else workbench.state.value
                    when {
                        !pending.accepted -> error = settingsSaveFailed
                        !current.messageIsError && current.message == settingsSaved -> dismissSheet()
                        current.messageIsError || current.message != pending.messageBefore ->
                            error = current.message.ifBlank { settingsSaveFailed }
                        else -> error = settingsSaveFailed
                    }
                    pendingSave = null
                }
                when (field) {
                    "shell" -> {
                        WindowDropdownPreference(title = stringResource(R.string.terminal_shell),
                            entry = DropdownEntry(TerminalShell.entries.map { option ->
                                val ready = option == TerminalShell.Bash || state.bundleTools.any { it.id == "zsh" && it.status == ToolStatus.Ready }
                                DropdownItem(text = stringResource(if (option == TerminalShell.Bash) R.string.shell_bash else R.string.shell_zsh),
                                    summary = if (!ready) stringResource(R.string.bundle_missing) else null,
                                    enabled = ready, selected = shell == option, onClick = { shell = option })
                            }), enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp))
                        if (state.bundleTools.none { it.id == "zsh" && it.status == ToolStatus.Ready }) {
                            WorkbenchSecondaryButton(onClick = { confirmZsh = true }, enabled = state.installed && !state.busy && !state.checkingTools, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.install_zsh))
                            }
                        }
                    }
                    "source" -> WindowDropdownPreference(
                        title = stringResource(R.string.apt_source),
                        items = AptSource.entries.map { stringResource(if (it == AptSource.Ubuntu) R.string.apt_official else R.string.apt_tsinghua) },
                        selectedIndex = AptSource.entries.indexOf(source),
                        onSelectedIndexChange = { source = AptSource.entries[it] }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp))
                    "about" -> AboutAppContent(installedVersion) { sshLicenseOpen = true }
                    "notifications" -> {
                        Text(stringResource(notificationStatus.label()), color = if (notificationStatus == RunningNotificationStatus.Available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.notification_help))
                        if (notificationStatus == RunningNotificationStatus.PermissionRequired && Build.VERSION.SDK_INT >= 33) WorkbenchSecondaryButton(
                            onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.allow_notifications)) }
                        WorkbenchSecondaryButton(onClick = { if (!notifications.openSettings()) error = notificationSettingsUnavailable }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.notification_system_settings))
                        }
                    }
                    else -> WorkbenchTextField(draft, { draft = it; error = null }, modifier = Modifier.fillMaxWidth(),
                        label = stringResource(when (field) { "path" -> R.string.path_label; "dns" -> R.string.dns_label; else -> R.string.proxy_label }),
                        singleLine = true, isError = error != null)
                }
                if (!informational) Text(stringResource(when (field) {
                    "shell" -> R.string.shell_help; "source" -> R.string.source_help; "path" -> R.string.path_help;
                    "dns" -> R.string.dns_help; else -> R.string.proxy_help
                }), style = MaterialTheme.typography.bodySmall)
                if (field == "proxy") CopyHelp(stringResource(R.string.about_proxy_details_title), stringResource(R.string.about_proxy_details))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = {
                if (informational) {
                    dismissSheet()
                } else {
                    val candidate = when (field) { "shell" -> settings.copy(terminalShell = shell); "source" -> settings.copy(aptSource = source); "path" -> settings.copy(additionalPath = draft); "dns" -> settings.copy(dns = draft); else -> settings.copy(proxy = draft) }
                    pendingSave = null
                    error = null
                    try {
                        workbench.validateSettings(candidate)
                        val messageBefore = workbench.state.value.message
                        workbench.saveSettings(candidate)
                        pendingSave = SettingsSaveAttempt(workbench.state.value.busy, messageBefore)
                    } catch (invalid: IllegalArgumentException) { error = invalid.message }
                }
            }, enabled = informational || (!state.busy && pendingSave == null), modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (informational) R.string.close else R.string.save_settings)) }
            if (!informational) WorkbenchSecondaryButton(shape = RoundedCornerShape(12.dp), onClick = dismissSheet, modifier = Modifier.fillMaxWidth()) {
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


@Composable
internal fun SshLicenseEntry(onClick: () -> Unit) {
    WorkbenchSecondaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.ssh_license_entry))
    }
}

@Composable
internal fun SshLicensePanel(license: String?, failed: Boolean = false) {
    Text(stringResource(R.string.ssh_license_component), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.ssh_license_location), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    when {
        failed -> CapabilityNotice(stringResource(R.string.ssh_license_failed), error = true)
        license == null -> Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
        else -> SelectionContainer { Text(license, style = MaterialTheme.typography.bodySmall) }
    }
}

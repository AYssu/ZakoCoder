package dev.zakocoder.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp) else Modifier)
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

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = cardBorder()) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, glyph: Glyph = Glyph.Settings, enabled: Boolean = true, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    val tone = if (enabled) 1f else .45f
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                WorkbenchIcon(glyph, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = tone))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = tone))
            if (value.isNotEmpty()) Text(value, fontSize = 12.sp, lineHeight = 18.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) WorkbenchIcon(Glyph.Next, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = tone))
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant) }

@Composable
internal fun SettingsPage(workbench: Workbench, state: WorkbenchState, onModels: () -> Unit, onExtensions: () -> Unit = {}) {
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
    if (aiPermissions) AiPermissionsSheet(workbench, state.projects.find { it.id == workbench.ai.state.value.projectId }?.name.orEmpty(), { aiPermissions = false })
    var appPermissionsOpen by rememberSaveable { mutableStateOf(false) }
    var rootRequestBusy by remember { mutableStateOf(false) }
    var rootRequestError by remember { mutableStateOf("") }
    val permissionScope = rememberCoroutineScope()
    val rootDeniedMessage = stringResource(R.string.screen_test_root_denied)
    val rootFailedMessage = stringResource(R.string.screen_test_operation_failed)
    if (appPermissionsOpen) {
        val screenPermissions by workbench.screenTests.state.collectAsStateWithLifecycle()
        AppPermissionsPage(rootGranted = screenPermissions.rootGranted, rootBusy = rootRequestBusy, rootError = rootRequestError,
            onRequestRoot = {
                permissionScope.launch {
                    rootRequestBusy = true; rootRequestError = ""
                    try {
                        workbench.screenTests.setAccess(dev.zakocoder.app.screentest.ScreenTestAccess.Root)
                        if (!workbench.screenTests.requestRootAccess()) rootRequestError = rootDeniedMessage
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { rootRequestError = rootFailedMessage }
                    finally { rootRequestBusy = false }
                }
            }, onToolPermissions = {
                workbench.ai.select(state.selectedId)
                if (workbench.ai.state.value.projectId == state.selectedId) { appPermissionsOpen = false; aiPermissions = true }
            }, onBack = { appPermissionsOpen = false },
            toolsEnabled = state.selectedId != null && !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving,
            backgroundServiceRunning = state.backgroundServiceRunning, backgroundServiceError = state.backgroundServiceError)
    }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmZsh by remember { mutableStateOf(false) }
    val notConfigured = stringResource(R.string.not_configured)
    val settings = state.settings
    val unavailable = stringResource(R.string.storage_unavailable)
    val notificationSettingsUnavailable = stringResource(R.string.notification_settings_unavailable)
    Column(Modifier.fillMaxSize()) {
        PageHeading(stringResource(R.string.nav_settings))
        Text(stringResource(R.string.settings_device_hint), Modifier.padding(bottom = 16.dp), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(top = 0.dp, bottom = 24.dp)) {
        item { SettingsGroup(stringResource(R.string.nav_ai)) {
            SettingsRow(stringResource(R.string.ai_settings), models.catalog.active?.name ?: notConfigured, glyph = Glyph.Ai, onClick = onModels)
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_usage_dashboard), stringResource(R.string.ai_usage_dashboard_hint), glyph = Glyph.History,
                onClick = { usageOpen = true })
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_extensions), stringResource(R.string.ai_extensions_types), glyph = Glyph.Extensions, onClick = onExtensions)
            SettingsDivider()
            SettingsRow(stringResource(R.string.ai_permissions), state.project?.name ?: stringResource(R.string.select_project_first),
                glyph = Glyph.Shield, enabled = state.selectedId != null && !ai.active && !ai.restoring && !ai.permissionsLoading && !ai.permissionsSaving,
                onClick = { workbench.ai.select(state.selectedId); if (workbench.ai.state.value.projectId == state.selectedId) aiPermissions = true })
        } }
        item { SettingsGroup(stringResource(R.string.screen_test_development_group)) {
            SettingsRow(stringResource(R.string.capability_packages_title), stringResource(R.string.capability_packages_settings_hint),
                glyph = Glyph.Extensions, onClick = { capabilityPackagesOpen = true })
        } }
        item { SettingsGroup(stringResource(R.string.nav_terminal)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(Glyph.Keyboard, Modifier.size(20.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.terminal_font), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.terminal_output_font), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { workbench.saveSettings(settings.copy(terminalFontSp = settings.terminalFontSp - 1)) }, modifier = Modifier.size(48.dp), enabled = !state.busy && settings.terminalFontSp > 10) {
                            WorkbenchIcon(Glyph.Minus, modifier = Modifier.size(16.dp), description = stringResource(R.string.decrease_font))
                        }
                        Text(stringResource(R.string.font_size_compact, settings.terminalFontSp), Modifier.widthIn(min = 32.dp), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = { workbench.saveSettings(settings.copy(terminalFontSp = settings.terminalFontSp + 1)) }, modifier = Modifier.size(48.dp), enabled = !state.busy && settings.terminalFontSp < 28) {
                            WorkbenchIcon(Glyph.Add, modifier = Modifier.size(16.dp), description = stringResource(R.string.increase_font))
                        }
                    }
                }
            }
            SettingsDivider()
            SettingsRow(stringResource(R.string.extra_path), settings.additionalPath.ifEmpty { notConfigured }, glyph = Glyph.File, enabled = !state.busy, onClick = { editing = "path" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.terminal_shell), stringResource(if (settings.terminalShell == TerminalShell.Bash) R.string.shell_bash else R.string.shell_zsh), glyph = Glyph.Terminal, enabled = !state.busy, onClick = { editing = "shell" })
        } }
        item { SettingsGroup(stringResource(R.string.network_settings)) {
            SettingsRow(stringResource(R.string.apt_source), stringResource(if (settings.aptSource == AptSource.Ubuntu) R.string.apt_official else R.string.apt_tsinghua), glyph = Glyph.Projects, enabled = !state.busy, onClick = { editing = "source" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.dns_short), settings.dns.split(',').joinToString(", ") { it.trim() }, mono = true, glyph = Glyph.Globe, enabled = !state.busy, onClick = { editing = "dns" })
            SettingsDivider()
            SettingsRow(stringResource(R.string.proxy_short), state.proxyDisplay.ifEmpty { notConfigured }, glyph = Glyph.Swap, enabled = !state.busy, onClick = { editing = "proxy" })
        } }
        item { SettingsGroup(stringResource(R.string.storage_title)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(Glyph.Storage, Modifier.size(20.dp)) }
                    }
                    Text(stringResource(R.string.storage_used), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                    Text(state.usedBytes?.let(StorageUsage::format) ?: unavailable, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val cacheScope = stringResource(R.string.cache_scope)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
                        TextButton(onClick = workbench::clearCache, enabled = !state.busy, shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            modifier = Modifier.semantics { stateDescription = cacheScope }.heightIn(min = 48.dp)) {
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
            SettingsRow(stringResource(R.string.app_permissions_title), stringResource(R.string.app_permissions_hint), glyph = Glyph.Shield,
                onClick = { appPermissionsOpen = true })
            SettingsDivider()
            SettingsRow(stringResource(R.string.running_notifications), stringResource(notificationStatus.label()), glyph = Glyph.Bell, onClick = { editing = "notifications" })
        } }
        item {
            TextButton(onClick = { editing = "about" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = BrandFill, shape = RoundedCornerShape(12.dp)) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { WorkbenchBrandMark(Modifier.size(28.dp)) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.app_version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(stringResource(R.string.about_description))
                        Text(stringResource(R.string.validation_artifacts_notice), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.licenses_description))
                        SshLicenseEntry { sshLicenseOpen = true }
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


@Composable
internal fun SshLicenseEntry(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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

package dev.zakocoder.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.kernel.KernelPluginActivationResult
import dev.zakocoder.app.kernel.KernelPluginFailure
import dev.zakocoder.app.kernel.KernelPluginInstallResult
import dev.zakocoder.app.kernel.KernelPluginStatus
import dev.zakocoder.app.settings.KernelFailure
import dev.zakocoder.app.settings.NetworkProxy
import dev.zakocoder.app.settings.ProxyCatalogState
import dev.zakocoder.app.settings.ProxyKernelState
import dev.zakocoder.app.settings.ProxyNode
import dev.zakocoder.app.settings.ProxyProbe
import dev.zakocoder.app.settings.ProxyProtocol
import dev.zakocoder.app.settings.ProxySubscription
import dev.zakocoder.app.settings.ProxySubscriptionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ProxyMode { Direct, Manual, Airport }

/**
 * Independent proxy page: current status, three exclusive connection modes, manual HTTP/SOCKS5, and
 * airport subscriptions (identify -> result -> node list -> apply). All network work is delegated to
 * [dev.zakocoder.app.settings.ProxyCatalog] and only runs when the user taps identify/refresh.
 */
@Composable
internal fun ProxySettingsPage(workbench: Workbench, state: WorkbenchState, onBack: () -> Unit) {
    val catalog by workbench.proxyCatalog.state.collectAsStateWithLifecycle()
    val kernel by workbench.proxyKernel.state.collectAsStateWithLifecycle()
    val pluginStatus by workbench.kernelPlugin.status.collectAsStateWithLifecycle()
    val bindingFailure by workbench.kernelPlugin.bindFailure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appliedNode = remember(state.settings.proxy, catalog.subscription) {
        val endpoint = runCatching { NetworkProxy.parse(state.settings.proxy)?.endpoint }.getOrNull()
        if (endpoint.isNullOrEmpty()) {
            null
        } else {
            catalog.subscription?.nodes?.firstOrNull { node ->
                node.supported && runCatching { NetworkProxy.parse(node.proxyUrl)?.endpoint }.getOrNull() == endpoint
            }
        }
    }
    var modeName by rememberSaveable { mutableStateOf(if (state.settings.proxy.isEmpty()) ProxyMode.Direct.name else ProxyMode.Manual.name) }
    var modeTouched by rememberSaveable { mutableStateOf(false) }
    val mode = ProxyMode.entries.firstOrNull { it.name == modeName } ?: ProxyMode.Manual
    // Drafts may contain a plaintext password or a tokenized subscription URL: keep them out of
    // rememberSaveable so they cannot be restored from saved instance state (DESIGN.md:57).
    var manualDraft by remember { mutableStateOf(state.settings.proxy) }
    var formError by remember { mutableStateOf<String?>(null) }
    var linkDraft by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var unsupportedHint by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var applyFeedback by remember { mutableStateOf<Int?>(null) }
    var lastActionWasRefresh by remember { mutableStateOf(false) }
    // A kernel node starts asynchronously: remember the node we asked to enable and wait for the
    // kernel state machine to report Running/Failed before showing a final page-level result.
    var pendingKernelApply by remember { mutableStateOf<ProxyNode?>(null) }
    var lastKernelNode by remember { mutableStateOf<ProxyNode?>(null) }
    var pluginBusy by remember { mutableStateOf(false) }
    var pluginLocalInstall by remember { mutableStateOf(false) }
    var pluginDownloaded by remember { mutableStateOf(0L) }
    var pluginTotal by remember { mutableStateOf<Long?>(null) }
    var pluginFailure by remember { mutableStateOf<KernelPluginFailure?>(null) }
    var pluginInstallerOpened by remember { mutableStateOf(false) }
    var pluginCancelled by remember { mutableStateOf(false) }
    var pluginUninstallRequested by remember { mutableStateOf(false) }
    var pluginInstallJob by remember { mutableStateOf<Job?>(null) }
    var pluginHint by remember { mutableStateOf(false) }
    var activationFeedback by remember { mutableStateOf<Int?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    val pluginReady = pluginStatus is KernelPluginStatus.Installed

    LaunchedEffect(Unit) { workbench.proxyCatalog.loadCached() }
    LaunchedEffect(kernel, pendingKernelApply) {
        val pending = pendingKernelApply ?: return@LaunchedEffect
        when (val current = kernel) {
            is ProxyKernelState.Running -> if (current.nodeId == pending.id) {
                applyFeedback = R.string.proxy_feedback_kernel_running
                pendingKernelApply = null
            }
            is ProxyKernelState.Failed -> {
                applyFeedback = current.reason.messageRes()
                pendingKernelApply = null
            }
            is ProxyKernelState.Starting -> if (current.nodeId == pending.id) {
                applyFeedback = R.string.proxy_feedback_kernel_starting
            }
            ProxyKernelState.Stopped -> {
                // No start transition arrived: the request was rejected (busy/workbench or binary
                // missing) or the core kept the old state. Report a recoverable failure.
                delay(1500)
                if (pendingKernelApply?.id == pending.id) {
                    applyFeedback = R.string.proxy_feedback_failed
                    pendingKernelApply = null
                }
            }
        }
    }
    LaunchedEffect(catalog.subscription, modeTouched, appliedNode) {
        if (!modeTouched && appliedNode != null) modeName = ProxyMode.Airport.name
    }
    LaunchedEffect(mode) {
        applyFeedback = null
        if (mode == ProxyMode.Manual && manualDraft.isEmpty()) manualDraft = state.settings.proxy
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // The plugin is installed through the system installer, outside this activity. Refresh on
    // resume so package changes made while the user was away show up without polling.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                workbench.kernelPlugin.refresh()
                pluginInstallerOpened = false
                pluginUninstallRequested = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { workbench.kernelPlugin.refresh() }

    fun handlePluginResult(result: KernelPluginInstallResult) {
        when (result) {
            KernelPluginInstallResult.InstallerOpened -> {
                pluginInstallerOpened = true
                pluginFailure = null
            }
            is KernelPluginInstallResult.Failed -> {
                pluginFailure = result.reason
                pluginInstallerOpened = false
            }
            KernelPluginInstallResult.Cancelled -> {
                pluginCancelled = true
                pluginFailure = null
            }
        }
    }

    fun launchPluginInstall(local: Boolean, block: suspend () -> KernelPluginInstallResult) {
        pluginInstallJob?.cancel()
        pluginInstallJob = scope.launch {
            pluginBusy = true
            pluginLocalInstall = local
            pluginDownloaded = 0L
            pluginTotal = null
            pluginFailure = null
            pluginInstallerOpened = false
            pluginCancelled = false
            try {
                handlePluginResult(block())
            } finally {
                pluginBusy = false
                pluginInstallJob = null
            }
        }
    }

    fun downloadPlugin() = launchPluginInstall(local = false) {
        workbench.kernelPlugin.downloadAndInstall { done, total ->
            pluginDownloaded = done
            pluginTotal = total
        }
    }

    fun installLocalPlugin(uri: Uri) = launchPluginInstall(local = true) {
        workbench.kernelPlugin.installFromUri(uri)
    }

    fun cancelPluginInstall() {
        pluginInstallJob?.cancel()
        pluginInstallJob = null
        pluginBusy = false
        pluginCancelled = true
    }

    fun uninstallPlugin() {
        // Never leave a kernel process bound to a plugin that is being uninstalled.
        workbench.stopProxyKernel()
        workbench.kernelPlugin.uninstall()
        pluginUninstallRequested = true
        pluginInstallerOpened = false
        pluginFailure = null
        pluginCancelled = false
    }

    val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installLocalPlugin(uri)
    }

    fun saveProxy(value: String) {
        formError = null
        applyFeedback = null
        val candidate = state.settings.copy(proxy = value)
        try {
            // Validate before stopping anything: a typo must not kill a working kernel/proxy.
            workbench.validateSettings(candidate)
            // A manual/direct endpoint replaces whatever the kernel owned; never leave the child
            // process and its loopback port alive behind the new setting.
            if (kernel != ProxyKernelState.Stopped) workbench.stopProxyKernel(clearSetting = false)
            workbench.saveSettings(candidate)
            // Never keep a typed password/token in the draft after a successful save call.
            manualDraft = ""
            applyFeedback = R.string.proxy_feedback_saved
        } catch (invalid: IllegalArgumentException) {
            formError = invalid.message
        }
    }

    fun identify() {
        lastActionWasRefresh = false
        applyFeedback = null
        workbench.proxyCatalog.updateLink(linkDraft)
        scope.launch {
            workbench.proxyCatalog.probe()
            val probe = workbench.proxyCatalog.state.value.probe
            if (probe is ProxyProbe.Airport || probe is ProxyProbe.SingleNode) {
                workbench.proxyCatalog.import()
                if (workbench.proxyCatalog.state.value.error == null) linkDraft = ""
            }
        }
    }

    fun refreshCache() {
        lastActionWasRefresh = true
        applyFeedback = null
        // Refreshing replaces the subscription the kernel was started for; stop it first.
        if (kernel != ProxyKernelState.Stopped) workbench.stopProxyKernel()
        scope.launch { workbench.proxyCatalog.refresh() }
    }

    fun applyNode(node: ProxyNode) {
        if (node.viaKernel && !pluginReady) {
            applyFeedback = R.string.proxy_bottom_needs_plugin
            return
        }
        if (node.viaKernel) {
            lastKernelNode = node
            pendingKernelApply = node
            applyFeedback = R.string.proxy_feedback_kernel_starting
            // Kernel start is asynchronous; the LaunchedEffect above reports the final result.
            workbench.applyProxyNode(node)
        } else {
            val ok = workbench.applyProxyNode(node)
            applyFeedback = when {
                ok -> R.string.proxy_feedback_saved
                !node.supported -> R.string.proxy_feedback_unsupported
                else -> R.string.proxy_feedback_failed
            }
        }
    }

    fun retryKernel() {
        lastKernelNode?.let { applyNode(it) }
    }

    val activeNodes: List<ProxyNode> = (catalog.probe as? ProxyProbe.Airport)?.subscription?.nodes
        ?: (catalog.probe as? ProxyProbe.UnsupportedOnly)?.nodes
        ?: catalog.subscription?.nodes.orEmpty()
    val singleNode = (catalog.probe as? ProxyProbe.SingleNode)?.node
    val selectedNode = activeNodes.firstOrNull { it.id == catalog.selectedId && it.supported }
        ?: singleNode?.takeIf { it.supported }
    val selectedNeedsPlugin = selectedNode?.viaKernel == true && !pluginReady
    val kernelRunning = kernel as? ProxyKernelState.Running
    val settingsEndpoint = runCatching { NetworkProxy.parse(state.settings.proxy)?.endpoint }.getOrNull()
    val kernelApplied = kernelRunning != null && settingsEndpoint == "socks5://127.0.0.1:${kernelRunning.port}"
    val selectedSourceName = if (kernelApplied) kernelRunning?.nodeName else appliedNode?.name
    val maskedSource = catalog.subscription?.maskedSource.orEmpty()
    val currentModeRes = when {
        state.proxyDisplay.isEmpty() -> R.string.proxy_status_direct
        kernelApplied -> R.string.proxy_status_airport_kernel
        appliedNode != null -> R.string.proxy_status_airport
        else -> R.string.proxy_status_manual
    }
    // Reopening the page while the local kernel is already serving the active proxy: land on the
    // airport tab instead of the manual form, unless the user already switched tabs in this session.
    LaunchedEffect(kernelApplied, modeTouched) {
        if (!modeTouched && kernelApplied) modeName = ProxyMode.Airport.name
    }

    AiFullScreen(stringResource(R.string.proxy_page_title), onBack) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.proxy_section_status), style = MaterialTheme.typography.titleSmall)
                    ProxyStatusCard(
                        modeRes = currentModeRes,
                        endpoint = state.proxyDisplay,
                        hasProxy = state.proxyDisplay.isNotEmpty(),
                        hasCredentials = state.proxyDisplay.contains("***"),
                        source = selectedSourceName,
                        maskedSource = maskedSource.ifEmpty { null },
                        copied = copied,
                        onCopy = {
                            if (state.proxyDisplay.isNotEmpty()) {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                manager?.setPrimaryClip(ClipData.newPlainText("proxy", state.proxyDisplay))
                                copied = true
                            }
                        },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.proxy_section_mode), style = MaterialTheme.typography.titleSmall)
                    ProxyModeSelector(mode) { next ->
                        modeTouched = true
                        modeName = next.name
                        unsupportedHint = false
                        pluginHint = false
                        formError = null
                        applyFeedback = null
                    }
                }
                when (mode) {
                    ProxyMode.Direct -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.proxy_mode_direct_hint), style = MaterialTheme.typography.bodyMedium, color = TertiaryText)
                        formError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        applyFeedback?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                                color = feedbackColor(it))
                        }
                        WorkbenchPrimaryButton(onClick = { saveProxy("") }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_direct_save))
                        }
                    }
                    ProxyMode.Manual -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.proxy_section_manual), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = manualDraft,
                            onValueChange = { manualDraft = it; formError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.proxy_manual_label)) },
                            placeholder = { Text(stringResource(R.string.proxy_manual_placeholder)) },
                            singleLine = true,
                            isError = formError != null,
                            shape = RoundedCornerShape(10.dp),
                        )
                        formError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        applyFeedback?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                                color = feedbackColor(it))
                        }
                        Text(stringResource(R.string.proxy_manual_help), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                        Text(stringResource(R.string.proxy_mode_manual_hint), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                        WorkbenchPrimaryButton(onClick = { saveProxy(manualDraft) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (state.busy) R.string.proxy_manual_saving else R.string.proxy_manual_save))
                        }
                    }
                    ProxyMode.Airport -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.proxy_section_airport), style = MaterialTheme.typography.titleSmall)
                        AirportSection(
                            catalog = catalog,
                            linkDraft = linkDraft,
                            onLinkChange = { linkDraft = it; workbench.proxyCatalog.updateLink(it) },
                            onPaste = {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val text = manager?.primaryClip?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                if (text.isNotEmpty()) {
                                    linkDraft = text
                                    workbench.proxyCatalog.updateLink(text)
                                }
                            },
                            onIdentify = ::identify,
                            onRefresh = ::refreshCache,
                            onRetry = { if (lastActionWasRefresh) refreshCache() else identify() },
                            retryLabelRes = if (lastActionWasRefresh) R.string.proxy_airport_retry_refresh else R.string.proxy_airport_retry,
                            onSelect = { workbench.proxyCatalog.select(it) },
                            onUnsupported = { unsupportedHint = true },
                            onRemove = { removing = true },
                            onPluginNeeded = { pluginHint = true },
                            savingBusy = state.busy,
                            unsupportedHint = unsupportedHint,
                            pluginReady = pluginReady,
                            pluginHint = pluginHint,
                        )
                        val progress = pluginTotal?.takeIf { it > 0 }?.let { total ->
                            ((pluginDownloaded * 100) / total).toInt().coerceIn(0, 100)
                        }
                        KernelPluginCard(
                            status = pluginStatus,
                            busy = pluginBusy,
                            localInstall = pluginLocalInstall,
                            progressPercent = progress,
                            failure = pluginFailure,
                            installerOpened = pluginInstallerOpened,
                            cancelled = pluginCancelled,
                            uninstallRequested = pluginUninstallRequested,
                            onDownload = ::downloadPlugin,
                            onPickLocal = { pluginPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                            onCancel = ::cancelPluginInstall,
                            onUninstall = ::uninstallPlugin,
                            bindingFailure = bindingFailure,
                            activationFeedback = activationFeedback,
                            diagnosticsBusy = diagnosticsBusy,
                            onActivate = {
                                val result = context.activityOrNull()?.let(workbench.kernelPlugin::openActivation)
                                activationFeedback = when (result) {
                                    KernelPluginActivationResult.Opened -> R.string.proxy_plugin_activation_opened
                                    KernelPluginActivationResult.SettingsOpened -> R.string.proxy_plugin_activation_legacy
                                    KernelPluginActivationResult.NotInstalled -> R.string.proxy_plugin_error_not_installed
                                    KernelPluginActivationResult.Incompatible -> R.string.proxy_plugin_error_wrong_signer
                                    else -> R.string.proxy_plugin_activation_unavailable
                                }
                            },
                            onDiagnostics = {
                                diagnosticsBusy = true
                                scope.launch {
                                    try { diagnostic = withContext(Dispatchers.IO) { workbench.kernelPlugin.diagnostics() } }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    finally { diagnosticsBusy = false }
                                }
                            },
                        )
                        KernelStatusCard(
                            kernel = kernel,
                            onStop = { workbench.stopProxyKernel(); applyFeedback = null },
                            onRetry = if (lastKernelNode != null) ::retryKernel else null,
                        )
                        Spacer(Modifier.height(72.dp))
                    }
                }
            }
            if (mode == ProxyMode.Airport) {
                AirportBottomBar(
                    selectedNode = selectedNode,
                    busy = state.busy || catalog.busy,
                    feedback = applyFeedback,
                    needsPlugin = selectedNeedsPlugin,
                    onApply = { selectedNode?.let { applyNode(it) } },
                    onCancel = onBack,
                )
            }
        }
    }

    diagnostic?.let { KernelDiagnosticsSheet(it) { diagnostic = null } }

    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(stringResource(R.string.proxy_remove_title)) },
        text = { Text(stringResource(R.string.proxy_remove_message)) },
        confirmButton = {
            TextButton(onClick = {
                removing = false
                unsupportedHint = false
                pluginHint = false
                linkDraft = ""
                applyFeedback = null
                pendingKernelApply = null
                // The subscription the kernel was started for is going away: stop the process too.
                if (kernel != ProxyKernelState.Stopped) workbench.stopProxyKernel()
                scope.launch { workbench.proxyCatalog.clear() }
            }) { Text(stringResource(R.string.proxy_remove_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { removing = false }) { Text(stringResource(R.string.proxy_action_cancel)) }
        },
    )
}

@Composable
private fun ProxyStatusCard(
    modeRes: Int,
    endpoint: String,
    hasProxy: Boolean,
    hasCredentials: Boolean,
    source: String?,
    maskedSource: String?,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = hasProxy, role = Role.Button, onClick = onCopy),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = cardBorder(),
        shadowElevation = cardElevation(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(if (endpoint.isEmpty()) TertiaryText else MaterialTheme.colorScheme.primary)
                Text(stringResource(modeRes), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (hasProxy) {
                    Text(stringResource(R.string.proxy_status_effective), style = MaterialTheme.typography.bodySmall, color = ReadyGreen)
                }
            }
            Text(
                endpoint.ifEmpty { stringResource(R.string.proxy_status_not_configured) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasCredentials) Text(stringResource(R.string.proxy_status_credential_hint),
                style = MaterialTheme.typography.bodySmall, color = TertiaryText)
            source?.let {
                Text(stringResource(R.string.proxy_status_source, it), style = MaterialTheme.typography.bodySmall,
                    color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (source == null) maskedSource?.let {
                Text(stringResource(R.string.proxy_status_masked_source, it), style = MaterialTheme.typography.bodySmall,
                    color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasProxy) {
                Text(stringResource(if (copied) R.string.proxy_status_copied else R.string.proxy_status_copy_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) ReadyGreen else TertiaryText)
            }
        }
    }
}

@Composable
private fun ProxyModeSelector(mode: ProxyMode, onSelect: (ProxyMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProxyMode.entries.forEach { option ->
            val selected = option == mode
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(option.labelRes()),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AirportSection(
    catalog: ProxyCatalogState,
    linkDraft: String,
    onLinkChange: (String) -> Unit,
    onPaste: () -> Unit,
    onIdentify: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    retryLabelRes: Int,
    onSelect: (String) -> Unit,
    onUnsupported: () -> Unit,
    onRemove: () -> Unit,
    onPluginNeeded: () -> Unit,
    savingBusy: Boolean,
    unsupportedHint: Boolean,
    pluginReady: Boolean,
    pluginHint: Boolean,
) {
    val busy = catalog.busy
    var linkFocused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = linkDraft,
                onValueChange = onLinkChange,
                modifier = Modifier.weight(1f).onFocusChanged { linkFocused = it.isFocused },
                label = { Text(stringResource(R.string.proxy_airport_link_label)) },
                placeholder = if (linkFocused) null else ({ Text(stringResource(R.string.proxy_airport_link_placeholder)) }),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
            OutlinedButton(
                onClick = onPaste,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.proxy_airport_paste)) }
        }
        Text(stringResource(R.string.proxy_airport_link_help), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        OutlinedButton(
            onClick = onIdentify,
            enabled = linkDraft.isNotBlank() && !busy && !savingBusy,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.proxy_airport_probing))
            } else {
                Text(stringResource(R.string.proxy_airport_probe))
            }
        }

        catalog.subscription?.let { subscription ->
            val updatedAt = catalog.updatedAt ?: subscription.fetchedAt
            val timeText = remember(updatedAt) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.proxy_airport_cached, timeText, subscription.nodes.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = TertiaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_airport_refresh))
                }
            }
        }

        val probe = catalog.probe
        val airport = probe as? ProxyProbe.Airport
        val single = probe as? ProxyProbe.SingleNode
        val displayNodes = airport?.subscription?.nodes ?: catalog.subscription?.nodes.orEmpty()

        when {
            busy && probe == null && catalog.subscription == null -> ProxyResultSkeleton()
            else -> {
                if (catalog.error == null && probe == null && catalog.subscription == null) {
                    ProxyEmptyState()
                } else {
                    catalog.error?.let { ProxyErrorBlock(it, retryLabelRes, enabled = !busy && !savingBusy, onRetry = onRetry) }
                    if (catalog.error == null) {
                        when {
                            airport != null -> ProxyAirportResult(airport.subscription, displayNodes, catalog, !busy && !savingBusy,
                                onSelect, onUnsupported, unsupportedHint, pluginReady, pluginHint, onPluginNeeded)
                            single != null -> ProxySingleResult(single.node, pluginReady)
                            probe is ProxyProbe.UnsupportedOnly -> ProxyUnsupportedResult(probe.nodes, !busy && !savingBusy,
                                onUnsupported, unsupportedHint)
                            probe is ProxyProbe.NotSubscription -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProxyBadge(stringResource(R.string.proxy_badge_unknown), MaterialTheme.colorScheme.error)
                                Text(stringResource(R.string.proxy_result_not_subscription), style = MaterialTheme.typography.bodyMedium)
                            }
                            probe == null && catalog.subscription != null -> ProxyAirportResult(catalog.subscription, displayNodes, catalog,
                                !busy && !savingBusy, onSelect, onUnsupported, unsupportedHint, pluginReady, pluginHint, onPluginNeeded)
                        }
                    }
                }
            }
        }

        if (pluginHint) {
            Text(stringResource(R.string.proxy_node_needs_plugin_hint), style = MaterialTheme.typography.bodySmall, color = WaitingAmber)
        }

        if (catalog.subscription != null) {
            TextButton(onClick = onRemove, enabled = !busy && !savingBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.proxy_airport_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ProxyAirportResult(
    subscription: ProxySubscription,
    nodes: List<ProxyNode>,
    catalog: ProxyCatalogState,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onUnsupported: () -> Unit,
    unsupportedHint: Boolean,
    pluginReady: Boolean,
    pluginHint: Boolean,
    onPluginNeeded: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProxyBadge(stringResource(R.string.proxy_badge_airport), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.proxy_counts, nodes.size, subscription.supportedCount),
                style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        }
        Text(stringResource(R.string.proxy_counts_hint), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        if (nodes.isNotEmpty()) {
            Text(stringResource(R.string.proxy_section_nodes), style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                    ProxyNodeRow(
                        node = node,
                        selected = node.id == catalog.selectedId,
                        enabled = enabled,
                        onSelect = { onSelect(node.id) },
                        onUnsupported = onUnsupported,
                        pluginReady = pluginReady,
                        onPluginNeeded = onPluginNeeded,
                    )
                    if (index != nodes.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (unsupportedHint) {
            Text(stringResource(R.string.proxy_node_unsupported_hint), style = MaterialTheme.typography.bodySmall, color = WaitingAmber)
        }
        if (pluginHint) {
            Text(stringResource(R.string.proxy_node_needs_plugin_hint), style = MaterialTheme.typography.bodySmall, color = WaitingAmber)
        }
    }
}

@Composable
private fun ProxyUnsupportedResult(nodes: List<ProxyNode>, enabled: Boolean, onUnsupported: () -> Unit, unsupportedHint: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProxyBadge(stringResource(R.string.proxy_badge_unsupported), WaitingAmber)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.proxy_result_unsupported_only, nodes.size),
                style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        }
        if (nodes.isNotEmpty()) {
            Text(stringResource(R.string.proxy_section_nodes), style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                    ProxyNodeRow(node = node, selected = false, enabled = enabled, onSelect = {}, onUnsupported = onUnsupported)
                    if (index != nodes.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (unsupportedHint) {
            Text(stringResource(R.string.proxy_node_unsupported_hint), style = MaterialTheme.typography.bodySmall, color = WaitingAmber)
        }
    }
}

@Composable
private fun ProxySingleResult(node: ProxyNode, pluginReady: Boolean) {
    val needsPlugin = node.supported && node.viaKernel && !pluginReady
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProxyBadge(stringResource(R.string.proxy_badge_single), MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = cardBorder(),
            shadowElevation = cardElevation(),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProtocolBadge(node.protocol)
                    if (node.viaKernel) KernelTag()
                    Text(node.name.ifBlank { node.host }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.proxy_node_host, node.host, node.port) + " · " + protocolName(node.protocol),
                    style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                if (needsPlugin) {
                    Text(stringResource(R.string.proxy_node_needs_plugin), style = MaterialTheme.typography.bodySmall, color = WaitingAmber)
                } else {
                    Text(stringResource(R.string.proxy_result_single_hint), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                }
            }
        }
    }
}

@Composable
internal fun ProxyNodeRow(
    node: ProxyNode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onUnsupported: () -> Unit,
    pluginReady: Boolean = true,
    onPluginNeeded: () -> Unit = {},
) {
    val needsPlugin = node.supported && node.viaKernel && !pluginReady
    val selectable = node.supported && !needsPlugin
    val interaction = when {
        selectable -> Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        needsPlugin -> Modifier.clickable(enabled = enabled, onClick = onPluginNeeded)
        node.subscriptionPlaceholder -> Modifier
        else -> Modifier.clickable(enabled = enabled, onClick = onUnsupported)
    }
    Row(
        interaction.fillMaxWidth().heightIn(min = 56.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProtocolBadge(node.protocol)
            if (node.viaKernel) KernelTag()
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.name.ifBlank { node.host }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (node.subscriptionPlaceholder) stringResource(R.string.proxy_subscription_notice_hint)
                else stringResource(R.string.proxy_node_host, node.host, node.port) + " · " + protocolName(node.protocol),
                style = MaterialTheme.typography.bodySmall,
                color = TertiaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            node.subscriptionPlaceholder -> Text(stringResource(R.string.proxy_subscription_notice),
                style = MaterialTheme.typography.labelSmall, color = TertiaryText)
            selectable -> RadioButton(selected = selected, onClick = null, enabled = enabled)
            needsPlugin -> Text(stringResource(R.string.proxy_node_needs_plugin), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Medium, color = WaitingAmber, maxLines = 2)
            else -> Text(stringResource(R.string.proxy_badge_unsupported), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Medium, color = WaitingAmber)
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: ProxyProtocol) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            Modifier.widthIn(min = 44.dp).heightIn(min = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(protocolShort(protocol), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ProxyBadge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = .12f)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun ProxyResultSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Surface(Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) { }
        }
    }
}

@Composable
private fun ProxyEmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkbenchIcon(Glyph.Globe, Modifier.size(40.dp), tint = TertiaryText)
        Text(stringResource(R.string.proxy_airport_empty_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.proxy_airport_empty_hint), style = MaterialTheme.typography.bodySmall,
            color = TertiaryText, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProxyErrorBlock(error: ProxySubscriptionError, retryLabelRes: Int, enabled: Boolean, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .45f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(error.labelRes()), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(retryLabelRes))
            }
        }
    }
}

@Composable
private fun KernelTag() {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(R.string.proxy_kernel_badge),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** Optional kernel plugin: install (download or local APK), version state, uninstall. */
@Composable
internal fun KernelPluginCard(
    status: KernelPluginStatus,
    busy: Boolean,
    localInstall: Boolean,
    progressPercent: Int?,
    failure: KernelPluginFailure?,
    installerOpened: Boolean,
    cancelled: Boolean,
    uninstallRequested: Boolean,
    onDownload: () -> Unit,
    onPickLocal: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
    bindingFailure: dev.zakocoder.app.kernel.KernelBindingFailure? = null,
    activationFeedback: Int? = null,
    diagnosticsBusy: Boolean = false,
    onActivate: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = cardBorder(),
        shadowElevation = cardElevation(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (status) {
                        KernelPluginStatus.NotInstalled -> TertiaryText
                        is KernelPluginStatus.Installed -> ReadyGreen
                        KernelPluginStatus.Incompatible -> WaitingAmber
                    },
                )
                Text(stringResource(R.string.proxy_plugin_section), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                when (status) {
                    KernelPluginStatus.NotInstalled -> stringResource(R.string.proxy_plugin_not_installed)
                    is KernelPluginStatus.Installed -> stringResource(R.string.proxy_plugin_installed, status.info.versionName)
                    KernelPluginStatus.Incompatible -> stringResource(R.string.proxy_plugin_incompatible)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is KernelPluginStatus.Incompatible) WaitingAmber else TertiaryText,
            )
            KernelRecoveryActions(status, bindingFailure, activationFeedback,
                enabled = !busy, diagnosticsBusy = diagnosticsBusy, onActivate = onActivate, onDiagnostics = onDiagnostics)
            if (status !is KernelPluginStatus.Installed) {
                Text(stringResource(R.string.proxy_plugin_required_hint),
                    style = MaterialTheme.typography.bodySmall, color = TertiaryText)
            }
            if (installerOpened) {
                Text(stringResource(R.string.proxy_plugin_installer_opened),
                    style = MaterialTheme.typography.bodySmall, color = ReadyGreen)
            }
            if (uninstallRequested) {
                Text(stringResource(R.string.proxy_plugin_uninstalling),
                    style = MaterialTheme.typography.bodySmall, color = TertiaryText)
            }
            if (cancelled) {
                Text(stringResource(R.string.proxy_plugin_cancelled),
                    style = MaterialTheme.typography.bodySmall, color = TertiaryText)
            }
            failure?.let {
                Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (busy) {
                if (localInstall) {
                    Text(stringResource(R.string.proxy_plugin_installing), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                } else {
                    if (progressPercent != null) {
                        LinearProgressIndicator(progress = { progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.proxy_plugin_download_progress, progressPercent),
                            style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.proxy_plugin_downloading), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                    }
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_plugin_cancel), color = MaterialTheme.colorScheme.error)
                }
            } else {
                when (status) {
                    KernelPluginStatus.NotInstalled, KernelPluginStatus.Incompatible -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onDownload, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_plugin_install_online))
                        }
                        OutlinedButton(onClick = onPickLocal, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_plugin_install_local))
                        }
                    }
                    is KernelPluginStatus.Installed -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Updating must not require uninstalling first: a failed install keeps the old version.
                        OutlinedButton(onClick = onDownload, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_plugin_update_online))
                        }
                        OutlinedButton(onClick = onPickLocal, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_plugin_update_local))
                        }
                        TextButton(onClick = onUninstall, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.proxy_plugin_uninstall), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** Local Xray kernel status for the current airport subscription (airport mode only). */
@Composable
private fun KernelStatusCard(kernel: ProxyKernelState, onStop: () -> Unit, onRetry: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = cardBorder(),
        shadowElevation = cardElevation(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(
                    when (kernel) {
                        ProxyKernelState.Stopped -> TertiaryText
                        is ProxyKernelState.Starting -> WaitingAmber
                        is ProxyKernelState.Running -> ReadyGreen
                        is ProxyKernelState.Failed -> MaterialTheme.colorScheme.error
                    },
                )
                Text(stringResource(R.string.proxy_kernel_section), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            val failed = kernel as? ProxyKernelState.Failed
            Text(
                when (kernel) {
                    ProxyKernelState.Stopped -> stringResource(R.string.proxy_kernel_stopped)
                    is ProxyKernelState.Starting -> stringResource(R.string.proxy_kernel_starting)
                    is ProxyKernelState.Running -> stringResource(R.string.proxy_kernel_running, kernel.nodeName, kernel.port)
                    is ProxyKernelState.Failed -> stringResource(kernel.reason.messageRes())
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) MaterialTheme.colorScheme.error else TertiaryText,
            )
            if (kernel is ProxyKernelState.Running) {
                Text(stringResource(R.string.proxy_kernel_running_hint),
                    style = MaterialTheme.typography.labelSmall, color = TertiaryText)
                Text(stringResource(R.string.proxy_kernel_plugin_running_detail),
                    style = MaterialTheme.typography.labelSmall, color = TertiaryText)
            }
            if (kernel is ProxyKernelState.Running || failed != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (failed != null) {
                        onRetry?.let {
                            OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.proxy_kernel_retry))
                            }
                        }
                    }
                    TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.proxy_kernel_stop), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun feedbackColor(res: Int): Color = when {
    res == R.string.proxy_feedback_saved || res == R.string.proxy_feedback_kernel_running -> ReadyGreen
    res == R.string.proxy_feedback_kernel_starting -> TertiaryText
    else -> MaterialTheme.colorScheme.error
}

private fun KernelFailure.messageRes(): Int = when (this) {
    KernelFailure.BinaryMissing -> R.string.proxy_kernel_failure_binary_missing
    KernelFailure.UnsupportedNode -> R.string.proxy_kernel_failure_unsupported_node
    KernelFailure.StartFailed -> R.string.proxy_kernel_failure_start_failed
    KernelFailure.Timeout -> R.string.proxy_kernel_failure_timeout
    KernelFailure.PortUnavailable -> R.string.proxy_kernel_failure_port_unavailable
    KernelFailure.PluginNotInstalled -> R.string.proxy_kernel_failure_plugin_missing
    KernelFailure.PluginIncompatible -> R.string.proxy_kernel_failure_plugin_incompatible
    KernelFailure.ServiceUnavailable -> R.string.proxy_plugin_error_service_unavailable
    KernelFailure.DnsResolutionFailed -> R.string.proxy_kernel_dns_resolution_failed
}

private fun KernelPluginFailure.messageRes(): Int = when (this) {
    KernelPluginFailure.Download -> R.string.proxy_plugin_error_download
    KernelPluginFailure.InvalidPackage -> R.string.proxy_plugin_error_invalid_package
    KernelPluginFailure.WrongSigner -> R.string.proxy_plugin_error_wrong_signer
    KernelPluginFailure.WrongPackage -> R.string.proxy_plugin_error_wrong_package
    KernelPluginFailure.VersionTooOld -> R.string.proxy_plugin_error_version_too_old
    KernelPluginFailure.VersionDowngrade -> R.string.proxy_plugin_error_version_downgrade
    KernelPluginFailure.Io -> R.string.proxy_plugin_error_io
    KernelPluginFailure.NotInstalled -> R.string.proxy_plugin_error_not_installed
    KernelPluginFailure.ServiceUnavailable -> R.string.proxy_plugin_error_service_unavailable
}

@Composable
private fun AirportBottomBar(
    selectedNode: ProxyNode?,
    busy: Boolean,
    feedback: Int?,
    needsPlugin: Boolean,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = PanelBackground) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                selectedNode?.let {
                    Text(stringResource(R.string.proxy_selected_summary, it.name), style = MaterialTheme.typography.bodySmall,
                        color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (needsPlugin) {
                    Text(stringResource(R.string.proxy_bottom_needs_plugin), style = MaterialTheme.typography.bodySmall,
                        color = WaitingAmber)
                }
                feedback?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = feedbackColor(it))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.proxy_action_cancel))
                    }
                    WorkbenchPrimaryButton(
                        onClick = onApply,
                        enabled = selectedNode != null && !busy && !needsPlugin,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.proxy_action_apply)) }
                }
            }
        }
    }
}

private fun ProxyMode.labelRes(): Int = when (this) {
    ProxyMode.Direct -> R.string.proxy_mode_direct
    ProxyMode.Manual -> R.string.proxy_mode_manual
    ProxyMode.Airport -> R.string.proxy_mode_airport
}

@Composable
private fun protocolShort(protocol: ProxyProtocol): String = stringResource(
    when (protocol) {
        ProxyProtocol.Http -> R.string.proxy_protocol_short_http
        ProxyProtocol.Socks5 -> R.string.proxy_protocol_short_socks5
        ProxyProtocol.Vmess -> R.string.proxy_protocol_short_vmess
        ProxyProtocol.Ss -> R.string.proxy_protocol_short_ss
        ProxyProtocol.Trojan -> R.string.proxy_protocol_short_trojan
        ProxyProtocol.Vless -> R.string.proxy_protocol_short_vless
        ProxyProtocol.Hysteria -> R.string.proxy_protocol_short_hysteria
        ProxyProtocol.Tuic -> R.string.proxy_protocol_short_tuic
        ProxyProtocol.Other -> R.string.proxy_protocol_short_other
    },
)

@Composable
private fun protocolName(protocol: ProxyProtocol): String = stringResource(
    when (protocol) {
        ProxyProtocol.Http -> R.string.proxy_protocol_name_http
        ProxyProtocol.Socks5 -> R.string.proxy_protocol_name_socks5
        ProxyProtocol.Vmess -> R.string.proxy_protocol_name_vmess
        ProxyProtocol.Ss -> R.string.proxy_protocol_name_ss
        ProxyProtocol.Trojan -> R.string.proxy_protocol_name_trojan
        ProxyProtocol.Vless -> R.string.proxy_protocol_name_vless
        ProxyProtocol.Hysteria -> R.string.proxy_protocol_name_hysteria
        ProxyProtocol.Tuic -> R.string.proxy_protocol_name_tuic
        ProxyProtocol.Other -> R.string.proxy_protocol_name_other
    },
)

private fun ProxySubscriptionError.labelRes(): Int = when (this) {
    ProxySubscriptionError.InvalidLink -> R.string.proxy_error_invalid_link
    ProxySubscriptionError.InsecureScheme -> R.string.proxy_error_insecure_scheme
    ProxySubscriptionError.Unreachable -> R.string.proxy_error_unreachable
    ProxySubscriptionError.HttpStatus -> R.string.proxy_error_http_status
    ProxySubscriptionError.TooLarge -> R.string.proxy_error_too_large
    ProxySubscriptionError.UnsupportedContent -> R.string.proxy_error_unsupported_content
    ProxySubscriptionError.NotFound -> R.string.proxy_error_not_found
    ProxySubscriptionError.Empty -> R.string.proxy_error_empty
}

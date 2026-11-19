package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.device.DeviceMode
import dev.zakocoder.app.device.LaunchableDeviceApp
import dev.zakocoder.app.device.launchableDeviceApps
import dev.zakocoder.app.screentest.ScreenOverlayPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only the controller can create or operate a display. The UI never targets the physical display. */
@Composable
internal fun VirtualScreenPage(workbench: Workbench, onBack: () -> Unit) {
    val state by workbench.virtualScreen.state.collectAsStateWithLifecycle()
    val access by workbench.deviceAccess.state.collectAsStateWithLifecycle()
    val screen by workbench.screenTests.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var choosingApp by remember { mutableStateOf(false) }
    var permissionsOpen by remember { mutableStateOf(false) }
    var confirmSharing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var operationSerial by remember { mutableLongStateOf(0L) }
    var reportOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    val fallback = stringResource(R.string.virtual_screen_operation_failed)
    val ready = access.mode == DeviceMode.Root && access.rootGranted
    val busy = state.busy || working
    suspend fun refreshPreview() {
        val requestedDisplay = workbench.virtualScreen.state.value.displayId
        val bytes = workbench.virtualScreen.capture()
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        check(bitmap != null) { fallback }
        currentCoroutineContext().ensureActive()
        check(workbench.virtualScreen.state.value.active && workbench.virtualScreen.state.value.displayId == requestedDisplay) { fallback }
        preview = bitmap
    }
    fun operation(action: suspend () -> Unit) {
        if (working || workbench.virtualScreen.state.value.busy || operationJob?.isActive == true) return
        val requested = ++operationSerial
        working = true; operationError = null
        operationJob = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (operationSerial == requested) operationError = fallback }
            finally { if (operationSerial == requested) { working = false; operationJob = null } }
        }
    }
    fun stopDisplay() {
        operationSerial++
        operationJob?.cancel()
        operationJob = null
        working = false; choosingApp = false; confirmSharing = null
        preview = null; operationError = null
        workbench.virtualScreen.stop()
    }
    fun sharing(owner: String, lease: String, enabled: Boolean) {
        try { workbench.screenTests.setScreenshotSharing(owner, enabled, lease) }
        catch (_: IllegalStateException) { operationError = fallback }
    }
    LaunchedEffect(state.displayId, state.active) { preview = null }
    val content: @Composable () -> Unit = {
        val back = rememberWorkbenchBackMotion(onBack = onBack)
        Column(Modifier.fillMaxSize().then(back.modifier).background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back.close) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.close)) }
                Text(stringResource(R.string.virtual_screen_title), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.virtual_screen_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!ready) {
                    CapabilityNotice(stringResource(R.string.virtual_screen_root_required))
                    WorkbenchPrimaryButton(onClick = { permissionsOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.device_permissions_open))
                    }
                } else {
                    WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(when {
                                busy -> R.string.virtual_screen_starting
                                state.active -> R.string.virtual_screen_running
                                else -> R.string.virtual_screen_idle
                            }, state.displayId ?: 0, state.width, state.height), style = MaterialTheme.typography.bodyMedium)
                            if (state.active) {
                                state.packageName?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                WorkbenchSecondaryButton(onClick = { choosingApp = true }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.virtual_screen_apps)) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { operation { refreshPreview() } }, enabled = !busy,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.virtual_screen_refresh)) }
                                    TextButton(onClick = ::stopDisplay,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.virtual_screen_stop)) }
                                }
                            } else WorkbenchPrimaryButton(onClick = { operation {
                                workbench.virtualScreen.start()
                            } }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.virtual_screen_start))
                            }
                            if (busy) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (!state.active) TextButton(onClick = ::stopDisplay,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.virtual_screen_stop)) }
                            }
                        }
                    }
                    val image = preview
                    if (image != null && state.active) WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // Bound the viewport before fitting the bitmap: a forced full width
                            // plus aspectRatio can exceed max height and crop a portrait display.
                            val ratio = image.width.toFloat() / image.height
                            val viewportHeight = (maxWidth / ratio).coerceAtMost(480.dp)
                            Image(image.asImageBitmap(), contentDescription = stringResource(R.string.virtual_screen_preview),
                                modifier = Modifier.fillMaxWidth().height(viewportHeight),
                                contentScale = ContentScale.Fit)
                        }
                    } else Text(stringResource(R.string.virtual_screen_preview_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VirtualScreenTaskPanel(
                        model = VirtualScreenTaskUiModel(active = screen.active, busy = screen.busy,
                            summary = screen.summary, actionTitle = screen.overlayProgress.title,
                            actionDetail = screen.overlayProgress.detail,
                            errorMessage = screen.summary.takeIf { screen.overlayProgress.phase == ScreenOverlayPhase.Failed }.orEmpty(),
                            hasReport = screen.reportPath != null && screen.projectId != null,
                            shareScreenshots = screen.shareScreenshots),
                        onShare = { enabled ->
                            val owner = screen.projectId
                            val lease = screen.leaseId
                            if (owner == null || lease == null) operationError = fallback
                            else if (enabled) confirmSharing = owner to lease
                            else sharing(owner, lease, false)
                        },
                        onStop = workbench.screenTests::stop,
                        onReport = {
                            // Capture the report owner before suspending; never substitute the
                            // currently viewed workspace for the task that produced this report.
                            val reportOwner = screen.projectId
                            if (reportOwner == null) operationError = fallback
                            else {
                                reportOpen = true; report = null
                                scope.launch {
                                    try { report = workbench.screenTests.readReport(reportOwner) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { reportOpen = false; operationError = fallback }
                                }
                            }
                        },
                    )
                }
                state.error?.takeIf(String::isNotBlank)?.let { CapabilityNotice(it, error = true) }
                operationError?.let { CapabilityNotice(it, error = true) }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        WorkbenchRetainedPage(covered = permissionsOpen, content = content)
        if (permissionsOpen) DevicePermissionsPage(workbench, onBack = { permissionsOpen = false }, embedded = true)
    }
    if (choosingApp) VirtualScreenAppPicker(onDismiss = { choosingApp = false }) { packageName ->
        choosingApp = false
        preview = null
        operation { workbench.virtualScreen.launch(packageName); refreshPreview() }
    }
    if (reportOpen) WorkbenchSheet(stringResource(R.string.screen_test_report_title), { reportOpen = false }) {
        ScreenTestReport(report)
    }
    confirmSharing?.let { (owner, lease) ->
        ScreenTestScreenshotConsent(onDismiss = { confirmSharing = null }, onConfirm = {
            confirmSharing = null
            // The consent belongs to the exact task shown when the switch was tapped.
            sharing(owner, lease, true)
        })
    }
}

@Composable
private fun VirtualScreenAppPicker(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableDeviceApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        try {
            apps = launchableDeviceApps(context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { failed = true }
    }
    WorkbenchSheet(stringResource(R.string.virtual_screen_apps), onDismiss) {
        when {
            failed -> CapabilityNotice(stringResource(R.string.virtual_screen_apps_failed), error = true)
            apps == null -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.virtual_screen_apps_loading)) }
            apps.orEmpty().isEmpty() -> Text(stringResource(R.string.virtual_screen_apps_empty))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(apps.orEmpty(), key = { it.packageName }) { app ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(app.packageName) }.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

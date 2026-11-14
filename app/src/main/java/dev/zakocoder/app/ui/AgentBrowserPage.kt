package dev.zakocoder.app.ui

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import dev.zakocoder.app.R
import dev.zakocoder.app.browser.BrowserController

/** Keep streaming state subscriptions inside the browser destination. */
@Composable
internal fun AgentBrowserWorkspacePage(workbench: dev.zakocoder.app.Workbench, onBack: () -> Unit) {
    val task by workbench.ai.state.collectAsStateWithLifecycle()
    val followedTask = remember { task.active }
    androidx.compose.runtime.LaunchedEffect(task.active, task.phase) {
        if (followedTask && (!task.active || task.phase == dev.zakocoder.app.ai.AiPhase.AwaitingAnswer)) onBack()
    }
    AgentBrowserPage(workbench.browser, task, approvalHost = { AiToolApprovalHost(workbench, task) }, onBack = onBack)
}

/** Compact native chrome, actual page below; all operations belong to BrowserController. */
@Composable
internal fun AgentBrowserPage(controller: BrowserController, taskState: dev.zakocoder.app.ai.AiState? = null,
    approvalHost: @Composable () -> Unit = {}, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val displayedAddress = if (editing) address else state.url.ifBlank { address }
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val navigate = {
        val target = displayedAddress.trim()
        address = target
        editing = false
        focus.clearFocus()
        controller.requestNavigation(target)
    }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.setForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> controller.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.setForeground(false) }
    }
    AiFullScreen(stringResource(R.string.browser_title), onBack) {
        Column(Modifier.fillMaxSize()) {
            if (taskState != null && (taskState.active || taskState.turn > 0)) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.browser_task_state, stringResource(taskState.phase.label)),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onBack) { Text(stringResource(R.string.browser_return_conversation)) }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkbenchTextField(
                            value = displayedAddress,
                            onValueChange = { address = it.take(8192); editing = true },
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.browser_address), singleLine = true,
                            placeholder = { Text(stringResource(R.string.browser_address_example)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigate() }),
                        )
                        IconButton(onClick = { navigate() }, enabled = !state.busy && displayedAddress.isNotBlank()) {
                            WorkbenchIcon(Glyph.Right, description = stringResource(R.string.browser_go))
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = controller::requestBack, enabled = state.canGoBack && !state.busy) {
                            WorkbenchIcon(Glyph.Back, description = stringResource(R.string.browser_back))
                        }
                        IconButton(onClick = if (state.loading || state.busy) controller::stop else controller::requestReload,
                            enabled = state.url.isNotBlank() || state.busy) {
                            WorkbenchIcon(if (state.loading || state.busy) Glyph.Close else Glyph.Refresh,
                                description = stringResource(if (state.loading || state.busy) R.string.browser_stop else R.string.browser_reload))
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.title.ifBlank { stringResource(R.string.browser_page_empty) }, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(if (state.busy) browserActionText(state.action) else stringResource(
                                if (state.loading) R.string.browser_action_navigate
                                else if (state.url.isBlank()) R.string.browser_start_hint
                                else R.string.browser_ready_hint),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.browser_share_images), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val sharingLabel = stringResource(R.string.browser_share_images)
                        WorkbenchSwitch(checked = state.shareImages, enabled = !state.busy, onCheckedChange = controller::setShareImages,
                            modifier = Modifier.semantics { contentDescription = sharingLabel })
                    }
                    state.error?.let { code ->
                        Text(controller.errorMessage(code) + " · " + code, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            else Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                // Keep the real WebView mounted even before navigation: AI operations wait for
                // this attachment. The native landing state only covers its empty document.
                AndroidView(
                    factory = { context -> WebView(context).also { web ->
                        controller.attach(web)
                        controller.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    } },
                    onRelease = controller::detach,
                    modifier = Modifier.fillMaxSize(),
                )
                val loadFailed = state.error in setOf("BROWSER_LOAD_FAILED", "BROWSER_TLS_ERROR", "BROWSER_RENDERER_GONE")
                if (state.url.isBlank() || loadFailed) Box(
                    Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface).clipToBounds(),
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        WorkbenchIcon(Glyph.Globe, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(if (loadFailed) R.string.browser_load_failed_title else R.string.browser_start_title),
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(if (loadFailed) R.string.browser_load_failed_hint else R.string.browser_start_body),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.busy || state.loading) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(browserActionText(state.action), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (loadFailed && state.url.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = controller::requestReload) { Text(stringResource(R.string.browser_retry)) }
                        }
                    }
                }
            }
        }
        state.pendingOrigin?.let { origin ->
            state.pendingOriginRequestId?.let { requestId ->
                WorkbenchAlertDialog(onDismissRequest = { controller.confirmOrigin(origin, requestId, false) },
                    title = { Text(stringResource(R.string.browser_confirm_title)) },
                    text = { Text(stringResource(R.string.browser_confirm_body, origin)) },
                    confirmButton = { TextButton(onClick = { controller.confirmOrigin(origin, requestId, true) }) { Text(stringResource(R.string.browser_allow)) } },
                    dismissButton = { TextButton(onClick = { controller.confirmOrigin(origin, requestId, false) }) { Text(stringResource(R.string.browser_deny)) } })
            }
        }
        approvalHost()
    }
}

@Composable
private fun browserActionText(action: String): String = stringResource(when (action) {
    "navigate", "back" -> R.string.browser_action_navigate
    "read", "find" -> R.string.browser_action_read
    "click" -> R.string.browser_action_click
    "type" -> R.string.browser_action_type
    "scroll" -> R.string.browser_action_scroll
    "wait" -> R.string.browser_action_wait
    "screenshot" -> R.string.browser_action_screenshot
    else -> R.string.browser_action_busy
})

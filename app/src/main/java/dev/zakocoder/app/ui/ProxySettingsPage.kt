package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState

/** External HTTP/SOCKS5 endpoints only. Saving and credential storage remain in the settings layer. */
@Composable
internal fun ProxySettingsPage(workbench: Workbench, state: WorkbenchState, onBack: () -> Unit) {
    val git by workbench.git.state.collectAsState()
    val tasks by workbench.projectTasks.state.collectAsState()
    ProxySettingsContent(
        endpoint = state.settings.proxy,
        display = state.proxyDisplay,
        busy = state.busy || state.loading || git.busy || tasks.busy,
        onBack = onBack,
        onSave = { value ->
            val settings = state.settings.copy(proxy = value)
            workbench.validateSettings(settings)
            workbench.saveSettings(settings)
        },
    )
}

@Composable
internal fun ProxySettingsContent(
    endpoint: String,
    display: String,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    // URLs may contain credentials: never persist the editing draft in saved instance state.
    var draft by remember(endpoint) { mutableStateOf(endpoint) }
    var invalid by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    fun save(value: String) {
        invalid = false
        submitted = false
        try {
            onSave(value)
            // Clear newly entered credentials after submission; the status exposes a masked URL.
            draft = ""
            submitted = true
        } catch (_: IllegalArgumentException) {
            invalid = true
        }
    }

    AiFullScreen(stringResource(R.string.proxy_page_title), onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.proxy_section_status), style = MaterialTheme.typography.titleSmall)
                WorkbenchCard(Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (endpoint.isEmpty()) TertiaryText else MaterialTheme.colorScheme.primary)
                            Text(stringResource(if (endpoint.isEmpty()) R.string.proxy_status_direct else R.string.proxy_status_manual),
                                style = MaterialTheme.typography.titleSmall)
                        }
                        Text(display.ifEmpty { stringResource(R.string.proxy_status_not_configured) },
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (display.contains("***")) Text(stringResource(R.string.proxy_status_credential_hint),
                            style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.proxy_section_manual), style = MaterialTheme.typography.titleSmall)
                WorkbenchTextField(
                    value = draft,
                    onValueChange = { draft = it; invalid = false; submitted = false },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = stringResource(R.string.proxy_manual_label),
                    placeholder = { Text(stringResource(R.string.proxy_manual_placeholder)) },
                    singleLine = true,
                    isError = invalid,
                    shape = RoundedCornerShape(12.dp),
                )
                if (invalid) Text(stringResource(R.string.proxy_manual_invalid),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.proxy_manual_help), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                Text(stringResource(R.string.proxy_mode_manual_hint), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                if (submitted) Text(stringResource(R.string.proxy_feedback_saved),
                    style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                WorkbenchPrimaryButton(onClick = { save(draft) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (busy) R.string.proxy_manual_saving else R.string.proxy_manual_save))
                }
                if (endpoint.isNotEmpty()) WorkbenchTextButton(onClick = { save("") }, enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_direct_save))
                }
            }
        }
    }
}

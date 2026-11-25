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
import kotlinx.coroutines.flow.first

internal sealed interface ProxySaveOutcome {
    data object Saved : ProxySaveOutcome
    data class Failed(val message: String) : ProxySaveOutcome
}

private data class ProxySaveAttempt(val accepted: Boolean, val messageBefore: String)

/** External HTTP/SOCKS5 endpoints only. Saving and credential storage remain in the settings layer. */
@Composable
internal fun ProxySettingsPage(workbench: Workbench, state: WorkbenchState, onBack: () -> Unit) {
    val git by workbench.git.state.collectAsState()
    val tasks by workbench.projectTasks.state.collectAsState()
    var pendingSave by remember { mutableStateOf<ProxySaveAttempt?>(null) }
    var saveOutcome by remember { mutableStateOf<ProxySaveOutcome?>(null) }
    val saveFailed = stringResource(R.string.settings_save_failed)
    val settingsSaved = stringResource(R.string.settings_saved)
    LaunchedEffect(pendingSave) {
        val pending = pendingSave ?: return@LaunchedEffect
        val current = if (pending.accepted && workbench.state.value.busy) workbench.state.first { !it.busy } else workbench.state.value
        saveOutcome = when {
            !pending.accepted -> ProxySaveOutcome.Failed(saveFailed)
            !current.messageIsError && current.message == settingsSaved -> ProxySaveOutcome.Saved
            current.messageIsError || current.message != pending.messageBefore ->
                ProxySaveOutcome.Failed(current.message.ifBlank { saveFailed })
            else -> ProxySaveOutcome.Failed(saveFailed)
        }
        pendingSave = null
    }
    ProxySettingsContent(
        endpoint = state.settings.proxy,
        display = state.proxyDisplay,
        busy = state.busy || state.loading || git.busy || tasks.busy,
        onBack = onBack,
        onSave = { value ->
            val settings = state.settings.copy(proxy = value)
            pendingSave = null
            saveOutcome = null
            workbench.validateSettings(settings)
            val messageBefore = workbench.state.value.message
            workbench.saveSettings(settings)
            pendingSave = ProxySaveAttempt(workbench.state.value.busy, messageBefore)
        },
        saveOutcome = saveOutcome,
        onSaveOutcomeConsumed = { saveOutcome = null },
    )
}

@Composable
internal fun ProxySettingsContent(
    endpoint: String,
    display: String,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    saveOutcome: ProxySaveOutcome? = null,
    onSaveOutcomeConsumed: () -> Unit = {},
) {
    // URLs may contain credentials: never persist the editing draft in saved instance state.
    var draft by remember(endpoint) { mutableStateOf(endpoint) }
    var invalid by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saveOutcome) {
        when (val outcome = saveOutcome) {
            ProxySaveOutcome.Saved -> {
                // Clear credentials after confirmed persistence; the status exposes a masked URL.
                draft = ""
                invalid = false
                submitted = true
                saveError = null
            }
            is ProxySaveOutcome.Failed -> {
                invalid = false
                submitted = false
                saveError = outcome.message
            }
            null -> Unit
        }
        if (saveOutcome != null) onSaveOutcomeConsumed()
    }
    fun save(value: String) {
        invalid = false
        submitted = false
        saveError = null
        try {
            onSave(value)
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
                    onValueChange = { draft = it; invalid = false; submitted = false; saveError = null },
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
                saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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

package dev.zakocoder.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.kernel.KernelBindingFailure
import dev.zakocoder.app.kernel.KernelPluginStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Context.activityOrNull(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? Activity
}

/** Explicit recovery actions; showing this panel never starts a service or opens system settings. */
@Composable
internal fun KernelRecoveryActions(
    status: KernelPluginStatus,
    failure: KernelBindingFailure?,
    activationFeedback: Int?,
    enabled: Boolean,
    diagnosticsBusy: Boolean,
    onActivate: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status is KernelPluginStatus.Installed) {
            Text(stringResource(R.string.proxy_plugin_activation_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        failure?.let { Text(stringResource(it.bindingMessageRes()),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        activationFeedback?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is KernelPluginStatus.Installed) {
                WorkbenchSecondaryButton(onClick = onActivate, enabled = enabled, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_plugin_activate))
                }
            }
            TextButton(onClick = onDiagnostics, enabled = enabled && !diagnosticsBusy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(if (diagnosticsBusy) R.string.proxy_plugin_diagnostics_loading else R.string.proxy_plugin_diagnostics))
            }
        }
    }
}

internal fun KernelBindingFailure.bindingMessageRes(): Int = when (this) {
    KernelBindingFailure.Rejected -> R.string.proxy_binding_rejected
    KernelBindingFailure.SecurityDenied -> R.string.proxy_binding_security
    KernelBindingFailure.Timeout -> R.string.proxy_binding_timeout
    KernelBindingFailure.NullBinding -> R.string.proxy_binding_null
    KernelBindingFailure.BindingDied, KernelBindingFailure.Disconnected -> R.string.proxy_binding_disconnected
    KernelBindingFailure.RemoteCallFailed -> R.string.proxy_binding_remote
    KernelBindingFailure.Interrupted -> R.string.proxy_binding_interrupted
    KernelBindingFailure.Unexpected -> R.string.proxy_binding_unexpected
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KernelDiagnosticsSheet(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val diagnosticTitle = stringResource(R.string.proxy_plugin_diagnostics)
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportSnapshot by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = exportSnapshot
        exportSnapshot = null
        if (uri != null && snapshot != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(snapshot) }
                }
                feedback = R.string.proxy_plugin_diagnostics_saved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback = R.string.proxy_plugin_diagnostics_export_failed }
            finally { exporting = false }
        }
    }
    WorkbenchPanelSheet(onDismiss = onDismiss) { _ ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.proxy_plugin_diagnostics), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.proxy_plugin_diagnostics_hint), style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(report, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            feedback?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchSecondaryButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText(diagnosticTitle, report))
                    feedback = R.string.proxy_plugin_diagnostics_copied
                }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_plugin_diagnostics_copy))
                }
                TextButton(onClick = { exportSnapshot = report; export.launch("zakocoder-kernel-diagnostics.txt") },
                    enabled = !exporting, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proxy_plugin_diagnostics_export))
                }
            }
        }
    }
}

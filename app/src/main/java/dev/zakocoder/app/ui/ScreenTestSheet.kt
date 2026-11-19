package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

/** Display-only task projection. Device mode and target selection live outside this panel. */
internal data class VirtualScreenTaskUiModel(
    val active: Boolean = false,
    val busy: Boolean = false,
    val summary: String = "",
    val actionTitle: String = "",
    val actionDetail: String = "",
    val errorMessage: String = "",
    val hasReport: Boolean = false,
    val shareScreenshots: Boolean = false,
)

@Composable
internal fun VirtualScreenTaskPanel(
    model: VirtualScreenTaskUiModel,
    onShare: (Boolean) -> Unit,
    onStop: () -> Unit,
    onReport: () -> Unit,
) {
    WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIcon(Glyph.File, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.screen_test_run), style = MaterialTheme.typography.bodyMedium)
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.actionTitle.ifBlank { model.summary }.ifBlank { stringResource(R.string.screen_test_idle) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.actionDetail.isNotBlank()) Text(model.actionDetail,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.errorMessage.isNotBlank()) Text(model.errorMessage,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (model.active) {
                val label = stringResource(R.string.virtual_screen_share)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    WorkbenchSwitch(checked = model.shareScreenshots, onCheckedChange = onShare,
                        modifier = Modifier.semantics { contentDescription = label })
                }
                Text(stringResource(R.string.virtual_screen_share_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.screen_test_stop))
                }
            }
            if (model.hasReport) TextButton(onClick = onReport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.screen_test_report))
            }
        }
    }
}

@Composable
internal fun ScreenTestReport(report: String?) {
    if (report == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.screen_test_report_loading)) }
    else SelectionContainer {
        Text(report.ifBlank { stringResource(R.string.screen_test_report_empty) }, Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun ScreenTestScreenshotConsent(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    WorkbenchAlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_test_share_confirm_title)) },
        text = { Text(stringResource(R.string.screen_test_share_confirm_body)) },
        confirmButton = { WorkbenchDialogButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.screen_test_share_confirm_action))
        } },
        dismissButton = { WorkbenchDialogButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

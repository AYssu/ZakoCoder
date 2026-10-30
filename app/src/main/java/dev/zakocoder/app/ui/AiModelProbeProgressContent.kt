package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiModelsState
import dev.zakocoder.app.ai.AiProbe
import kotlinx.coroutines.delay

/** Same grouped form vocabulary as model configuration; bounded output scrolls independently. */
@Composable
internal fun AiModelProbeProgressContent(state: AiModelsState) {
    val progress = state.probeProgress ?: return
    var clock by remember(progress.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.probe, progress.startedAt) {
        while (state.probe == AiProbe.Testing) { clock = System.currentTimeMillis(); delay(100) }
    }
    val elapsed = if (state.probe == AiProbe.Testing) (clock - progress.startedAt).coerceAtLeast(progress.elapsedMs) else progress.elapsedMs
    val stage = when {
        state.probe == AiProbe.Cancelled -> R.string.ai_probe_cancelled
        state.probe == AiProbe.Passed -> R.string.ai_probe_verified
        state.probe == AiProbe.Failed -> R.string.ai_probe_failed
        progress.events > 0 -> R.string.ai_probe_receiving
        progress.status != null -> R.string.ai_probe_waiting
        else -> R.string.ai_probe_connecting
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_probe_live_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(stage), style = MaterialTheme.typography.bodySmall,
                color = if (state.probe == AiProbe.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ai_probe_elapsed, elapsed / 1000.0), style = MaterialTheme.typography.labelSmall)
                progress.status?.let { Text(stringResource(R.string.ai_probe_http, it), style = MaterialTheme.typography.labelSmall) }
                Text(stringResource(R.string.ai_probe_events, progress.events), style = MaterialTheme.typography.labelSmall)
                progress.firstEventMs?.let { Text(stringResource(R.string.ai_probe_first_event, it), style = MaterialTheme.typography.labelSmall) }
            }
            if (progress.preview.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.ai_probe_response), style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(progress.preview, modifier = Modifier.fillMaxWidth().heightIn(max = 176.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (progress.truncated) Text(stringResource(R.string.ai_probe_truncated), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.ai_probe_no_execution), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

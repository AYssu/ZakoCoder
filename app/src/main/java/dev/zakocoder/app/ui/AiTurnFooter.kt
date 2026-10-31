package dev.zakocoder.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AiTurnFooter(state: AiState, turn: Int, modifier: Modifier = Modifier) {
    val usage = remember(state.requests, turn) { turnUsage(state.requests, turn) }
    val timing = state.turnTimings.lastOrNull { it.turn == turn }
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UsageCount("↑", usage.input, R.string.ai_usage_input)
            UsageCount("↓", usage.output, R.string.ai_usage_output)
            UsageCount("Σ", usage.total, R.string.ai_usage_total)
            val speed = usage.tokensPerSecond
            Text(if (speed == null) stringResource(R.string.ai_usage_speed_unknown) else stringResource(R.string.ai_usage_speed, speed),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (timing?.finished != null) {
            val seconds = ((timing.finished - timing.started).coerceAtLeast(0) / 1000)
            val date = remember(timing.finished) { SimpleDateFormat("MMM d '·' HH:mm", Locale.getDefault()).format(Date(timing.finished)) }
            Text(stringResource(R.string.ai_worked_for, seconds / 60, seconds % 60, date), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageCount(symbol: String, value: Long?, label: Int) {
    val animated by animateFloatAsState((value ?: 0).toFloat(), tween(450), label = "usage")
    Text(stringResource(label, symbol, if (value == null) "—" else tokenWindowLabel(animated.toLong())),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

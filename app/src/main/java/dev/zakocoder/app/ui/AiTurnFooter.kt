package dev.zakocoder.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal)
    CompositionLocalProvider(LocalTextStyle provides style, LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageCount("↑", usage.input, R.string.ai_usage_input)
                UsageCount("↓", usage.output, R.string.ai_usage_output)
                UsageCount("Σ", usage.total, R.string.ai_usage_total)
            }
            val details = buildList {
                if (timing?.finished != null) {
                    val seconds = (timing.finished - timing.started).coerceAtLeast(0) / 1000
                    val date = remember(timing.finished) { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timing.finished)) }
                    add(stringResource(R.string.ai_worked_for, seconds / 60, seconds % 60, date))
                }
                add(usage.tokensPerSecond?.let { stringResource(R.string.ai_usage_speed, it) }
                    ?: stringResource(R.string.ai_usage_speed_unknown))
                add(if (usage.cached == null) stringResource(R.string.ai_usage_cache_unknown) else
                    stringResource(R.string.ai_usage_cache, tokenWindowLabel(usage.cached),
                        usage.cachePercent?.let { String.format(Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') + "%" } ?: "—"))
            }
            Text(details.joinToString(" · "), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), maxLines = 1)
        }
    }
}

@Composable
private fun UsageCount(symbol: String, value: Long?, label: Int) {
    val animated by animateFloatAsState((value ?: 0).toFloat(), tween(450), label = "usage")
    Text(stringResource(label, symbol, if (value == null) "—" else tokenWindowLabel(animated.toLong())),
        style = LocalTextStyle.current, color = LocalContentColor.current)
}

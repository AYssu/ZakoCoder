package dev.zakocoder.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun UsageTrendCard(buckets: List<AiUsageBucket>, daily: Boolean, zone: ZoneId) {
    var metric by remember { mutableIntStateOf(0) }
    var selected by remember(buckets) { mutableIntStateOf(buckets.indexOfLast { it.summary.requestCount > 0 || it.summary.toolCount > 0 }.coerceAtLeast(0)) }
    val labels = listOf(R.string.usage_chart_requests, R.string.usage_chart_tokens, R.string.usage_chart_tools)
    fun value(summary: AiUsageSummary): Long? = when (metric) {
        1 -> summary.totalTokens
        2 -> summary.toolCount.toLong()
        else -> summary.requestCount.toLong()
    }
    val values = buckets.map { value(it.summary) }
    val max = (values.filterNotNull().maxOrNull() ?: 0).coerceAtLeast(1L)
    val formatter = remember(daily, zone) { DateTimeFormatter.ofPattern(if (daily) "HH:mm xxx" else "MM-dd", Locale.ROOT).withZone(zone) }
    val selectedBucket = buckets.getOrNull(selected)
    val selectedValue = selectedBucket?.let { bucket -> when (metric) {
        1 -> bucket.summary.totalTokens?.let { stringResource(R.string.usage_chart_tokens_value, tokenWindowLabel(it)) } ?: stringResource(R.string.usage_chart_unknown)
        2 -> stringResource(R.string.usage_chart_tools_value, bucket.summary.toolCount)
        else -> stringResource(R.string.usage_chart_requests_value, bucket.summary.requestCount)
    } }.orEmpty()
    val accessibility = stringResource(R.string.usage_chart_accessible, stringResource(labels[metric]), buckets.size)
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.primary.copy(alpha = .75f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(12.dp), border = cardBorder(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.usage_chart_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    FilterChip(selected = metric == index, onClick = { metric = index }, label = { Text(stringResource(label)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(if (daily) R.string.usage_chart_hourly else R.string.usage_chart_daily), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tokenWindowLabel(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.fillMaxWidth().height(132.dp).semantics { contentDescription = accessibility }
                .pointerInput(buckets) {
                    detectTapGestures { point ->
                        if (buckets.isNotEmpty() && size.width > 0) {
                            selected = ((point.x / size.width) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                        }
                    }
                }) {
                repeat(4) { index -> val y = size.height * index / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
                if (values.isNotEmpty()) {
                    val slot = size.width / values.size
                    drawRect(primary.copy(alpha = .08f), Offset(slot * selected, 0f), Size(slot, size.height))
                    values.forEachIndexed { index, value ->
                        if (value != null && value > 0) {
                            val height = ((value.toDouble() / max) * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                            drawRect(if (index == selected) primary else muted, Offset(slot * index + slot * .18f, size.height - height), Size(slot * .64f, height))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOfNotNull(buckets.firstOrNull(), buckets.lastOrNull()).forEach {
                    Text(formatter.format(Instant.ofEpochMilli(it.started)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected-- }, enabled = selected > 0) {
                    WorkbenchIcon(Glyph.Back, description = stringResource(R.string.usage_chart_previous))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(selectedBucket?.let { formatter.format(Instant.ofEpochMilli(it.started)) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selectedValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { selected++ }, enabled = selected < buckets.lastIndex) {
                    WorkbenchIcon(Glyph.Next, description = stringResource(R.string.usage_chart_next))
                }
            }
            if (metric == 1) Text(stringResource(R.string.usage_chart_reported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun UsageModelDistribution(models: List<AiUsageModelCount>) {
    val total = models.sumOf { it.requests.toLong() }.coerceAtLeast(1L)
    Surface(shape = RoundedCornerShape(12.dp), border = cardBorder(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.usage_chart_model_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            models.take(5).forEach { entry ->
                val share = entry.requests.toFloat() / total
                val progress by animateFloatAsState(share, tween(300), label = "usageModelShare")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.model.ifBlank { stringResource(R.string.ai_usage_missing_model) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    Text(stringResource(R.string.usage_chart_model_value, entry.requests, String.format(Locale.ROOT, "%.1f", share * 100)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (models.size > 5) Text(stringResource(R.string.usage_chart_model_more, models.size - 5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

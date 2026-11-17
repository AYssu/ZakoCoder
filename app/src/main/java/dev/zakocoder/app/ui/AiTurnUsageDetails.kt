package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiTurnTiming
import dev.zakocoder.app.ai.AiTurnUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun turnUsageDuration(timing: AiTurnTiming?): String = timing?.finished?.let { finished ->
    val seconds = (finished - timing.started).coerceAtLeast(0) / 1000
    "${seconds / 60}m ${seconds % 60}s"
} ?: "—"

@Composable
internal fun AiTurnUsageDetails(usage: AiTurnUsage, timing: AiTurnTiming?, context: TurnUsageContext?,
    summary: String, colors: TurnUsageColors, onDismiss: () -> Unit) {
    if (!LocalWorkbenchPageInteractive.current) return
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val shadowInset = with(LocalDensity.current) { 8.dp.roundToPx() }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeight * .8f).toDp() }
    val position = remember(gap, shadowInset) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            // The transparent inset belongs to the popup window, not the visible card.
            val edge = (gap - shadowInset).coerceAtLeast(0)
            val left = (anchorBounds.right - popupContentSize.width + shadowInset)
                .coerceIn(edge, maxOf(edge, windowSize.width - popupContentSize.width - edge))
            val above = anchorBounds.top - popupContentSize.height - gap + shadowInset
            val top = (if (above >= edge) above else anchorBounds.bottom + gap - shadowInset)
                .coerceIn(edge, maxOf(edge, windowSize.height - popupContentSize.height - edge))
            return IntOffset(left, top)
        }
    } }
    var closing by remember { mutableStateOf(false) }
    val transition = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        transition.animateTo(if (closing) 0f else 1f, tween(160))
        if (closing) onDismiss()
    }
    val arrival = transition.value
    Popup(popupPositionProvider = position, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true)) {
        Box(Modifier.graphicsLayer {
            alpha = arrival; scaleX = .97f + .03f * arrival; scaleY = scaleX
            translationY = (1 - arrival) * 5.dp.toPx()
        }.padding(8.dp)) {
            Surface(Modifier.width(216.dp).heightIn(max = maxHeight).testTag("ai-turn-usage-details"),
                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.turn_usage_details_title), style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp), color = colors.strong)
                        val finished = timing?.finished?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"
                        Text(finished, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace), color = colors.faint)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(usage.total?.let(::tokenWindowLabel) ?: "—", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp,
                                lineHeight = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = colors.strong)
                            Text("token", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.faint)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            DetailNumber("↑", usage.input?.let(::tokenWindowLabel) ?: "—", colors)
                            DetailNumber("↓", usage.output?.let(::tokenWindowLabel) ?: "—", colors)
                        }
                    }
                    HorizontalDivider(color = colors.track.copy(alpha = .55f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DetailRow(stringResource(R.string.turn_usage_duration), turnUsageDuration(timing), colors)
                        DetailRow(stringResource(R.string.turn_usage_speed), usage.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) } ?: "—", colors)
                        val cached = usage.cached?.let { count ->
                            val percent = usage.cachePercent?.let { String.format(Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.') }
                            if (percent == null) tokenWindowLabel(count) else "${tokenWindowLabel(count)} ($percent%)"
                        } ?: "—"
                        DetailRow(stringResource(R.string.turn_usage_cache), cached, colors, "ai-turn-usage-detail-cache")
                        DetailRow(stringResource(R.string.turn_usage_summary), summary, colors)
                    }
                    HorizontalDivider(color = colors.track.copy(alpha = .55f))
                    Row(Modifier.testTag("ai-turn-usage-detail-context"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        UsageContextRing(context, colors, Modifier.size(26.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.turn_usage_context_budget), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.secondary)
                            Text(context?.let { "${it.percentage}%" } ?: "—", style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = usageContextColor(context, colors))
                            Text(context?.let { stringResource(R.string.turn_usage_context_capacity, tokenWindowLabel(it.used), tokenWindowLabel(it.capacity.toLong())) } ?: "—",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace), color = colors.secondary)
                            if (context != null) Text(stringResource(if (context.used <= context.capacity) R.string.turn_usage_context_remaining else R.string.turn_usage_context_exceeded,
                                tokenWindowLabel(kotlin.math.abs(context.capacity.toLong() - context.used))),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace), color = colors.secondary)
                        }
                    }
                    Text(stringResource(R.string.turn_usage_context_note), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.secondary)
                    if (usage.partial) Text(stringResource(R.string.turn_usage_partial_note), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.secondary)
                }
            }
        }
    }
}

@Composable
private fun DetailNumber(symbol: String, value: String, colors: TurnUsageColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(symbol, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = colors.faint)
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = colors.number)
    }
}

@Composable
private fun DetailRow(label: String, value: String, colors: TurnUsageColors, tag: String? = null) {
    Row(Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = colors.secondary)
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace), color = colors.number)
    }
}

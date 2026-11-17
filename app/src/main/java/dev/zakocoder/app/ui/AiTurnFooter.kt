package dev.zakocoder.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import java.util.Locale
import kotlin.math.roundToInt

internal data class TurnUsageContext(val used: Long, val capacity: Int) {
    val percentage: Int get() = (used.toDouble() / capacity * 100).roundToInt()
}

/** A historical turn must never borrow the latest turn's context or cumulative token usage. */
internal fun turnUsageContext(state: AiState, turn: Int): TurnUsageContext? {
    val requests = state.requests.filter { it.turn == turn }
    val latest = requests.lastOrNull { !it.compaction }
    if (latest != null) return latest.takeIf { it.estimatedContextTokens > 0 && it.contextWindowTokens > 0 }
        ?.let { TurnUsageContext(it.estimatedContextTokens, it.contextWindowTokens) }
    return if (requests.isEmpty() && turn == state.turn && state.estimatedContextTokens > 0 && state.contextWindowTokens > 0)
        TurnUsageContext(state.estimatedContextTokens, state.contextWindowTokens) else null
}

internal data class TurnUsageColors(val faint: Color, val secondary: Color, val number: Color, val strong: Color, val track: Color)

@Composable
internal fun turnUsageColors(): TurnUsageColors = if (LocalWorkbenchDark.current)
    TurnUsageColors(Color(0xFF77777E), Color(0xFFA0A0A8), Color(0xFFC6C6CD), Color(0xFFEDEDF0), Color(0xFF414147)) else
    TurnUsageColors(Color(0xFFB7B7BD), Color(0xFF8A8A92), Color(0xFF4B4B52), Color(0xFF1C1C1F), Color(0xFFE2E2DD))

@Composable
internal fun AiTurnFooter(state: AiState, turn: Int, modifier: Modifier = Modifier) {
    val usage = remember(state.requests, turn) { turnUsage(state.requests, turn) }
    val context = remember(state.requests, state.turn, state.estimatedContextTokens, state.contextWindowTokens, turn) { turnUsageContext(state, turn) }
    val timing = state.turnTimings.lastOrNull { it.turn == turn }
    val compacted = state.requests.any { it.turn == turn && it.compaction }
    val colors = turnUsageColors()
    val textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace,
        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val input = animatedUsageLabel(usage.input)
    val output = animatedUsageLabel(usage.output)
    val total = animatedUsageLabel(usage.total)
    val duration = turnUsageDuration(timing)
    val speed = usage.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    val cache = usage.cached?.let(::tokenWindowLabel) ?: "—"
    val durationLabel = stringResource(R.string.turn_usage_duration)
    val cacheLabel = stringResource(R.string.turn_usage_cache)
    val summaryLabel = stringResource(R.string.turn_usage_summary)
    val turnRecords = state.requests.filter { it.turn == turn }
    val summaryValue = when {
        compacted -> stringResource(R.string.turn_usage_summary_triggered)
        turnRecords.isEmpty() -> "—"
        state.historyTrimmed || state.requests.size >= 32 || turnRecords.filter { it.round > 0 }.minOfOrNull { it.round }?.let { it != 1 } == true ->
            stringResource(R.string.turn_usage_summary_unrecorded)
        else -> stringResource(R.string.turn_usage_summary_none)
    }
    val contextLabel = stringResource(R.string.turn_usage_context)
    val percentage = context?.let { "${it.percentage}%" } ?: "—"
    val contextDescription = context?.let { stringResource(R.string.turn_usage_context_description,
        it.used.toString(), it.capacity.toString(), percentage) } ?: stringResource(R.string.turn_usage_context_unknown)
    val measured = rememberTextMeasurer()
    val density = LocalDensity.current
    var expanded by remember(state.conversationId, turn) { mutableStateOf(false) }

    fun field(symbol: String, value: String, sum: Boolean = false, unit: String = ""): AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = if (sum) colors.number else colors.secondary)) { append(symbol) }
        append(" ")
        withStyle(SpanStyle(color = if (sum) colors.strong else colors.number,
            fontWeight = if (sum) FontWeight.SemiBold else FontWeight.Medium)) { append(value) }
        if (unit.isNotEmpty()) withStyle(SpanStyle(color = colors.faint)) { append(" $unit") }
    }
    fun detail(label: String, value: String) = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.faint, fontFamily = FontFamily.Default)) { append(label); append(" ") }
        withStyle(SpanStyle(color = colors.number)) { append(value) }
    }
    val inputField = field("↑", input)
    val outputField = field("↓", output)
    val totalField = field("Σ", total, sum = true, unit = "token")
    val durationField = detail(durationLabel, "$duration · $speed tok/s")
    val cacheField = detail(cacheLabel, cache)
    val summaryField = detail(summaryLabel, summaryValue)

    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("ai-turn-usage-row")) {
        val available = constraints.maxWidth
        fun textWidth(text: AnnotatedString) = measured.measure(text, style = textStyle, maxLines = 1, softWrap = false).size.width
        val rowGap = if (maxWidth < 432.dp) 4.dp else 10.dp
        val gap = with(density) { rowGap.roundToPx() }
        val dividerWidth = with(density) { 11.dp.roundToPx() }
        val labelWidth = measured.measure(contextLabel, style = textStyle.copy(fontFamily = FontFamily.Default), maxLines = 1, softWrap = false).size.width
        val percentageWidth = measured.measure(percentage, style = textStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false).size.width
        val infoWidth = measured.measure("ⓘ", style = textStyle.copy(fontSize = 12.sp), maxLines = 1, softWrap = false).size.width
        val contextFullWidth = labelWidth + percentageWidth + infoWidth + with(density) { 43.dp.roundToPx() }
        val contextCompactWidth = percentageWidth + infoWidth + with(density) { 37.dp.roundToPx() }
        val requestedTier = when {
            maxWidth >= 1010.dp -> 5
            maxWidth >= 850.dp -> 4
            maxWidth >= 680.dp -> 3
            else -> 2
        }
        val compactTotalField = field("Σ", total, sum = true)
        fun tierWidth(tier: Int, compactContext: Boolean = false, tokenUnit: Boolean = true): Int =
            textWidth(if (tokenUnit) totalField else compactTotalField) +
            (if (compactContext) contextCompactWidth else contextFullWidth) + gap * 2 +
            (if (tier >= 2) textWidth(inputField) + textWidth(outputField) + gap * 2 else 0) +
            (if (tier >= 3) textWidth(durationField) + dividerWidth + gap else 0) +
            (if (tier >= 4) textWidth(cacheField) + dividerWidth + gap else 0) +
            (if (tier >= 5) textWidth(summaryField) + dividerWidth + gap else 0)
        // Keep both input/output on phones: remove a repeated unit, then the context
        // label before sacrificing useful usage fields. Every candidate is measured.
        val candidates = (requestedTier downTo 2).map { Triple(it, false, true) } + listOf(
            Triple(2, false, false), Triple(2, true, false),
            Triple(1, false, true), Triple(1, true, true), Triple(1, true, false))
        val layout = candidates.firstOrNull { (tier, compact, unit) -> tierWidth(tier, compact, unit) <= available }
            ?: Triple(1, true, false)
        val (tier, compactContext, showTokenUnit) = layout
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rowGap)) {
            if (tier >= 2) {
                UsageField(inputField, stringResource(R.string.ai_usage_input, "↑", usage.input?.let(::tokenWindowLabel) ?: "—"), "input", textStyle)
                UsageField(outputField, stringResource(R.string.ai_usage_output, "↓", usage.output?.let(::tokenWindowLabel) ?: "—"), "output", textStyle)
            }
            UsageField(if (showTokenUnit) totalField else field("Σ", total, sum = true),
                stringResource(R.string.ai_usage_total, "Σ", usage.total?.let(::tokenWindowLabel) ?: "—"), "total", textStyle)
            if (tier >= 3) { UsageDivider(colors); UsageField(durationField, tag = "duration", style = textStyle) }
            if (tier >= 4) { UsageDivider(colors); UsageField(cacheField, tag = "cache", style = textStyle) }
            if (tier >= 5) { UsageDivider(colors); UsageField(summaryField, tag = "summary", style = textStyle) }
            Spacer(Modifier.weight(1f))
            Box {
                Row(Modifier.heightIn(min = 48.dp).testTag("ai-turn-usage-context")
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { contentDescription = contextLabel; stateDescription = contextDescription }
                    .padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    UsageContextRing(context, colors, Modifier.size(17.dp))
                    if (!compactContext) Text(contextLabel, style = textStyle.copy(fontFamily = FontFamily.Default), color = colors.secondary, maxLines = 1, softWrap = false)
                    Text(percentage, style = textStyle.copy(fontWeight = FontWeight.SemiBold), color = usageContextColor(context, colors), maxLines = 1, softWrap = false)
                    Text("ⓘ", style = textStyle.copy(fontSize = 12.sp), color = colors.faint, maxLines = 1)
                }
                if (expanded) AiTurnUsageDetails(usage, timing, context, summaryValue, colors) { expanded = false }
            }
        }
    }
}

@Composable
private fun animatedUsageLabel(value: Long?): String {
    val animated by animateFloatAsState((value ?: 0).toFloat(), tween(450), label = "usage")
    return value?.let { tokenWindowLabel(animated.toLong()) } ?: "—"
}

@Composable
private fun UsageField(value: AnnotatedString, description: String? = null, tag: String,
    style: androidx.compose.ui.text.TextStyle) {
    Text(value, Modifier.testTag("ai-turn-usage-$tag").then(if (description == null) Modifier else
        Modifier.semantics { contentDescription = description }), style = style, maxLines = 1, softWrap = false)
}

@Composable
private fun UsageDivider(colors: TurnUsageColors) {
    Canvas(Modifier.width(1.dp).height(12.dp)) { drawLine(colors.track, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = size.width) }
}

internal fun usageContextColor(context: TurnUsageContext?, colors: TurnUsageColors): Color = when {
    context == null -> colors.secondary
    context.percentage > 100 -> if (colors.strong.luminance() > .5f) Color(0xFFEF8D85) else Color(0xFFB4372C)
    context.percentage >= 80 -> if (colors.strong.luminance() > .5f) Color(0xFFE9B46A) else Color(0xFFB4530A)
    else -> colors.strong
}

@Composable
internal fun UsageContextRing(context: TurnUsageContext?, colors: TurnUsageColors, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState((context?.let { it.used.toFloat() / it.capacity } ?: 0f).coerceIn(0f, 1f), tween(450), label = "context-usage")
    val color = usageContextColor(context, colors)
    Canvas(modifier) {
        val stroke = size.minDimension * .11f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawCircle(colors.track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (progress > 0) drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

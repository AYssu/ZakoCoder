package dev.zakocoder.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import kotlin.math.roundToInt

/** Drag previews stay local; only release commits a setting to the selected provider. */
@Composable
internal fun ReasoningStrengthSlider(profile: AiProfile, enabled: Boolean,
    onSelect: (AiThinking, AiReasoningEffort?) -> Unit) {
    val levels = remember(profile.protocol) {
        listOf(Triple(R.string.ai_options_disabled, AiThinking.Disabled, null),
            Triple(R.string.ai_options_provider_default, AiThinking.Default, null)) +
            AiReasoningEffort.entries.filter { profile.protocol != AiApiProtocol.Gemini || it != AiReasoningEffort.Max }
                .map { Triple(it.labelResource(), AiThinking.Enabled, it) }
    }
    val saved = levels.indexOfFirst { (_, thinking, effort) ->
        profile.parameters.thinking == thinking && profile.parameters.reasoningEffort == effort
    }.let { if (it < 0) 1 else it }
    var preview by remember(profile.id, saved) { mutableIntStateOf(saved) }
    val latestSave by rememberUpdatedState(onSelect)
    val labels = levels.map { stringResource(it.first) }
    val title = stringResource(R.string.ai_options_effort)
    val colors = listOf(Color(0xFF8E8E93), Color(0xFF0A7CFF), Color(0xFF3B82F6),
        Color(0xFF6D5EF0), Color(0xFFE8890C), Color(0xFFF0435F))
    val accent by animateColorAsState(colors[preview], label = "reasoning-color")
    val position by animateFloatAsState(preview.toFloat(), spring(dampingRatio = .88f, stiffness = 215f), label = "reasoning-thumb")
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
            Text(labels[preview], fontSize = 11.sp, lineHeight = 16.sp, color = accent, fontWeight = FontWeight.Medium)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("ai-reasoning-slider")
            .semantics {
                contentDescription = title
                stateDescription = labels[preview]
                progressBarRangeInfo = ProgressBarRangeInfo(preview.toFloat(), 0f..levels.lastIndex.toFloat(), levels.size - 2)
                if (!enabled) disabled()
                setProgress { value ->
                    if (!enabled || !value.isFinite()) false else {
                        val next = value.roundToInt().coerceIn(levels.indices)
                        preview = next
                        val (_, thinking, effort) = levels[next]
                        latestSave(thinking, effort)
                        true
                    }
                }
            }
            .pointerInput(profile.id, enabled, saved, levels.size) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun index(x: Float) = (x / (size.width.toFloat() / levels.size)).toInt().coerceIn(levels.indices)
                    preview = index(down.position.x)
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            preview = index(change.position.x)
                            change.consume()
                            if (!change.pressed) { released = true; break }
                        }
                    } finally {
                        if (!released) preview = saved
                    }
                    if (released && preview != saved) {
                        val (_, thinking, effort) = levels[preview]
                        latestSave(thinking, effort)
                    }
                }
            }) {
            // Keep the 48dp gesture target while making the visual control lighter.
            val railHeight = 28.dp.toPx()
            val top = (size.height - railHeight) / 2
            val centerY = size.height / 2
            val step = size.width / levels.size
            val centerX = ((position + .5f) * step).coerceIn(0f, size.width)
            // Center the fill's rounded end under the knob instead of ending at its center.
            val fillWidth = (centerX + railHeight / 2).coerceAtMost(size.width)
            val radius = minOf(12.dp.toPx(), step / 2 - 1.dp.toPx())
            val round = CornerRadius(railHeight / 2)
            drawRoundRect(scheme.surfaceVariant.copy(alpha = .65f), Offset(0f, top), Size(size.width, railHeight), round)
            drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = .65f), accent)),
                Offset(0f, top), Size(fillWidth, railHeight), round, alpha = if (enabled) 1f else .4f)
            drawLine(scheme.onSurfaceVariant.copy(alpha = .2f), Offset(step, centerY - 6.dp.toPx()),
                Offset(step, centerY + 6.dp.toPx()), 1.dp.toPx())
            levels.indices.forEach { i ->
                val x = (i + .5f) * step
                val color = if (x <= centerX) Color.White.copy(alpha = .85f) else scheme.onSurfaceVariant.copy(alpha = .35f)
                drawCircle(color, if (i == 1) 3.dp.toPx() else 2.dp.toPx(), Offset(x, centerY),
                    style = if (i == 1) Stroke(1.dp.toPx()) else androidx.compose.ui.graphics.drawscope.Fill)
            }
            // A circular knob, including while pressed; the soft shadow does not change its shape.
            drawCircle(Color.Black.copy(alpha = .06f), radius + 2.dp.toPx(), Offset(centerX, centerY + 2.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = .05f), radius + 1.dp.toPx(), Offset(centerX, centerY + 1.dp.toPx()))
            drawCircle(Color.White, radius, Offset(centerX, centerY))
            drawCircle(Color.Black.copy(alpha = .06f), radius, Offset(centerX, centerY), style = Stroke(.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1,
                        color = if (i == preview) accent else scheme.onSurfaceVariant,
                        fontWeight = if (i == preview) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.ai.AiPhase

@Composable
internal fun AiConversationProgress(state: AiState, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onDetails),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            AiPhaseText(stringResource(state.phase.label),
                animated = state.active && state.phase in setOf(AiPhase.Requesting, AiPhase.Receiving))
        }
        if (state.plan.isNotEmpty()) Text(stringResource(R.string.ai_plan_progress, state.plan.count { it.status == "completed" }, state.plan.size),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
    }
}

/** Only the glyph drawing changes: the label, layout and accessibility text remain stable. */
@Composable
internal fun AiPhaseText(label: String, animated: Boolean) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val emphasis = MaterialTheme.colorScheme.onSurface
    val motion = if (animated) {
        val transition = rememberInfiniteTransition(label = "phase-sweep")
        val progress = transition.animateFloat(initialValue = -0.7f, targetValue = 1.7f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "phase-sweep-position")
        Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    // Read animation in drawing, so a frame never recomposes the conversation.
                    val center = size.width * progress.value
                    val radius = size.width * 0.45f
                    drawRect(Brush.linearGradient(listOf(base, emphasis, base),
                        start = Offset(center - radius, 0f), end = Offset(center + radius, 0f)),
                        blendMode = BlendMode.SrcIn)
                }
            }
    } else Modifier
    Text(label, motion, style = MaterialTheme.typography.labelSmall, color = base)
}

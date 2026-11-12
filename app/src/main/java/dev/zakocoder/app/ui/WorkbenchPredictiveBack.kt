package dev.zakocoder.app.ui

import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalWorkbenchPageInteractive = staticCompositionLocalOf { true }

/** Gesture progress affects drawing only; navigation happens only after a committed gesture. */
internal class WorkbenchBackMotion(val modifier: Modifier, val close: () -> Unit)

@Composable
internal fun rememberWorkbenchBackMotion(enabled: Boolean = true, resetKey: Any? = Unit, onBack: () -> Unit): WorkbenchBackMotion {
    val view = LocalView.current
    val close by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    var fromRight by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.background
    val maxTravel = with(LocalDensity.current) { 56.dp.toPx() }
    val commit: () -> Unit = {
        // Android respects the user's system haptic setting; no vibrator permission is needed.
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        close()
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        settling?.cancel()
        try {
            // A new gesture can interrupt the return spring without snapping the page home.
            val interruptedProgress = progress.value
            val previousEdge = fromRight
            events.collect { event ->
                fromRight = event.swipeEdge == BackEventCompat.EDGE_RIGHT
                val raw = event.progress.coerceIn(0f, 1f)
                val carried = if (previousEdge == fromRight) interruptedProgress else 0f
                progress.snapTo(carried + (1f - carried) * raw)
            }
            // Keep the outgoing page at its gesture position until navigation removes it.
            // Resetting here would produce a one-frame jump before the exit transition.
            commit()
        } catch (cancelled: CancellationException) {
            settling = scope.launch {
                progress.animateTo(0f, spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = 650f,
                    visibilityThreshold = .0005f,
                ))
            }
            throw cancelled
        }
    }
    LaunchedEffect(resetKey) {
        settling?.cancel()
        progress.snapTo(0f)
    }
    val modifier = Modifier.graphicsLayer {
        val amount = progress.value.coerceIn(0f, 1f)
        translationX = minOf(size.width * .14f, maxTravel) * amount * if (fromRight) -1f else 1f
        scaleX = 1f - .018f * amount
        scaleY = scaleX
        transformOrigin = TransformOrigin.Center
        shape = RoundedCornerShape((16f * amount).dp)
        clip = amount > 0f
    }.background(color)
    return WorkbenchBackMotion(modifier, commit)
}

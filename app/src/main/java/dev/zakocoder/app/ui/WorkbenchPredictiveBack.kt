package dev.zakocoder.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

internal val LocalWorkbenchPageInteractive = staticCompositionLocalOf { true }

/** Gesture progress affects drawing only; navigation happens only after a committed gesture. */
internal class WorkbenchBackMotion(val modifier: Modifier, val close: () -> Unit, val progress: State<Float>,
    val exitProgress: State<Float>, val exiting: State<Boolean>)

@Composable
internal fun rememberWorkbenchBackMotion(enabled: Boolean = true, resetKey: Any? = Unit, dialogWindow: Boolean = false,
    animateExit: Boolean = true, onBack: () -> Unit): WorkbenchBackMotion = key(resetKey) {
    // A destination starts with its own neutral layer in the same composition. Resetting
    // the old layer in LaunchedEffect leaves one frame where the new page is off-screen.
    rememberWorkbenchBackMotionState(enabled, dialogWindow, animateExit, onBack)
}

@Composable
private fun rememberWorkbenchBackMotionState(enabled: Boolean, dialogWindow: Boolean,
    animateExit: Boolean, onBack: () -> Unit): WorkbenchBackMotion {
    val view = LocalView.current
    val close by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val exiting = remember { mutableStateOf(false) }
    val canAnimateExit by rememberUpdatedState(animateExit)
    var settling by remember { mutableStateOf<Job?>(null) }
    var fromRight by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.background
    val commit: () -> Unit = {
        if (!exiting.value) {
            exiting.value = true
            settling?.cancel()
            settling = scope.launch {
                if (canAnimateExit) {
                    // Finish from the exact dragged position, then remove the page only
                    // after its transparent, off-screen end state has reached a draw frame.
                    exit.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                    withFrameNanos { }
                    withFrameNanos { }
                    // A background save can change the guard during the short exit.
                    // Do not leave a retained editor transparent and unable to receive input.
                    if (!canAnimateExit) {
                        exit.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
                        progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = 650f, visibilityThreshold = .0005f))
                        exiting.value = false
                    }
                    view.withWorkbenchClickFeedback { close() }
                } else {
                    // A dirty/busy destination can refuse to leave. Restore its surface
                    // before requesting confirmation instead of briefly exposing its parent.
                    progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 650f, visibilityThreshold = .0005f))
                    exiting.value = false
                    view.withWorkbenchClickFeedback { close() }
                }
            }
        }
    }
    val interactive = enabled && LocalWorkbenchPageInteractive.current
    if (dialogWindow) {
        // Compose dialogs own a NavigationEvent dispatcher. Activity handlers do not receive
        // this window's gesture progress, even though the final dismiss callback still works.
        val navigation = rememberNavigationEventState(NavigationEventInfo.None)
        var gestureActive by remember { mutableStateOf(false) }
        var carriedProgress by remember { mutableFloatStateOf(0f) }
        NavigationBackHandler(navigation, isBackEnabled = interactive,
            onBackCompleted = { gestureActive = false; commit() },
            onBackCancelled = {
                gestureActive = false
                if (!exiting.value) { settling?.cancel(); settling = scope.launch {
                    progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 650f, visibilityThreshold = .0005f))
                } }
            })
        LaunchedEffect(navigation.transitionState) {
            val event = (navigation.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent
                ?: return@LaunchedEffect
            if (exiting.value) return@LaunchedEffect
            settling?.cancel()
            val nextEdge = event.swipeEdge == NavigationEvent.EDGE_RIGHT
            if (!gestureActive) {
                carriedProgress = if (nextEdge == fromRight) progress.value else 0f
                gestureActive = true
            }
            fromRight = nextEdge
            progress.snapTo(carriedProgress + (1f - carriedProgress) * event.progress.coerceIn(0f, 1f))
        }
    }
    PredictiveBackHandler(enabled = interactive && !dialogWindow) { events ->
        // Keep consuming Back while the old page is exiting; never dispatch it to a parent.
        if (exiting.value) { events.collect { }; return@PredictiveBackHandler }
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
            if (!exiting.value) settling = scope.launch {
                progress.animateTo(0f, spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = 650f,
                    visibilityThreshold = .0005f,
                ))
            }
            throw cancelled
        }
    }
    val modifier = Modifier.graphicsLayer {
        val amount = progress.value.coerceIn(0f, 1f)
        val finishing = exit.value.coerceIn(0f, 1f)
        // Reveal meaningful parent content, rather than only its empty outer padding.
        val dragged = .72f * amount
        translationX = size.width * (dragged + (1.02f - dragged) * finishing) * if (fromRight) -1f else 1f
        // Keep text legible while dragging; the stronger fade belongs to the confirmed exit.
        alpha = (1f - .12f * amount) * (1f - finishing)
        scaleX = 1f - .018f * amount
        scaleY = scaleX
        transformOrigin = TransformOrigin.Center
        shape = RoundedCornerShape((16f * amount).dp)
        // Keep the same clipping path through the last return frame. Toggling clipping
        // off exactly at zero can rebuild the layer as the page lands, producing a flash.
        clip = true
    }.background(color).then(if (exiting.value) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    } else Modifier)
    return WorkbenchBackMotion(modifier, commit, progress.asState(), exit.asState(), exiting)
}

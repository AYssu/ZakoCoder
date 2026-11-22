package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One retained destination follows the drag and survives the release animation. */
@Stable
internal class ConversationWorkspaceMotion(private val scope: CoroutineScope, private val dismissed: () -> Unit) {
    var progress by mutableFloatStateOf(1f)
        private set
    var transitioning by mutableStateOf(false)
        private set
    var dragging by mutableStateOf(false)
        private set
    private var width = 1f
    private var settling: Job? = null

    fun updateViewportWidth(viewportWidth: Float) { width = viewportWidth.coerceAtLeast(1f) }

    fun beginDrag() {
        settling?.cancel()
        progress = 0f
        dragging = true
        transitioning = true
    }

    fun open() {
        settling?.cancel()
        progress = 0f
        transitioning = true
        dragging = false
        settle(true)
    }

    fun dragBy(delta: Float) {
        if (dragging) progress = (progress - delta / width).coerceIn(0f, 1f)
    }

    fun release(completed: Boolean, velocity: Float) {
        if (!dragging) return
        dragging = false
        val commit = completed && if (kotlin.math.abs(velocity) > width * .9f) velocity < 0f else progress >= .28f
        settle(commit)
    }

    private fun settle(open: Boolean) {
        settling = scope.launch {
            val animation = Animatable(progress)
            animation.animateTo(if (open) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f,
                    visibilityThreshold = .0005f)) { progress = value }
            // Present the last gesture frame before removing the cancelled destination.
            withFrameNanos { }
            if (!open) dismissed()
            transitioning = false
        }
    }
}

@Composable
internal fun rememberConversationWorkspaceMotion(onDismiss: () -> Unit): ConversationWorkspaceMotion {
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    return remember(scope) { ConversationWorkspaceMotion(scope) { dismiss() } }
}

/** Claim only leftward message drags. Rightward drags belong to the existing drawer. */
@Composable
internal fun Modifier.conversationSwipeNavigation(enabled: Boolean, motion: ConversationWorkspaceMotion?,
    onStart: (() -> Unit)?): Modifier {
    val startDestination by rememberUpdatedState(onStart)
    return pointerInput(enabled, motion, onStart != null) {
        if (!enabled || motion == null || startDestination == null) return@pointerInput
        val edge = 24 * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed || down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            val open = startDestination ?: return@awaitEachGesture
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, beyond ->
                // Vertical reading, long text selection and right swipes keep their owners.
                if (beyond < 0f && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    motion.beginDrag()
                    open()
                    motion.dragBy(beyond)
                    velocity.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                }
            } ?: return@awaitEachGesture
            if (!motion.dragging) return@awaitEachGesture
            var completed = false
            try {
                completed = horizontalDrag(start.id) { change ->
                    velocity.addPosition(change.uptimeMillis, change.position)
                    motion.dragBy(change.positionChange().x)
                    change.consume()
                }
            } finally {
                motion.release(completed, velocity.calculateVelocity().x)
            }
        }
    }
}

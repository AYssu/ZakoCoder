package dev.zakocoder.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlin.math.exp

/** Follows measured layout growth, without restarting an animation on every stream packet. */
@Composable
internal fun ConversationEndFollower(state: LazyListState, conversation: Any?, enabled: Boolean) {
    var positioned by remember(state, conversation) { mutableStateOf(false) }
    LaunchedEffect(state, conversation, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        // Opening history should show its end immediately. Subsequent growth moves continuously.
        if (!positioned) {
            state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
            positioned = true
        }
        while (true) {
            snapshotFlow { state.distanceToEnd() }.first { it > 0f }
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val distance = state.distanceToEnd()
                if (distance <= 0f) break
                // A capped frame interval prevents a delayed frame from becoming a visible jump.
                val elapsed = ((now - previous) / 1_000_000f).coerceIn(0f, 32f)
                previous = now
                val delta = if (distance < .5f) distance else distance * (1f - exp(-elapsed / 80f))
                state.scrollBy(delta)
            }
        }
    }
}

private fun LazyListState.distanceToEnd(): Float {
    if (!canScrollForward) return 0f
    val layout = layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return 0f
    return if (last.index == layout.totalItemsCount - 1) {
        (last.offset + last.size - layout.viewportEndOffset + layout.afterContentPadding)
            .toFloat().coerceAtLeast(0f)
    } else layout.viewportSize.height.toFloat()
}

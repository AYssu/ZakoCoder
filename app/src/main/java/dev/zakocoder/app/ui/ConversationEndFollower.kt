package dev.zakocoder.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlin.math.exp

/** Follows measured layout growth, without restarting an animation on every stream packet. */
@Composable
internal fun ConversationEndFollower(state: LazyListState, conversation: Any?, enabled: Boolean,
    canFollow: () -> Boolean = { true }) {
    val currentCanFollow by rememberUpdatedState(canFollow)
    var positioned by remember(state, conversation) { mutableStateOf(false) }
    LaunchedEffect(state, conversation, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount > 0 && currentCanFollow() }.first { it }
        // Opening history should show its end immediately. Subsequent growth moves continuously.
        if (!positioned) {
            state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
            positioned = true
        }
        while (true) {
            snapshotFlow { if (currentCanFollow()) state.distanceToEnd() else 0f }.first { it > 0f }
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // User input can pause us inside nested scroll before recomposition cancels
                // this effect. Recheck the live gate before every scroll mutation.
                if (!currentCanFollow()) break
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

package dev.zakocoder.app.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

private val dispatchingClickFeedback = ThreadLocal<Boolean>()

/** Feedback belongs to an accepted action, never pointer movement or a cancelled press. */
internal fun View.withWorkbenchClickFeedback(action: () -> Unit) {
    if (dispatchingClickFeedback.get() == true) {
        action()
        return
    }
    dispatchingClickFeedback.set(true)
    try {
        // No override flags: Android keeps the user's system haptic preference authoritative.
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        action()
    } finally {
        // A button that synchronously closes a page must not also vibrate for its back action.
        dispatchingClickFeedback.remove()
    }
}

/** Preserve each control's existing ripple/press animation and add one short action receipt. */
@Composable
internal fun rememberWorkbenchClickFeedback(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    val latestClick by rememberUpdatedState(onClick)
    return remember(view) { { view.withWorkbenchClickFeedback { latestClick() } } }
}

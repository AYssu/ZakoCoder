package dev.zakocoder.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar

/** Tracks only the composer: other selectable messages and text fields keep their own focus. */
internal class ComposerFocusDismissState(private val dismiss: () -> Unit) {
    internal var focused = false
    internal var fieldCoordinates: LayoutCoordinates? = null
    internal var hostCoordinates: LayoutCoordinates? = null

    internal fun dismissOutsidePress(position: androidx.compose.ui.geometry.Offset) {
        if (!focused) return
        val host = hostCoordinates?.takeIf { it.isAttached } ?: return
        val field = fieldCoordinates?.takeIf { it.isAttached } ?: return
        if (!field.boundsInWindow().contains(host.localToWindow(position))) dismiss()
    }
}

@Composable
internal fun rememberComposerFocusDismissState(): ComposerFocusDismissState {
    val focus = LocalFocusManager.current
    val toolbar = LocalTextToolbar.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, toolbar, keyboard) {
        ComposerFocusDismissState {
            toolbar.hide()
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}

/** Observe without consuming: a child button still clicks, and drags/long presses keep their owner. */
internal fun Modifier.dismissComposerFocusOnOutsidePress(state: ComposerFocusDismissState): Modifier =
    onGloballyPositioned { state.hostCoordinates = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        event.changes.any { it.id != down.id && it.pressed }) moved = true
                    if (!pointer.pressed && !moved &&
                        pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        state.dismissOutsidePress(down.position)
                    }
                } while (pointer.pressed)
            }
        }

internal fun Modifier.composerFocusTarget(state: ComposerFocusDismissState?): Modifier =
    if (state == null) this else onGloballyPositioned { state.fieldCoordinates = it }
        .onFocusChanged { state.focused = it.hasFocus }

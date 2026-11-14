package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Retains real parent pixels and state without allowing a covered page to receive input. */
@Composable
internal fun WorkbenchRetainedPage(covered: Boolean, content: @Composable () -> Unit) {
    val interactive = LocalWorkbenchPageInteractive.current && !covered
    val blocking = if (interactive) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    Box(Modifier.fillMaxSize().then(blocking)) {
        CompositionLocalProvider(LocalWorkbenchPageInteractive provides interactive, content = content)
    }
}

/** Clear the outgoing page's focus before navigation, never after the new page requests focus. */
@Composable
internal fun rememberWorkbenchClearNavigationFocus(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) {
        { focus.clearFocus(); keyboard?.hide(); Unit }
    }
}

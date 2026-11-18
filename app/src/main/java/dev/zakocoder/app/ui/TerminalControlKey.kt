package dev.zakocoder.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shared feedback stays legible on black; helper keys never take keyboard focus from the PTY. */
@Composable
internal fun TerminalControlKey(modifier: Modifier, selected: Boolean = false, repeat: Boolean = false,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) Color(0xFF3A3A3A) else if (selected) Color(0xFF2C2C2C) else Color.Transparent,
        tween(if (pressed) 60 else 140), label = "terminal-key-press")
    val action by rememberUpdatedState(onClick)
    val click = rememberWorkbenchClickFeedback(onClick)
    val feedback by rememberUpdatedState(click)
    Box(modifier.height(48.dp).padding(2.dp).clip(RoundedCornerShape(6.dp)).background(background)
        .semantics { this.selected = selected }
        .focusProperties { canFocus = false }
        .clickable(interactionSource = interactions, indication = ripple(color = Color.White),
            role = Role.Button, onClick = click)
        .then(if (!repeat) Modifier else Modifier.pointerInput(interactions) {
            // Own the touch gesture so release cannot append an extra click after repeating.
            // The inner handler consumes touch first; outer clickable still provides
            // keyboard and accessibility activation without competing for this gesture.
            detectTapGestures(onPress = { position ->
                coroutineScope {
                    val press = PressInteraction.Press(position)
                    interactions.emit(press)
                    feedback()
                    val repeating = launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        while (isActive) {
                            action()
                            delay(60)
                        }
                    }
                    try {
                        val released = tryAwaitRelease()
                        repeating.cancelAndJoin()
                        interactions.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    } finally {
                        repeating.cancel()
                        interactions.tryEmit(PressInteraction.Cancel(press))
                    }
                }
            })
        }), contentAlignment = Alignment.Center) {
        content()
    }
}

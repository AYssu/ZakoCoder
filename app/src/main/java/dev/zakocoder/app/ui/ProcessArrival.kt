package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** One entrance per operation, independent of parameter and status updates. */
@Composable
internal fun ProcessArrival(animate: Boolean, born: Long? = null, content: @Composable () -> Unit) {
    val initial = remember { if (!animate || born == 0L) 1f else
        born?.let { ((System.nanoTime() - it) / 800_000_000f).coerceIn(0f, 1f) } ?: 0f }
    val alpha = remember { Animatable(initial) }
    LaunchedEffect(Unit) { if (initial < 1f) alpha.animateTo(1f, tween(((1f - initial) * 800).toInt())) }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

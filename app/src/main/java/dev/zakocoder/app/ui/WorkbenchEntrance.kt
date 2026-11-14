package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/** Reveal only after required local data and the destination's first layout are ready.
 * No minimum display time; Compose respects the system animation duration scale.
 */
@Composable
internal fun WorkbenchEntrance(destination: String, ready: Boolean, animate: Boolean = true,
    onRevealed: () -> Unit = {}, retainBackground: Boolean = true, content: @Composable () -> Unit) {
    val reveal = remember(destination) { Animatable(0f) }
    val showLoading = remember(destination) { !ready }
    var laidOut by remember(destination) { mutableStateOf(false) }
    var prepared by remember(destination) { mutableStateOf(false) }
    val composeDestination = ready || prepared
    val currentOnRevealed by rememberUpdatedState(onRevealed)
    LaunchedEffect(destination, composeDestination, laidOut, animate) {
        if (composeDestination && laidOut) {
            prepared = true
            // Let the destination apply its initial layout and scroll restoration behind the cover.
            withFrameNanos { }
            withFrameNanos { }
            if (animate) reveal.animateTo(1f, folmeSpring(damping = .95f, response = .35f))
            else reveal.snapTo(1f)
            currentOnRevealed()
        }
    }
    BackHandler(enabled = reveal.isRunning) { }
    // Overlays must reveal the still-live destination after entry settles; retaining an
    // opaque entrance layer would hide it again during their predictive back gesture.
    val background = if (retainBackground || reveal.value < 1f) Modifier.background(MaterialTheme.colorScheme.background) else Modifier
    Box(Modifier.fillMaxSize().then(background)) {
        if (composeDestination) Box(Modifier.fillMaxSize().onGloballyPositioned { laidOut = true }.graphicsLayer {
            alpha = reveal.value
            val scale = 0.985f + 0.015f * reveal.value
            scaleX = scale; scaleY = scale
            translationY = (1f - reveal.value) * 12.dp.toPx()
        }) { content() }
        if (reveal.value < 1f) Surface(Modifier.matchParentSize().graphicsLayer { alpha = 1f - reveal.value }
            .pointerInput(destination) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }, color = MaterialTheme.colorScheme.background) {
            // Keep the same indicator alive while the destination first composes/layouts
            // and while the cover fades. Data readiness alone is not visual readiness.
            if (showLoading) Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(stringResource(dev.zakocoder.app.R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                WorkbenchEntryProgress()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(dev.zakocoder.app.R.string.setup_preparing_workspace),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Platform indeterminate drawable can keep drawing independently of Compose's reveal work. */
@Composable
private fun WorkbenchEntryProgress() {
    val tint = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = Modifier.size(24.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        factory = { context -> android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            indeterminateTintList = android.content.res.ColorStateList.valueOf(tint)
        } },
        update = { it.indeterminateTintList = android.content.res.ColorStateList.valueOf(tint) },
    )
}

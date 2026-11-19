package dev.zakocoder.app.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zakocoder.app.R

/** Only completed Back actions arm the guard; elapsed time is independent of wall-clock changes. */
internal class HomeBackConfirmation {
    private var lastBack: Long? = null

    fun accept(now: Long): Boolean {
        val previous = lastBack
        val confirmed = previous != null && now - previous in 0..2_000L
        lastBack = if (confirmed) null else now
        return confirmed
    }

    fun reset() { lastBack = null }
}

/** Register before child handlers: drawers, dialogs and the IME keep their existing Back behavior. */
@Composable
internal fun WorkbenchHomeBack(enabled: Boolean, onLeave: () -> Unit) {
    val confirmation = remember { HomeBackConfirmation() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val view = LocalView.current
    val hint = remember(context) { Toast.makeText(context, R.string.home_back_confirm, Toast.LENGTH_SHORT) }
    DisposableEffect(lifecycle, enabled, hint) {
        confirmation.reset()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { confirmation.reset(); hint.cancel() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            confirmation.reset()
            hint.cancel()
        }
    }
    BackHandler(enabled = enabled) {
        view.withWorkbenchClickFeedback {
            if (confirmation.accept(SystemClock.elapsedRealtime())) {
                hint.cancel()
                onLeave()
            } else hint.show()
        }
    }
}

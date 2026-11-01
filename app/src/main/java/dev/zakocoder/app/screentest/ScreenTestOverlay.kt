package dev.zakocoder.app.screentest

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The service owns task lifetime. This optional window only presents the current approved lease. */
internal class ScreenTestOverlay(private val context: Context, private val visible: (Boolean) -> Unit,
    private val returnToApp: (String, String) -> Unit, private val stop: (String, String) -> Unit,
    private val failed: (String) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var window: ComposeView? = null
    private var owner: WindowOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var renderedLease: String? = null
    private var conversationLease: String? = null
    private var expanded by mutableStateOf(false)
    private var presentation by mutableStateOf(ScreenOverlayPresentation())
    private var scroll = ScrollState(0)
    private var followLatest by mutableStateOf(true)
    private val actionBounds = mutableMapOf<Int, ComposeRect>()
    private var removalNeedsFrame = false
    private var removalFailed = false
    private var x = dp(12)
    private var y = 160
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun update(show: Boolean, project: String?, lease: String?, packageName: String = "", status: String = "",
        conversation: String = "", progress: ScreenOverlayProgress = ScreenOverlayProgress()) {
        if (!show || project == null || lease == null || !Settings.canDrawOverlays(context)) { remove(); return }
        if (conversationLease != lease) {
            expanded = false
            scroll = ScrollState(0)
            followLatest = true
            conversationLease = lease
        }
        if (window != null && renderedLease != lease) remove()
        presentation = ScreenOverlayPresentation(packageName, conversation, progress,
            status.takeUnless { it.trimStart().startsWith("{") }.orEmpty())
        if (window != null) return
        val lifecycle = WindowOwner()
        val content = ComposeView(context)
        content.setViewTreeLifecycleOwner(lifecycle)
        content.setViewTreeSavedStateRegistryOwner(lifecycle)
        content.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        content.setContent {
            DebugOverlayCard(presentation, expanded, scroll, followLatest, onFollowLatest = { followLatest = it },
                dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
                onExpand = { expanded = !expanded; actionBounds.clear(); resize() },
                onReturn = { returnToApp(project, lease) },
                onStop = { remove(); stop(project, lease) },
                onDrag = ::drag,
                onActionBounds = { id, bounds -> actionBounds[id] = bounds })
        }
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@ScreenTestOverlay.x; y = this@ScreenTestOverlay.y }
        size(layout)
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
        content.setOnApplyWindowInsetsListener { view, insets ->
            view.post { if (window === view) { resize(); clamp() } }
            insets
        }
        try {
            manager.addView(content, layout)
            owner = lifecycle; window = content; params = layout; renderedLease = lease
            lifecycle.resume()
            visible(true)
            content.post { clamp() }
        } catch (_: SecurityException) { lifecycle.destroy(); failure("window_permission") }
        catch (_: WindowManager.BadTokenException) { lifecycle.destroy(); failure("window_token") }
    }

    private fun size(layout: WindowManager.LayoutParams) {
        val safe = safeBounds()
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        layout.width = (if (expanded) dp(400) else dp(284)).coerceAtMost((safe.width() - dp(24)).coerceAtLeast(dp(48)))
        layout.height = if (expanded) {
            if (safe.width() > safe.height()) (safe.height() - dp(16)).coerceAtLeast(1)
            else dp(560).coerceAtMost((safe.height() * .72f).toInt())
        }
            else (dp(76) * fontScale.coerceAtMost(2f)).toInt().coerceAtMost(safe.height())
    }
    private fun resize() {
        val current = window ?: return
        val layout = params ?: return
        val beforeWidth = layout.width; val beforeHeight = layout.height
        size(layout)
        if (beforeWidth == layout.width && beforeHeight == layout.height) return
        try { manager.updateViewLayout(current, layout); current.post { clamp() } }
        catch (_: SecurityException) { failure("window_permission") }
        catch (_: IllegalArgumentException) { failure("window_layout") }
    }
    private fun drag(dx: Float, dy: Float) {
        val current = window ?: return
        val layout = params ?: return
        val location = IntArray(2); current.getLocationOnScreen(location)
        val safe = safeBounds()
        val desiredX = (location[0] + dx.toInt()).coerceIn(safe.left, (safe.right - current.width).coerceAtLeast(safe.left))
        val desiredY = (location[1] + dy.toInt()).coerceIn(safe.top, (safe.bottom - current.height).coerceAtLeast(safe.top))
        layout.x += desiredX - location[0]; layout.y += desiredY - location[1]
        position()
    }
    private fun clamp() {
        val current = window ?: return
        val layout = params ?: return
        if (current.width == 0 || current.height == 0) return
        val location = IntArray(2); current.getLocationOnScreen(location)
        val safe = safeBounds()
        val dx = location[0].coerceIn(safe.left, (safe.right - current.width).coerceAtLeast(safe.left)) - location[0]
        val dy = location[1].coerceIn(safe.top, (safe.bottom - current.height).coerceAtLeast(safe.top)) - location[1]
        if (dx == 0 && dy == 0) return
        layout.x += dx; layout.y += dy; position()
    }
    private fun position() {
        val current = window ?: return
        val layout = params ?: return
        x = layout.x; y = layout.y
        try { manager.updateViewLayout(current, layout) }
        catch (_: SecurityException) { failure("window_permission") }
        catch (_: IllegalArgumentException) { failure("window_layout") }
    }

    private fun safeBounds(): Rect {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val maximum = manager.maximumWindowMetrics
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val sources = listOfNotNull(maximum.windowInsets, manager.currentWindowMetrics.windowInsets, window?.rootWindowInsets)
                .map { it.getInsetsIgnoringVisibility(types) }
            return Rect(sources.maxOf { it.left }, sources.maxOf { it.top },
                maximum.bounds.width() - sources.maxOf { it.right }, maximum.bounds.height() - sources.maxOf { it.bottom })
        }
        val insets = window?.rootWindowInsets
        return Rect(insets?.stableInsetLeft ?: 0, insets?.stableInsetTop ?: 0,
            context.resources.displayMetrics.widthPixels - (insets?.stableInsetRight ?: 0),
            context.resources.displayMetrics.heightPixels - (insets?.stableInsetBottom ?: 0))
    }
    /** Geometry for isolated-device checks only; no conversation or target contents. */
    internal fun actionBoundsSnapshot(): Map<Int, Rect> {
        val view = window ?: return emptyMap()
        if (!expanded) return emptyMap()
        val location = IntArray(2); view.getLocationOnScreen(location)
        return actionBounds.mapValues { (_, r) -> Rect(location[0] + r.left.toInt(), location[1] + r.top.toInt(),
            location[0] + r.right.toInt(), location[1] + r.bottom.toInt()) }
    }
    internal fun isReadingScrollActive(): Boolean = scroll.isScrollInProgress
    internal fun maxScroll(): Int = scroll.maxValue
    internal fun readingSnapshot(): Triple<Boolean, Int, Boolean> = Triple(expanded, scroll.value, followLatest)
    internal fun geometryDiagnostics(): String {
        val view = window ?: return "window=absent"
        val location = IntArray(2); view.getLocationOnScreen(location)
        return "safe=${safeBounds()}; window=${Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)}; expanded=$expanded; scroll=${scroll.value}"
    }
    private fun failure(code: String) { remove(); failed(code) }
    fun remove() {
        val current = window
        window = null; params = null; renderedLease = null; actionBounds.clear()
        if (current != null) {
            removalNeedsFrame = true; removalFailed = false
            try { manager.removeViewImmediate(current) }
            catch (_: IllegalArgumentException) { }
            catch (_: SecurityException) { window = current; removalFailed = true; failed("window_remove") }
            if (!removalFailed) { owner?.destroy(); owner = null; current.disposeComposition() }
        }
        visible(false)
    }
    /** Hide before observations/input so this window cannot contaminate evidence or intercept actions. */
    suspend fun hideForAction() = withContext(Dispatchers.Main.immediate) {
        if (window == null && !removalNeedsFrame) return@withContext
        remove()
        if (removalFailed) throw ScreenTestException(context.getString(R.string.screen_overlay_remove_failed))
        val completed = withTimeoutOrNull(5000) {
            repeat(2) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val choreographer = android.view.Choreographer.getInstance()
                    val callback = android.view.Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
                    choreographer.postFrameCallback(callback)
                    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
                }
            }; true
        }
        if (completed != true) throw ScreenTestException(context.getString(R.string.screen_overlay_frame_timeout))
        removalNeedsFrame = false
    }

    private class WindowOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry
        init { saved.performAttach(); saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

package dev.zakocoder.app.screentest

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Application-owned optional window. The existing development service owns task lifetime. */
internal class ScreenTestOverlay(private val context: Context, private val visible: (Boolean) -> Unit,
    private val returnToApp: (String, String) -> Unit, private val stop: (String, String) -> Unit,
    private val failed: (String) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var window: View? = null
    private var renderedLease: String? = null
    private var removalNeedsFrame = false
    private var removalFailed = false
    private var expanded = false
    private var x = 0
    private var y = 160
    private fun dark() = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun update(show: Boolean, project: String?, lease: String?) {
        if (!show || project == null || lease == null || !Settings.canDrawOverlays(context)) { remove(); return }
        if (window != null && renderedLease != lease) remove()
        if (window != null) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setColor(if (dark()) Color.rgb(22, 29, 38) else Color.WHITE); cornerRadius = dp(12).toFloat() }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = this@ScreenTestOverlay.x; y = this@ScreenTestOverlay.y }
        val handle = DragButton(context).apply {
            text = context.getString(R.string.screen_overlay_handle)
            contentDescription = context.getString(R.string.screen_overlay_expand)
            textSize = 13f
            setTextColor(if (dark()) Color.rgb(229, 231, 235) else Color.rgb(17, 24, 39))
            minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48); minimumHeight = dp(48)
            background = null
        }
        container.addView(handle)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = if (expanded) View.VISIBLE else View.GONE }
        fun button(label: Int, action: () -> Unit) = Button(context).apply {
            text = context.getString(label)
            textSize = 13f
            setTextColor(if (label == R.string.screen_overlay_stop) Color.rgb(220, 75, 75)
                else if (dark()) Color.rgb(229, 231, 235) else Color.rgb(17, 24, 39))
            background = null
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }
        // Keep the user-visible entry attached while requesting our own activity.
        // The controller closes it after dispatching navigation, not before that request.
        actions.addView(button(R.string.screen_overlay_return) { returnToApp(project, lease) })
        actions.addView(button(R.string.screen_overlay_stop) { remove(); stop(project, lease) })
        container.addView(actions)
        fun clamp() {
            if (window !== container) return
            val inset = android.graphics.Rect()
            val width: Int
            val height: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val metrics = manager.currentWindowMetrics
                val safe = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                inset.set(safe.left, safe.top, safe.right, safe.bottom)
                width = metrics.bounds.width()
                height = metrics.bounds.height()
            } else {
                width = context.resources.displayMetrics.widthPixels
                height = context.resources.displayMetrics.heightPixels
                // Public insets supplied by the attached overlay window, never internal resources.
                val safe = container.rootWindowInsets
                inset.set(safe?.stableInsetLeft ?: 0, safe?.stableInsetTop ?: 0,
                    safe?.stableInsetRight ?: 0, safe?.stableInsetBottom ?: 0)
            }
            params.x = params.x.coerceIn(inset.left, (width - inset.right - container.width).coerceAtLeast(inset.left))
            params.y = params.y.coerceIn(inset.top, (height - inset.bottom - container.height).coerceAtLeast(inset.top))
            if (x == params.x && y == params.y) return
            x = params.x; y = params.y
            try { manager.updateViewLayout(container, params) }
            catch (_: SecurityException) { failure("window_permission") }
            catch (_: IllegalArgumentException) { failure("window_layout") }
        }
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
        handle.setOnClickListener {
            expanded = !expanded
            actions.visibility = if (expanded) View.VISIBLE else View.GONE
            handle.contentDescription = context.getString(if (expanded) R.string.screen_overlay_collapse else R.string.screen_overlay_expand)
            container.post { clamp() }
        }
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var dragged = false
        handle.clickOnRelease = { !dragged }
        handle.onDragTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) dragged = true
                    if (dragged && window === container) {
                        params.x = initialX + dx.toInt(); params.y = initialY + dy.toInt()
                        clamp()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { manager.addView(container, params); window = container; renderedLease = lease; visible(true); container.post { clamp() } }
        catch (_: SecurityException) { failure("window_permission") }
        catch (_: WindowManager.BadTokenException) { failure("window_token") }
    }

    /** Drag handling retains the standard click accessibility action and event dispatch. */
    private class DragButton(context: Context) : Button(context) {
        var onDragTouch: ((MotionEvent) -> Boolean)? = null
        var clickOnRelease: () -> Boolean = { false }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val handler = onDragTouch ?: return super.onTouchEvent(event)
            val handled = handler(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && clickOnRelease()) performClick()
            return handled
        }
        override fun performClick(): Boolean = super.performClick()
    }

    private fun failure(code: String) { remove(); failed(code) }

    fun remove() {
        val current = window
        window = null
        renderedLease = null
        if (current != null) {
            removalNeedsFrame = true
            removalFailed = false
            try { manager.removeViewImmediate(current) }
            catch (_: IllegalArgumentException) { }
            catch (_: SecurityException) { window = current; removalFailed = true; failed("window_remove") }
        }
        visible(false)
    }

    /** Removal completes before the next compositor frame; actions never use overlay coordinates. */
    suspend fun hideForAction() = withContext(Dispatchers.Main.immediate) {
        if (window == null && !removalNeedsFrame) return@withContext
        remove()
        if (removalFailed) throw ScreenTestException(context.getString(R.string.screen_overlay_remove_failed))
        val completed = withTimeoutOrNull(1000) {
            repeat(2) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = android.view.Choreographer.getInstance()
                val callback = android.view.Choreographer.FrameCallback {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }
        true
        }
        if (completed != true) throw ScreenTestException(context.getString(R.string.screen_overlay_frame_timeout))
        removalNeedsFrame = false
    }
}

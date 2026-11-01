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
import android.widget.TextView
import android.widget.ScrollView
import android.text.TextUtils
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
    private var conversationLease: String? = null
    private var targetLabel: TextView? = null
    private var statusLabel: TextView? = null
    private var conversationLabel: TextView? = null
    private var x = 0
    private var y = 160
    private fun dark() = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun displayStatus(status: String) = if (status.trimStart().startsWith("{")) context.getString(R.string.screen_test_ready) else boundedOverlayText(status)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun update(show: Boolean, project: String?, lease: String?, packageName: String = "", status: String = "", conversation: String = "") {
        if (!show || project == null || lease == null || !Settings.canDrawOverlays(context)) { remove(); return }
        if (conversationLease != lease) { expanded = false; conversationLease = lease }
        if (window != null && renderedLease != lease) remove()
        if (window != null) {
            targetLabel?.text = packageName
            statusLabel?.text = displayStatus(status)
            conversationLabel?.text = conversation.ifBlank { context.getString(R.string.screen_overlay_conversation_empty) }
            return
        }
        val container = BoundedOverlayLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(if (dark()) Color.rgb(22, 29, 38) else Color.WHITE)
                setStroke(dp(1).coerceAtLeast(1), if (dark()) Color.rgb(35, 43, 54) else Color.rgb(226, 232, 240))
                cornerRadius = dp(12).toFloat()
            }
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
        val actions = FixedActionsLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(256).coerceAtMost((safeBounds().width() - dp(24)).coerceAtLeast(dp(48))), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun label(value: String, lines: Int, size: Float) = TextView(context).apply {
            text = value
            textSize = size
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (dark()) Color.rgb(229, 231, 235) else Color.rgb(17, 24, 39))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        targetLabel = label(packageName, 2, 12f).also(body::addView)
        statusLabel = label(displayStatus(status), 3, 12f).also(body::addView)
        conversationLabel = label(conversation.ifBlank { context.getString(R.string.screen_overlay_conversation_empty) }, 6, 13f).also(body::addView)
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(body)
        }
        // Explicit measurement reserves the action row before assigning space to the body.
        actions.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun button(label: Int, action: () -> Unit) = Button(context).apply {
            tag = label
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
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        footer.addView(button(R.string.screen_overlay_return) { returnToApp(project, lease) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        footer.addView(button(R.string.screen_overlay_stop) { remove(); stop(project, lease) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        actions.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        container.addView(actions)
        fun applyPosition() {
            x = params.x; y = params.y
            try { manager.updateViewLayout(container, params) }
            catch (_: SecurityException) { failure("window_permission") }
            catch (_: IllegalArgumentException) { failure("window_layout") }
        }
        fun clamp() {
            if (window !== container || container.width == 0 || container.height == 0) return
            val safe = safeBounds(container)
            val location = IntArray(2)
            container.getLocationOnScreen(location)
            val desiredX = location[0].coerceIn(safe.left, (safe.right - container.width).coerceAtLeast(safe.left))
            val desiredY = location[1].coerceIn(safe.top, (safe.bottom - container.height).coerceAtLeast(safe.top))
            // LayoutParams offsets are relative to the window manager's fitted frame, not
            // necessarily the physical display origin. Correct the actual on-screen delta.
            val deltaX = desiredX - location[0]
            val deltaY = desiredY - location[1]
            if (deltaX == 0 && deltaY == 0) return
            params.x += deltaX
            params.y += deltaY
            applyPosition()
        }
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clamp() }
        container.setOnApplyWindowInsetsListener { view, insets ->
            // An attached overlay can receive larger current insets than maximum metrics.
            // Remeasure the weighted body before clamping its physical screen position.
            view.post { if (window === container) { view.requestLayout(); clamp() } }
            insets
        }
        handle.setOnClickListener {
            expanded = !expanded
            actions.visibility = if (expanded) View.VISIBLE else View.GONE
            handle.contentDescription = context.getString(if (expanded) R.string.screen_overlay_collapse else R.string.screen_overlay_expand)
            container.post { clamp() }
        }
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var dragged = false
        var initialScreenX = 0; var initialScreenY = 0
        handle.clickOnRelease = { !dragged }
        handle.onDragTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; dragged = false
                    val location = IntArray(2)
                    container.getLocationOnScreen(location)
                    initialScreenX = location[0]; initialScreenY = location[1]
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(8)) dragged = true
                    if (dragged && window === container) {
                        val safe = safeBounds(container)
                        val screenX = (initialScreenX + dx.toInt()).coerceIn(safe.left, (safe.right - container.width).coerceAtLeast(safe.left))
                        val screenY = (initialScreenY + dy.toInt()).coerceIn(safe.top, (safe.bottom - container.height).coerceAtLeast(safe.top))
                        params.x = initialX + screenX - initialScreenX
                        params.y = initialY + screenY - initialScreenY
                        applyPosition()
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

    /** Public system insets keep both measurement and positioning inside the same usable area. */
    private fun safeBounds(view: View? = window): android.graphics.Rect {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val maximum = manager.maximumWindowMetrics
            val current = manager.currentWindowMetrics
            val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            val sources = listOfNotNull(maximum.windowInsets, current.windowInsets, view?.rootWindowInsets)
                .map { it.getInsetsIgnoringVisibility(types) }
            // Maximum metrics supplies physical display bounds, but may omit a current
            // navigation/status bar. Retain the strictest inset from every public source.
            return android.graphics.Rect(sources.maxOf { it.left }, sources.maxOf { it.top },
                maximum.bounds.width() - sources.maxOf { it.right },
                maximum.bounds.height() - sources.maxOf { it.bottom })
        }
        val insets = view?.rootWindowInsets
        return android.graphics.Rect(insets?.stableInsetLeft ?: 0, insets?.stableInsetTop ?: 0,
            context.resources.displayMetrics.widthPixels - (insets?.stableInsetRight ?: 0),
            context.resources.displayMetrics.heightPixels - (insets?.stableInsetBottom ?: 0))
    }

    /** Read on Main. Physical screen bounds only; no target or conversation data. */
    internal fun actionBoundsSnapshot(): Map<Int, android.graphics.Rect> {
        val current = window ?: return emptyMap()
        return listOf(R.string.screen_overlay_return, R.string.screen_overlay_stop).mapNotNull { action ->
            val view = current.findViewWithTag<View>(action) ?: return@mapNotNull null
            if (!view.isShown) return@mapNotNull null
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            action to android.graphics.Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        }.toMap()
    }

    /** Geometry only, for isolated-device diagnostics; never contains target or conversation text. */
    internal fun geometryDiagnostics(): String {
        val current = window ?: return "window=absent"
        val location = IntArray(2)
        current.getLocationOnScreen(location)
        val bounds = android.graphics.Rect(location[0], location[1], location[0] + current.width, location[1] + current.height)
        val metrics = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            "maximum=${manager.maximumWindowMetrics.bounds}/${manager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types)}; " +
                "current=${manager.currentWindowMetrics.bounds}/${manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types)}; " +
                "rootInsets=${current.rootWindowInsets?.getInsetsIgnoringVisibility(types)}"
        } else "stableInsets=${current.rootWindowInsets}"
        val children = mutableListOf<String>()
        fun collect(view: View, depth: Int) {
            if (depth > 3 || children.size >= 12) return
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            children += "${view.javaClass.simpleName}:[${position[0]},${position[1]},${position[0] + view.width},${position[1] + view.height}]" +
                " measured=${view.measuredWidth}x${view.measuredHeight}"
            if (view is android.view.ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it), depth + 1) }
        }
        collect(current, 0)
        return "safe=${safeBounds(current)}; container=$bounds; measured=${current.measuredWidth}x${current.measuredHeight}; $metrics; children=$children"
    }

    /** Measure header first; nested WRAP_CONTENT/weight measurement must not overflow the window. */
    private inner class BoundedOverlayLayout(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val safe = safeBounds(this)
            fun limit(spec: Int, max: Int) = if (View.MeasureSpec.getMode(spec) == View.MeasureSpec.UNSPECIFIED) max
                else View.MeasureSpec.getSize(spec).coerceAtMost(max)
            val maxWidth = limit(widthMeasureSpec, safe.width()).coerceAtLeast(0)
            val maxHeight = limit(heightMeasureSpec, safe.height()).coerceAtLeast(0)
            val innerWidth = (maxWidth - paddingLeft - paddingRight).coerceAtLeast(0)
            val innerHeight = (maxHeight - paddingTop - paddingBottom).coerceAtLeast(0)
            val header = getChildAt(0)
            val actions = getChildAt(1)
            val showActions = actions.visibility != View.GONE
            val bodyWidth = if (showActions) actions.layoutParams.width.coerceIn(0, innerWidth) else innerWidth
            header.measure(View.MeasureSpec.makeMeasureSpec(bodyWidth,
                if (showActions) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(innerHeight, View.MeasureSpec.AT_MOST))
            if (showActions) actions.measure(View.MeasureSpec.makeMeasureSpec(bodyWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((innerHeight - header.measuredHeight).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            val contentWidth = if (showActions) bodyWidth else header.measuredWidth
            val contentHeight = header.measuredHeight + if (showActions) actions.measuredHeight else 0
            setMeasuredDimension((contentWidth + paddingLeft + paddingRight).coerceAtMost(maxWidth),
                (contentHeight + paddingTop + paddingBottom).coerceAtMost(maxHeight))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val header = getChildAt(0)
            header.layout(paddingLeft, paddingTop, paddingLeft + header.measuredWidth, paddingTop + header.measuredHeight)
            val actions = getChildAt(1)
            if (actions.visibility != View.GONE) actions.layout(paddingLeft, paddingTop + header.measuredHeight,
                paddingLeft + actions.measuredWidth, paddingTop + header.measuredHeight + actions.measuredHeight)
        }
    }

    /** The footer owns its space; a scrollable body receives only the remaining bounded height. */
    private inner class FixedActionsLayout(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)
            val footer = getChildAt(1)
            val footerHeight = dp(48).coerceAtMost(availableHeight)
            footer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(footerHeight, View.MeasureSpec.EXACTLY))
            val body = getChildAt(0)
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((availableHeight - footer.measuredHeight).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            setMeasuredDimension(width, (body.measuredHeight + footer.measuredHeight).coerceAtMost(availableHeight))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val footer = getChildAt(1)
            val footerTop = (height - footer.measuredHeight).coerceAtLeast(0)
            getChildAt(0).layout(0, 0, width, footerTop)
            footer.layout(0, footerTop, width, height)
        }
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
        targetLabel = null; statusLabel = null; conversationLabel = null
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
        val completed = withTimeoutOrNull(5000) {
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

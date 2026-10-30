package dev.zakocoder.app.screentest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.FileProvider
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No unselected application is inspected, including when the service is enabled but idle. */
class ScreenTestAccessibilityService : AccessibilityService(), ScreenTestBackend {
    @Volatile private var target: String? = null
    private var snapshot: Snapshot? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()

    override fun onServiceConnected() {
        instance = this
        bindTarget(null)
        notifyConnectionChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stop() }
    override fun onDestroy() {
        stop()
        if (instance === this) instance = null
        notifyConnectionChanged()
        super.onDestroy()
    }

    private fun bindTarget(packageName: String?) {
        target = packageName
        snapshot = null
        generation.incrementAndGet()
        serviceInfo = serviceInfo.apply {
            packageNames = arrayOf(packageName ?: "dev.zakocoder.screentest.unbound")
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun stop() {
        target = null
        val stoppedAt = generation.incrementAndGet()
        if (android.os.Looper.myLooper() == mainLooper) bindTarget(null)
        else android.os.Handler(mainLooper).post { if (generation.get() == stoppedAt) bindTarget(null) }
    }

    override suspend fun execute(packageName: String, args: JSONObject): ScreenTestResult = withContext(Dispatchers.Main.immediate) {
        require(packageName != applicationContext.packageName)
        if (target != packageName) bindTarget(packageName)
        val root = foreground(packageName)
        when (args.getString("action")) {
            "observe" -> observe(root)
            "assert_text" -> {
                val expected = args.getString("text")
                require(expected.isNotBlank() && expected.length <= 1000)
                val collected = collect(root)
                check(collected.nodes.any { !it.sensitive && (it.text.contains(expected) || it.description.contains(expected)) }) {
                    getString(R.string.screen_test_assert_failed)
                }
                ScreenTestResult(JSONObject().put("assertion", "passed").put("source", "visible_accessibility_nodes").toString())
            }
            "screenshot" -> screenshot(packageName, root)
            "back" -> {
                snapshot = null
                check(performGlobalAction(GLOBAL_ACTION_BACK)) { getString(R.string.screen_test_action_failed) }
                ScreenTestResult("{\"requested\":\"back\",\"verified\":false,\"next\":\"observe\"}")
            }
            "swipe" -> {
                val node = observedNode(root, args)
                val bounds = Rect().also(node::getBoundsInScreen)
                val targetWindow = root.window ?: error(getString(R.string.screen_test_wrong_foreground))
                check(!covered(targetWindow, bounds)) { getString(R.string.screen_test_wrong_foreground) }
                val direction = args.optString("direction", "up")
                require(direction in setOf("up", "down", "left", "right"))
                val x = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val dx = bounds.width() * .28f
                val dy = bounds.height() * .28f
                val (sx, sy, ex, ey) = when (direction) {
                    "up" -> listOf(x, y + dy, x, y - dy)
                    "down" -> listOf(x, y - dy, x, y + dy)
                    "left" -> listOf(x + dx, y, x - dx, y)
                    else -> listOf(x - dx, y, x + dx, y)
                }
                snapshot = null
                gesture(sx, sy, ex, ey, 350)
                ScreenTestResult("{\"requested\":\"swipe\",\"verified\":false,\"next\":\"observe\"}")
            }
            "click", "long_click", "input_text" -> {
                val node = observedNode(root, args)
                val action = args.getString("action")
                snapshot = null
                val accepted = when (action) {
                    "input_text" -> {
                        require(node.isEditable)
                        val text = args.getString("text")
                        require(text.length <= 2000 && text.none { it == '\u0000' })
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                    }
                    "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    else -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                check(accepted) { getString(R.string.screen_test_action_failed) }
                ScreenTestResult(JSONObject().put("accepted", action).put("verified", false).put("next", "observe").toString())
            }
            else -> error("Unknown screen-test action")
        }
    }

    private fun observedNode(root: AccessibilityNodeInfo, args: JSONObject): AccessibilityNodeInfo {
        val observed = snapshot
        check(observed != null && observed.token == args.optString("snapshot") &&
            System.currentTimeMillis() - observed.createdAt <= 60_000) { getString(R.string.screen_test_stale_snapshot) }
        val remembered = observed.nodes.firstOrNull { it.id == args.optString("node") }
            ?: error(getString(R.string.screen_test_stale_snapshot))
        var node = root
        for (index in remembered.path) node = node.getChild(index) ?: error(getString(R.string.screen_test_stale_snapshot))
        check(fingerprint(node) == remembered.fingerprint && node.isVisibleToUser && node.isEnabled) {
            getString(R.string.screen_test_stale_snapshot)
        }
        check(!remembered.sensitive && !node.isPassword) { getString(R.string.screen_test_sensitive) }
        return node
    }

    private fun foreground(packageName: String): AccessibilityNodeInfo {
        check(target == packageName) { getString(R.string.screen_test_stopped) }
        val root = rootInActiveWindow ?: error(getString(R.string.screen_test_wrong_foreground))
        check(root.packageName?.toString() == packageName && root.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            root.window?.isActive == true) { getString(R.string.screen_test_wrong_foreground) }
        return root
    }

    private fun observe(root: AccessibilityNodeInfo): ScreenTestResult {
        val collected = collect(root)
        val token = UUID.randomUUID().toString()
        val nodeJson = collected.nodes.map { node ->
            JSONObject().put("id", node.id).put("text", if (node.sensitive) "[redacted]" else node.text)
                .put("description", if (node.sensitive) "[redacted]" else node.description)
                .put("class", node.className).put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                .put("clickable", node.clickable).put("longClickable", node.longClickable)
                .put("editable", node.editable).put("enabled", node.enabled).put("sensitive", node.sensitive)
        }
        val bounded = boundedScreenObservation(target, token, nodeJson, collected.truncated)
        snapshot = Snapshot(token, System.currentTimeMillis(), collected.nodes.take(bounded.exposedCount))
        return ScreenTestResult(bounded.output)
    }

    private fun collect(root: AccessibilityNodeInfo): Collected {
        val nodes = mutableListOf<Node>()
        var visited = 0
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, path: List<Int>, sensitiveParent: Boolean) {
            if (++visited > 300 || path.size > 20) { truncated = true; return }
            val sensitive = sensitiveParent || node.isPassword
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser) nodes += Node("n${nodes.size + 1}", path, fingerprint(node), sensitive,
                if (sensitive) "" else node.text?.toString().orEmpty().take(160),
                if (sensitive) "" else node.contentDescription?.toString().orEmpty().take(160),
                node.className?.toString().orEmpty().take(120), bounds, node.isClickable,
                node.isLongClickable, node.isEditable, node.isEnabled)
            for (index in 0 until node.childCount.coerceAtMost(300)) {
                if (visited >= 300) { truncated = true; break }
                node.getChild(index)?.let { visit(it, path + index, sensitive) }
            }
        }
        visit(root, emptyList(), false)
        return Collected(nodes, truncated)
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return listOf(node.packageName, node.className, node.viewIdResourceName, bounds.toShortString(),
            node.isPassword, node.isEditable, if (node.isPassword) "" else node.text?.toString(),
            if (node.isPassword) "" else node.contentDescription?.toString()).joinToString("\u001f")
    }

    private suspend fun gesture(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long) {
        val epoch = generation.get()
        suspendCancellableCoroutine<Unit> { continuation ->
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!continuation.isActive) return
                    if (generation.get() == epoch) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException(getString(R.string.screen_test_stopped)))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(getString(R.string.screen_test_action_failed)))
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException(getString(R.string.screen_test_action_failed)))
        }
    }

    private suspend fun screenshot(packageName: String, root: AccessibilityNodeInfo): ScreenTestResult {
        if (Build.VERSION.SDK_INT < 30) error(getString(R.string.screen_test_screenshot_unsupported))
        val collected = collect(root)
        check(!collected.truncated && collected.nodes.none { it.sensitive }) { getString(R.string.screen_test_sensitive) }
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val targetWindow = root.window ?: error(getString(R.string.screen_test_wrong_foreground))
        val windowCapture = Build.VERSION.SDK_INT >= 34
        val bounds = if (windowCapture) rootBounds else captureBounds(targetWindow, rootBounds)
        val epoch = generation.get()
        val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    val copied = try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                        try { wrapped?.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped?.recycle() }
                    } catch (_: Exception) { null } finally { hardware.close() }
                    if (!continuation.isActive) { copied?.recycle(); return }
                    if (copied == null) continuation.resumeWithException(IllegalStateException(getString(R.string.screen_test_action_failed)))
                    else continuation.resume(copied)
                }
                override fun onFailure(errorCode: Int) {
                    val message = when {
                        Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                            R.string.screen_test_secure_screenshot
                        errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> R.string.screen_test_screenshot_interval
                        else -> R.string.screen_test_screenshot_failed
                    }
                    if (continuation.isActive) continuation.resumeWithException(ScreenTestException(getString(message)))
                }
            }
            if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(targetWindow.id, mainExecutor, callback)
            else takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
        try {
            check(epoch == generation.get()) { getString(R.string.screen_test_stopped) }
            val current = foreground(packageName)
            val now = collect(current)
            check(!now.truncated && now.nodes.none { it.sensitive }) { getString(R.string.screen_test_sensitive) }
            val currentBounds = Rect().also(current::getBoundsInScreen)
            check(currentBounds == rootBounds && current.windowId == targetWindow.id) {
                getString(R.string.screen_test_stale_snapshot)
            }
            if (!windowCapture) check(captureBounds(current.window ?: error(getString(R.string.screen_test_wrong_foreground)), currentBounds) == bounds) {
                getString(R.string.screen_test_screenshot_overlay)
            }
            val cropped = if (windowCapture) bitmap else {
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height && !bounds.isEmpty)
                Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
            }
            try {
                return withContext(Dispatchers.IO) {
                    val directory = File(filesDir, "screen-test/evidence").apply { check(mkdirs() || isDirectory) }
                    val existing = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "png" }.sortedBy { it.lastModified() }
                    var total = existing.sumOf { it.length() }
                    existing.forEachIndexed { index, file ->
                        if (index < existing.size - 39 || total > 48L * 1024 * 1024) {
                            val bytes = file.length()
                            if (file.delete()) total -= bytes
                        }
                    }
                    val file = File(directory, "${UUID.randomUUID()}.png")
                    try {
                        file.outputStream().use { check(cropped.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        check(file.length() <= 16L * 1024 * 1024)
                        ScreenTestResult(JSONObject().put("screenshot", file.name).put("package", packageName)
                            .put("source", if (windowCapture) "target_window" else "target_window_crop").toString(),
                            FileProvider.getUriForFile(this@ScreenTestAccessibilityService, "${applicationContext.packageName}.apk-files", file))
                    } catch (error: Exception) { file.delete(); throw error }
                }
            } finally { if (cropped !== bitmap) cropped.recycle() }
        } finally { bitmap.recycle() }
    }

    /** Only full-edge system bars / IME may reduce the crop. Interior overlays are never ignored. */
    private fun captureBounds(targetWindow: AccessibilityWindowInfo, requested: Rect): Rect {
        val safe = Rect(requested)
        val overlays = windows.filter { it.id != targetWindow.id && it.layer > targetWindow.layer }.map { other ->
            other.type to Rect().also(other::getBoundsInScreen)
        }
        // Trim outer bars first, then an adjacent IME. Window list ordering is not assumed.
        var changed: Boolean
        do {
            changed = false
            overlays.forEach { (type, rectangle) ->
                val overlap = Rect(safe)
                if (!overlap.intersect(rectangle) || type !in setOf(AccessibilityWindowInfo.TYPE_SYSTEM, AccessibilityWindowInfo.TYPE_INPUT_METHOD)) return@forEach
                val trimmed = when {
                    overlap.left == safe.left && overlap.right == safe.right && overlap.top == safe.top -> { safe.top = overlap.bottom; true }
                    overlap.left == safe.left && overlap.right == safe.right && overlap.bottom == safe.bottom -> { safe.bottom = overlap.top; true }
                    overlap.top == safe.top && overlap.bottom == safe.bottom && overlap.left == safe.left -> { safe.left = overlap.right; true }
                    overlap.top == safe.top && overlap.bottom == safe.bottom && overlap.right == safe.right -> { safe.right = overlap.left; true }
                    else -> false
                }
                check(!safe.isEmpty) { getString(R.string.screen_test_screenshot_overlay) }
                changed = changed || trimmed
            }
        } while (changed)
        check(overlays.none { (_, rectangle) -> Rect.intersects(safe, rectangle) }) {
            getString(R.string.screen_test_screenshot_overlay)
        }
        return safe
    }

    private fun covered(targetWindow: AccessibilityWindowInfo, bounds: Rect): Boolean = windows.any { other ->
        val otherBounds = Rect().also(other::getBoundsInScreen)
        other.id != targetWindow.id && other.layer > targetWindow.layer && Rect.intersects(bounds, otherBounds)
    }

    private data class Node(val id: String, val path: List<Int>, val fingerprint: String, val sensitive: Boolean,
        val text: String, val description: String, val className: String, val bounds: Rect,
        val clickable: Boolean, val longClickable: Boolean, val editable: Boolean, val enabled: Boolean)
    private data class Collected(val nodes: List<Node>, val truncated: Boolean)
    private data class Snapshot(val token: String, val createdAt: Long, val nodes: List<Node>)

    companion object {
        @Volatile internal var instance: ScreenTestAccessibilityService? = null
        internal val connectionListeners = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<() -> Unit>>()
        private fun notifyConnectionChanged() {
            connectionListeners.forEach { reference ->
                val listener = reference.get()
                if (listener == null) connectionListeners.remove(reference) else listener()
            }
        }
        fun isConnected(): Boolean = instance != null
    }
}

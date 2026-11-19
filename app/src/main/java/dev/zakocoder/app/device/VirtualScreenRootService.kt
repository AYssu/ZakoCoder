package dev.zakocoder.app.device

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.graphics.createBitmap
import com.topjohnwu.superuser.ipc.RootService
import dev.zakocoder.app.screentest.boundedScreenObservation
import dev.zakocoder.app.screentest.protectedDeviceScreenPackage
import dev.zakocoder.app.screentest.resolvedPermissionController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** All state belongs to one bound client; this service never runs as a daemon. */
class VirtualScreenRootService : RootService() {
    private val lock = Any()
    private val pixelsLock = Any()
    private var display: VirtualDisplay? = null
    private var images: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var automationThread: HandlerThread? = null
    private var automation: UiAutomation? = null
    private var latestImage: android.media.Image? = null
    private var frameGeneration = 0L
    private var latestImageGeneration = -1L
    private var frameBarrierNanos = Long.MAX_VALUE
    private var owner: IBinder? = null
    private var target: String? = null
    private var snapshot: Snapshot? = null
    @Volatile private var revoked = true
    private val death = IBinder.DeathRecipient { synchronized(lock) { release() } }

    private val endpoint = object : IVirtualScreen.Stub() {
        override fun create(width: Int, height: Int, densityDpi: Int, client: IBinder): String = checked("create") {
            check(Build.VERSION.SDK_INT >= 30) { "virtual_display_android_11_required" }
            validateVirtualScreenSize(width, height, densityDpi)
            release()
            revoked = false
            owner = client.also { it.linkToDeath(death, 0) }
            try {
                imageThread = HandlerThread("ZakoVirtualFrames").apply { start() }
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                images = reader
                reader.setOnImageAvailableListener({ source ->
                    // Keep one native buffer; converting every display frame would consume CPU
                    // while the preview is hidden. Capture alone converts the latest buffer.
                    val image = runCatching { source.acquireLatestImage() }.getOrNull()
                    if (image != null) synchronized(pixelsLock) {
                        if (images === source && !revoked && image.timestamp >= frameBarrierNanos) {
                            latestImage?.close()
                            latestImage = image
                            latestImageGeneration = frameGeneration
                        } else image.close()
                    }
                }, Handler(checkNotNull(imageThread).looper))
                var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 6) or (1 shl 8)
                // Trusted, own display group. Deliberately never AUTO_MIRROR or SECURE.
                if (Build.VERSION.SDK_INT >= 33) flags = flags or (1 shl 10) or (1 shl 11)
                if (Build.VERSION.SDK_INT >= 34) flags = flags or (1 shl 14)
                display = platformStage("display_create") { createOwnedDisplay(width, height, densityDpi, reader, flags) }
                requireVirtualDisplayId(checkNotNull(display) { "virtual_display_create_failed" }.display.displayId)
                platformStage("automation_connect") { connectAutomation() }
                JSONObject().put("display_id", displayId()).put("width", width).put("height", height).put("density_dpi", densityDpi).toString()
            } catch (error: Throwable) {
                release()
                throw error
            }
        }

        override fun launch(packageName: String): String = checked("launch") {
            displayId()
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "virtual_display_package_invalid" }
            check(!protectedDeviceScreenPackage(packageName, this@VirtualScreenRootService.packageName, resolvedPermissionController(this@VirtualScreenRootService))) { "virtual_display_sensitive_app" }
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: error("virtual_display_app_not_launchable")
            val activity = intent.resolveActivityInfo(packageManager, 0) ?: error("virtual_display_app_not_launchable")
            // A singleton Activity can reuse/move a physical-screen task. Refuse that contract.
            check(activity.launchMode != android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK &&
                activity.launchMode != android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE &&
                activity.launchMode != 4) { "virtual_display_singleton_activity" }
            val component = checkNotNull(intent.component) { "virtual_display_app_not_launchable" }
            check(component.packageName == packageName && component.className.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]+"))) { "virtual_display_component_invalid" }
            clearSnapshot()
            invalidatePixels()
            // Fixed argv avoids attributing a Root process to the application's ContextImpl.
            // A positive explicit display ID and a fresh task are mandatory; no fallback.
            platformStage("activity_start") { launchOnOwnedDisplay(component.flattenToString()) }
            target = packageName
            invalidatePixels()
            JSONObject().put("requested", "launch").put("package", packageName).put("display_id", displayId()).put("verified", false).toString()
        }

        override fun execute(packageName: String, arguments: String): String = checked("execute") {
            require(arguments.length <= 20_000) { "virtual_display_arguments_too_large" }
            val args = JSONObject(arguments)
            val action = args.getString("action")
            check(target != null && (packageName.isEmpty() || packageName == target)) { "virtual_display_target_changed" }
            val current = readFrame()
            try {
                when (action) {
                    "observe" -> observe(current)
                    "assert_text" -> {
                        val text = args.getString("text")
                        require(text.isNotBlank() && text.length <= 1000)
                        check(current.nodes.any { !it.sensitive && (it.info.text?.contains(text) == true || it.info.contentDescription?.contains(text) == true) }) { "virtual_display_assert_failed" }
                        JSONObject().put("assertion", "passed").put("verified", true).put("display_id", displayId()).toString()
                    }
                    "back" -> {
                        clearSnapshot()
                        injectKey(KeyEvent.KEYCODE_BACK)
                        invalidatePixels()
                        requested(action)
                    }
                    "click", "long_click", "input_text", "swipe", "scroll" -> {
                        val old = checkNotNull(snapshot) { "virtual_display_stale_snapshot" }
                        check(args.optString("snapshot") == old.token && SystemClock.elapsedRealtime() - old.at <= 60_000 && old.windowIds == current.windowIds && old.projection == current.projection) { "virtual_display_stale_snapshot" }
                        val index = args.optString("node").removePrefix("n").toIntOrNull()?.minus(1)
                        val node = index?.takeIf { it >= 0 && it < old.exposed }?.let(current.nodes::getOrNull) ?: error("virtual_display_stale_snapshot")
                        check(!node.sensitive && node.info.isEnabled && !node.bounds.isEmpty) { "virtual_display_sensitive_node" }
                        clearSnapshot()
                        when (action) {
                            "input_text" -> {
                                val text = args.getString("text")
                                require(text.length <= 2000)
                                check(node.info.isEditable && node.info.isFocused) { "virtual_display_input_not_focused" }
                                check(node.info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })) { "virtual_display_input_failed" }
                            }
                            "click" -> gesture(node.bounds, "tap", 80)
                            "long_click" -> gesture(node.bounds, "tap", 700)
                            else -> {
                                check(action != "scroll" || node.info.isScrollable) { "virtual_display_node_not_scrollable" }
                                gesture(node.bounds, args.optString("direction", "up"), 350)
                            }
                        }
                        invalidatePixels()
                        requested(action)
                    }
                    else -> error("virtual_display_action_unsupported")
                }
            } finally { current.close() }
        }

        override fun capture(): ParcelFileDescriptor = checked("capture") {
            val bytes = captureStableBytes()
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
            }, "ZakoVirtualCapture").start()
            pipe[0]
        }

        override fun cancel() {
            check(Binder.getCallingUid() == applicationInfo.uid) { "virtual_display_caller_denied" }
            revoked = true
        }
        override fun close() { checked("close") { release() } }
    }

    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() { synchronized(lock) { release() }; super.onDestroy() }

    private fun <T> checked(stage: String, block: () -> T): T {
        check(Binder.getCallingUid() == applicationInfo.uid) { "virtual_display_caller_denied" }
        val identity = Binder.clearCallingIdentity()
        return try { synchronized(lock) { block() } }
        catch (error: Exception) {
            val chain = generateSequence(error as Throwable?) { it.cause }.take(6).toList()
            val code = chain.firstNotNullOfOrNull { it.message?.takeIf { message -> message.matches(Regex("virtual_display_[a-z0-9_]+")) } }
                ?: when {
                    chain.any { it.message?.contains("packageName must match the calling uid") == true } -> "virtual_display_package_uid_mismatch"
                    chain.any { it.message?.contains("already registered", ignoreCase = true) == true } -> "virtual_display_automation_busy"
                    chain.any { it is NoSuchMethodException || it is ClassNotFoundException } -> "virtual_display_android_api_unsupported"
                    chain.any { it is SecurityException } -> "virtual_display_${stage}_permission_denied"
                    else -> "virtual_display_${stage}_failed"
                }
            android.util.Log.w("ZakoVirtualScreen", "stage=$stage error=$code types=${chain.joinToString(",") { it.javaClass.simpleName }}")
            throw IllegalStateException(code, error)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun <T> platformStage(stage: String, action: () -> T): T = try { action() }
    catch (error: Exception) {
        val chain = generateSequence(error as Throwable?) { it.cause }.take(6).toList()
        val known = chain.firstNotNullOfOrNull { it.message?.takeIf { text -> text.matches(Regex("virtual_display_[a-z0-9_]+")) } }
        val permission = chain.firstNotNullOfOrNull { cause ->
            Regex("android\\.permission\\.([A-Z][A-Z_]+)").find(cause.message.orEmpty())?.groupValues?.get(1)?.lowercase()
                ?: Regex("[Rr]equires ([A-Z][A-Z_]+)\\b").find(cause.message.orEmpty())?.groupValues?.get(1)?.lowercase()
        }
        val reason = when {
            permission != null -> "permission_$permission"
            chain.any { it.message?.contains("already registered", true) == true } -> "automation_busy"
            chain.any { it.message?.contains("packageName must match", true) == true || it.message?.contains("calling package", true) == true } -> "package_uid_mismatch"
            chain.any { it.message?.contains("not allowed", true) == true } -> "not_allowed"
            chain.any { it.message?.contains("not connected", true) == true } -> "not_connected"
            chain.any { it is SecurityException } -> "security_denied"
            else -> "failed"
        }
        val code = known ?: "virtual_display_${stage}_$reason"
        val frames = chain.flatMap { it.stackTrace.take(3).map { frame -> frame.className + "." + frame.methodName } }.take(9).joinToString(",")
        android.util.Log.w("ZakoVirtualScreen", "stage=$stage error=$code types=${chain.joinToString(",") { it.javaClass.simpleName }} frames=$frames")
        throw IllegalStateException(code, error)
    }

    private fun displayId(): Int {
        check(!revoked) { "virtual_display_permission_revoked" }
        return requireVirtualDisplayId(checkNotNull(display) { "virtual_display_not_started" }.display.displayId)
    }

    private fun createOwnedDisplay(width: Int, height: Int, densityDpi: Int, reader: ImageReader, flags: Int): VirtualDisplay {
        val creation = VirtualScreenCreationLease<VirtualDisplay> { it.release() }
        try {
            val created = onRootMain(creation::cancel) {
                // DMS validates package -> Linux UID even for UID 0. Root has no installed package.
                // Binder takes sender_euid from the process group leader (not the worker).
                // Bionic seteuid changes this calling thread only, so this entire call must run
                // on the actual process main thread. Temporarily use Android's system identity, whose
                // real installed package owns this display. OEM shell permission subsets are not
                // sufficient for a trusted independent display. The process retains its real/saved
                // Root UID and restores effective Root immediately after this framework call.
                val systemContext = object : ContextWrapper(this@VirtualScreenRootService) {
                    override fun getPackageName(): String = "android"
                    override fun getOpPackageName(): String = "android"
                    override fun getApplicationContext(): Context = this
                }
                check(android.system.Os.geteuid() == 0) { "virtual_display_root_identity_required" }
                try {
                    android.system.Os.seteuid(1000)
                    check(android.system.Os.geteuid() == 1000) { "virtual_display_system_identity_failed" }
                    val manager = platformStage("display_manager") { DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(systemContext) }
                    check(!revoked) { "virtual_display_permission_revoked" }
                    val allocated = checkNotNull(platformStage("display_allocate") { manager.createVirtualDisplay("ZakoCoder-${UUID.randomUUID()}", width, height, densityDpi, reader.surface, flags) }) { "virtual_display_allocate_returned_null" }
                    creation.publish(allocated)
                    check(!revoked) { "virtual_display_permission_revoked" }
                    allocated
                } finally {
                    android.system.Os.seteuid(0)
                    check(android.system.Os.geteuid() == 0) { "virtual_display_root_identity_restore_failed" }
                }
            }
            creation.take(created)
            return created
        } catch (error: Throwable) {
            creation.cancel()
            throw error
        }
    }

    private fun <T> onRootMain(onCancelled: () -> Unit, action: () -> T): T {
        fun run(): T {
            check(android.os.Process.myTid() == android.os.Process.myPid()) { "virtual_display_main_thread_identity" }
            return action()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return run()
        val task = FutureTask { run() }
        check(Handler(Looper.getMainLooper()).post(task)) { "virtual_display_main_thread_unavailable" }
        return try { task.get(15, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
        catch (error: TimeoutException) {
            revoked = true
            onCancelled()
            task.cancel(false)
            throw IllegalStateException("virtual_display_main_thread_timeout", error)
        }
        catch (error: InterruptedException) {
            revoked = true
            onCancelled()
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw IllegalStateException("virtual_display_main_thread_interrupted", error)
        }
    }

    private fun launchOnOwnedDisplay(component: String) {
        val id = displayId()
        val child = ProcessBuilder(
            "/system/bin/am", "start", "--user", (applicationInfo.uid / 100_000).toString(),
            "--display", id.toString(), "-f", "0x18000000", // NEW_TASK | MULTIPLE_TASK
            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_LAUNCHER, "-n", component,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val drain = Thread({
            runCatching { child.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        if (output.length + count > 32_768) { child.destroyForcibly(); break }
                        output.append(buffer, 0, count)
                    }
                }
            } }
        }, "ZakoVirtualLaunch").apply { start() }
        try {
            check(child.waitFor(12, TimeUnit.SECONDS)) { "virtual_display_activity_start_timeout" }
            drain.join(1000)
            check(!drain.isAlive && output.length < 32_768) { "virtual_display_activity_start_output_limit" }
            val diagnostic = synchronized(output) { output.toString() }
            val problem = when {
                diagnostic.contains("Unknown option", true) || diagnostic.contains("Unrecognized option", true) || diagnostic.contains("Unsupported option", true) -> "unsupported_option"
                diagnostic.contains("display", true) && (diagnostic.contains("not allowed", true) || diagnostic.contains("denied", true) || diagnostic.contains("not found", true) || diagnostic.contains("invalid", true)) -> "display_denied"
                diagnostic.contains("Permission Denial", true) || diagnostic.contains("SecurityException") -> "permission_denied"
                diagnostic.contains("does not exist", true) || diagnostic.contains("unable to resolve", true) || diagnostic.contains("Unknown activity", true) -> "unknown_activity"
                diagnostic.contains("timed out", true) || diagnostic.contains("timeout", true) -> "timeout"
                diagnostic.contains("not allowed", true) -> "not_allowed"
                diagnostic.contains("not started", true) -> "not_started"
                diagnostic.contains("Error:", true) || diagnostic.contains("Exception") || child.exitValue() != 0 -> "failed"
                else -> null
            }
            if (problem != null) error("virtual_display_activity_start_$problem")
            displayId() // Revocation during startup cannot publish a usable target.
        } finally {
            child.destroyForcibly()
            drain.join(1000)
        }
    }

    // Root-only UiAutomation has no public construction API. Fail closed if these
    // framework contracts are unavailable; never use physical-screen accessibility.
    @android.annotation.SuppressLint("PrivateApi")
    private fun connectAutomation() {
        val connectionClass = Class.forName("android.app.UiAutomationConnection")
        val connection = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val protocol = Class.forName("android.app.IUiAutomationConnection")
        automationThread = HandlerThread("ZakoVirtualNodes").apply { start() }
        val instance = UiAutomation::class.java.getConstructor(Looper::class.java, protocol).newInstance(checkNotNull(automationThread).looper, connection)
        // Keep the user's accessibility services enabled; no rootless screen fallback exists.
        platformStage("automation_register") { UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
        automation = instance
        platformStage("automation_windows") {
            val info = instance.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instance.serviceInfo = info
        }
    }

    private fun captureStableBytes(): ByteArray {
        val expectedDisplay = displayId()
        val expectedTarget = checkNotNull(target) { "virtual_display_target_changed" }
        // One shared budget for all read-only retries, below the existing action timeout.
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (true) {
            check(displayId() == expectedDisplay && target == expectedTarget) { "virtual_display_target_changed" }
            try {
                val bytes = captureFrame(deadline)
                check(displayId() == expectedDisplay && target == expectedTarget) { "virtual_display_target_changed" }
                return bytes
            } catch (error: IllegalStateException) {
                // Permission, sensitive windows/nodes and identity changes never retry.
                if (error.message !in setOf("virtual_display_frame_changed", "virtual_display_frame_not_ready", "virtual_display_window_not_ready")) throw error
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw error
                displayId() // A cancellation must terminate before the next read.
                SystemClock.sleep(minOf(80, remaining))
            }
        }
    }

    private fun captureFrame(deadline: Long): ByteArray {
        val current = readFrame(deadline)
        try {
            check(current.nodes.none { it.sensitive }) { "virtual_display_sensitive_frame" }
            awaitFreshPixels(deadline)
            val bytes = synchronized(pixelsLock) {
                val image = checkNotNull(latestImage) { "virtual_display_frame_not_ready" }
                check(latestImageGeneration == frameGeneration && image.timestamp >= frameBarrierNanos) { "virtual_display_frame_not_ready" }
                val plane = image.planes.firstOrNull() ?: error("virtual_display_capture_failed")
                check(plane.pixelStride == 4) { "virtual_display_pixel_format" }
                val padded = createBitmap(plane.rowStride / plane.pixelStride, image.height)
                var bitmap: Bitmap? = null
                try {
                    plane.buffer.rewind()
                    padded.copyPixelsFromBuffer(plane.buffer)
                    bitmap = if (padded.width == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    java.io.ByteArrayOutputStream().use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "virtual_display_capture_failed" }
                        output.toByteArray().also { check(it.size <= 12_582_912) { "virtual_display_capture_too_large" } }
                    }
                } finally { if (bitmap !== padded) bitmap?.recycle(); padded.recycle() }
            }
            // Recheck sensitive windows and node identity after reading pixels. No prior
            // bytes are reused if an in-app transition changed the observed projection.
            readFrame(deadline).use { after ->
                check(after.nodes.none { it.sensitive }) { "virtual_display_sensitive_frame" }
                check(after.windowIds == current.windowIds && after.projection == current.projection) { "virtual_display_frame_changed" }
            }
            return bytes
        } finally { current.close() }
    }

    private fun readFrame(deadline: Long = Long.MAX_VALUE): Frame {
        if (Build.VERSION.SDK_INT < 30) error("virtual_display_android_11_required")
        val id = displayId()
        requireNoSecureWindow(deadline)
        val windows = checkNotNull(automation) { "virtual_display_nodes_disconnected" }.windowsOnAllDisplays[id].orEmpty()
        check(windows.isNotEmpty()) { "virtual_display_window_not_ready" }
        val nodes = mutableListOf<Node>()
        val ids = mutableListOf<Int>()
        try {
            for (window in windows) {
                val root = window.root ?: error("virtual_display_unknown_window")
                try {
                    val pkg = root.packageName?.toString().orEmpty()
                    check(pkg == target && !protectedDeviceScreenPackage(pkg, packageName, resolvedPermissionController(this))) { "virtual_display_foreign_or_sensitive_window" }
                    check(window.type == AccessibilityWindowInfo.TYPE_APPLICATION) { "virtual_display_system_window" }
                    ids += window.id
                    walk(root, false, nodes, 0)
                } finally { root.recycle() }
            }
            check(ids.isNotEmpty() && nodes.isNotEmpty()) { "virtual_display_window_not_ready" }
            return Frame(ids.sorted(), nodes)
        } catch (error: Throwable) { nodes.forEach { it.info.recycle() }; throw error }
        finally { windows.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun walk(node: AccessibilityNodeInfo, sensitiveParent: Boolean, result: MutableList<Node>, depth: Int) {
        check(depth <= 60 && result.size < 300) { "virtual_display_node_limit" }
        val sensitive = sensitiveParent || node.isPassword
        check(node.packageName?.toString() == target) { "virtual_display_foreign_node" }
        if (node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val size = checkNotNull(display).display.mode
            if (!bounds.intersect(0, 0, size.physicalWidth, size.physicalHeight)) bounds.setEmpty()
            result += Node(AccessibilityNodeInfo.obtain(node), bounds, sensitive)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> try { walk(child, sensitive, result, depth + 1) } finally { child.recycle() } }
        }
    }

    private fun observe(frame: Frame): String {
        val token = UUID.randomUUID().toString()
        val bounded = boundedScreenObservation(target, token, frame.nodes.mapIndexed { i, n -> n.json("n${i + 1}") })
        snapshot = Snapshot(token, SystemClock.elapsedRealtime(), frame.windowIds, frame.projection, bounded.exposedCount)
        return JSONObject(bounded.output).put("display_id", displayId()).toString()
    }

    private fun gesture(bounds: Rect, direction: String, duration: Long) {
        val x = bounds.centerX().toFloat(); val y = bounds.centerY().toFloat()
        val dx = (bounds.width() / 4).toFloat(); val dy = (bounds.height() / 4).toFloat()
        val points = when (direction) {
            "tap" -> floatArrayOf(x, y, x, y)
            "up" -> floatArrayOf(x, y + dy, x, y - dy)
            "down" -> floatArrayOf(x, y - dy, x, y + dy)
            "left" -> floatArrayOf(x + dx, y, x - dx, y)
            "right" -> floatArrayOf(x - dx, y, x + dx, y)
            else -> error("virtual_display_direction_invalid")
        }
        val at = SystemClock.uptimeMillis()
        var lifted = false
        fun event(action: Int, progress: Float) {
            val motion = MotionEvent.obtain(at, SystemClock.uptimeMillis(), action, points[0] + (points[2] - points[0]) * progress, points[1] + (points[3] - points[1]) * progress, 0)
            try { motion.source = InputDevice.SOURCE_TOUCHSCREEN; inject(motion) } finally { motion.recycle() }
        }
        try {
            event(MotionEvent.ACTION_DOWN, 0f)
            while (SystemClock.uptimeMillis() - at < duration) {
                SystemClock.sleep(16)
                event(MotionEvent.ACTION_MOVE, ((SystemClock.uptimeMillis() - at).toFloat() / duration).coerceAtMost(1f))
            }
            event(MotionEvent.ACTION_UP, 1f); lifted = true
        } finally { if (!lifted) runCatching {
            val cancel = MotionEvent.obtain(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, points[2], points[3], 0)
            try {
                cancel.source = InputDevice.SOURCE_TOUCHSCREEN
                val id = requireVirtualDisplayId(checkNotNull(display).display.displayId)
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(cancel, id)
                automation?.injectInputEvent(cancel, true)
            } finally { cancel.recycle() }
        } }
    }

    private fun injectKey(keyCode: Int) {
        val at = SystemClock.uptimeMillis()
        inject(KeyEvent(at, at, KeyEvent.ACTION_DOWN, keyCode, 0))
        inject(KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun inject(event: InputEvent) {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId())
        check(checkNotNull(automation).injectInputEvent(event, true)) { "virtual_display_input_rejected" }
    }

    private fun requireNoSecureWindow(deadline: Long) {
        check(SystemClock.elapsedRealtime() < deadline) { "virtual_display_frame_not_ready" }
        val child = ProcessBuilder("/system/bin/dumpsys", "window", "windows").redirectErrorStream(true).start()
        val text = StringBuilder()
        val read = Thread({ child.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            while (true) { val count = reader.read(buffer); if (count < 0) break; synchronized(text) { if (text.length + count > 2_097_152) { child.destroyForcibly(); break }; text.append(buffer, 0, count) } }
        } }, "ZakoVirtualWindowCheck").apply { start() }
        try {
            check(child.waitFor(minOf(8000, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)), TimeUnit.MILLISECONDS)) { "virtual_display_secure_check_timeout" }
            read.join(1000)
            check(!read.isAlive && child.exitValue() == 0) { "virtual_display_secure_check_failed" }
            check(text.length < 2_097_152) { "virtual_display_secure_check_output_limit" }
            val dump = synchronized(text) { text.toString() }
            check(virtualDisplayCaptureAllowed(dump, displayId())) { "virtual_display_secure_or_unknown_window" }
        } finally { child.destroyForcibly(); read.join(1000) }
    }

    private fun requested(action: String) = JSONObject().put("requested", action).put("dispatched", true).put("verified", false).put("next", "observe").put("display_id", displayId()).toString()
    private fun clearSnapshot() { snapshot = null }
    private fun invalidatePixels() = synchronized(pixelsLock) {
        frameGeneration++
        frameBarrierNanos = System.nanoTime()
        latestImageGeneration = -1
        latestImage?.close(); latestImage = null
    }
    private fun awaitFreshPixels(captureDeadline: Long) {
        val deadline = minOf(captureDeadline, SystemClock.elapsedRealtime() + 2500)
        while (SystemClock.elapsedRealtime() < deadline) {
            displayId()
            if (synchronized(pixelsLock) { latestImage != null && latestImageGeneration == frameGeneration }) return
            SystemClock.sleep(16)
        }
        error("virtual_display_frame_not_ready")
    }
    private fun release() {
        revoked = true
        clearSnapshot(); target = null
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }; owner = null
        runCatching { display?.release() }; display = null
        runCatching { images?.setOnImageAvailableListener(null, null) }
        synchronized(pixelsLock) { latestImage?.close(); latestImage = null }
        runCatching { images?.close() }; images = null
        runCatching { automation?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) } }; automation = null
        imageThread?.quitSafely(); imageThread = null
        automationThread?.quitSafely(); automationThread = null
    }

    private data class Snapshot(val token: String, val at: Long, val windowIds: List<Int>, val projection: String, val exposed: Int)
    private data class Frame(val windowIds: List<Int>, val nodes: List<Node>) : java.io.Closeable {
        val projection: String get() = JSONArray(nodes.mapIndexed { i, n -> n.json("n${i + 1}") }).toString()
        override fun close() { nodes.forEach { it.info.recycle() } }
    }
    private data class Node(val info: AccessibilityNodeInfo, val bounds: Rect, val sensitive: Boolean) {
        fun json(id: String): JSONObject = JSONObject().put("id", id).put("text", if (sensitive) "[redacted]" else info.text?.toString().orEmpty().take(160))
            .put("description", if (sensitive) "[redacted]" else info.contentDescription?.toString().orEmpty().take(160))
            .put("class", info.className?.toString().orEmpty().take(120)).put("resource", info.viewIdResourceName.orEmpty().take(200))
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))).put("sensitive", sensitive)
            .put("enabled", info.isEnabled).put("clickable", info.isClickable).put("editable", info.isEditable).put("focused", info.isFocused).put("scrollable", info.isScrollable)
    }
}

/** Ownership handoff for one creation; a later request cannot revive this lease. */
internal class VirtualScreenCreationLease<T : Any>(private val dispose: (T) -> Unit) {
    private val cancelled = AtomicBoolean()
    private val owned = AtomicReference<T?>()
    fun publish(resource: T) {
        owned.set(resource)
        if (cancelled.get()) { cancel(); error("virtual_display_permission_revoked") }
    }
    fun take(resource: T) {
        check(!cancelled.get() && owned.compareAndSet(resource, null)) { "virtual_display_permission_revoked" }
    }
    fun cancel() {
        cancelled.set(true)
        owned.getAndSet(null)?.let { runCatching { dispose(it) } }
    }
}

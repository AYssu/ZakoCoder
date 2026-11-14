package dev.zakocoder.app.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** A synthetic entry surface: no workspace, credentials, network, or model configuration. */
@SdkSuppress(minSdkVersion = 30)
class WorkbenchEntranceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90000)
    fun dayIndicatorKeepsAnimatingUntilDestinationIsRevealed() = exercise(dark = false, animate = true)

    @Test(timeout = 90000)
    fun nightIndicatorKeepsAnimatingUntilDestinationIsRevealed() = exercise(dark = true, animate = true)

    @Test(timeout = 90000)
    fun animationDisabledStillDeliversInteractiveContent() {
        exercise(dark = false, animate = false)
        initiallyReadyOverlayDoesNotFlashLoadingIndicator()
    }

    private fun initiallyReadyOverlayDoesNotFlashLoadingIndicator() {
        val laidOut = AtomicBoolean()
        val spinnerSeen = AtomicBoolean()
        val revealed = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                WorkbenchMiuixTheme(false) {
                    WorkbenchEntrance("ready-overlay", ready = true, animate = false,
                        onRevealed = { revealed.set(true) }) {
                        val view = LocalView.current
                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                            if (collect(view.rootView).any(::isIndicator)) spinnerSeen.set(true)
                            laidOut.set(true)
                        }) { Text("已经准备好的详情") }
                    }
                }
            } }
            await("An initially ready overlay completes") { laidOut.get() && revealed.get() }
            assertFalse("An already-ready overlay must not flash a startup spinner", spinnerSeen.get())
            assertNull(indicatorBounds())
        }
    }

    private fun exercise(dark: Boolean, animate: Boolean) {
        warmDeviceActivity()
        val ready = mutableStateOf(false)
        val revealed = AtomicInteger()
        val compositions = AtomicInteger()
        val firstLayout = AtomicBoolean()
        val indicatorAtFirstLayout = AtomicBoolean()
        val clicks = AtomicInteger()
        val theme = if (dark) "night" else "day"
        val suffix = if (animate) theme else "no-animation"
        val directory = File(context.cacheDir, "ui-review/startup-entry/$suffix").apply { mkdirs() }
        val buttonBounds = AtomicReference<Rect>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                UpgradeUiHarness().configureSystemBars(activity, dark)
                activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        WorkbenchEntrance("application", ready.value, animate = animate,
                            onRevealed = { revealed.incrementAndGet() }) {
                            val view = LocalView.current
                            DisposableEffect(Unit) {
                                compositions.incrementAndGet()
                                onDispose { }
                            }
                            Box(Modifier.fillMaxSize().onGloballyPositioned {
                                if (firstLayout.compareAndSet(false, true)) {
                                    indicatorAtFirstLayout.set(collect(view.rootView).any(::isIndicator))
                                }
                            }, contentAlignment = Alignment.Center) {
                                Button(onClick = { clicks.incrementAndGet() }, modifier = Modifier.onGloballyPositioned {
                                    val position = IntArray(2).also(view::getLocationOnScreen)
                                    val bounds = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                                    buttonBounds.set(Rect((position[0] + bounds.x).toInt(), (position[1] + bounds.y).toInt(),
                                        (position[0] + bounds.x + it.size.width).toInt(),
                                        (position[1] + bounds.y + it.size.height).toInt()))
                                }) { Text("进入工作台") }
                            }
                        }
                    }
                }
            }
            await("A live entry indicator is laid out") { indicatorBounds() != null }
            // Semantics can precede the first compositor frame; do not capture the old
            // Activity surface or count its disappearance as indicator animation.
            SystemClock.sleep(250)
            assertEquals("Destination must remain lazy while required data is pending", 0, compositions.get())
            val bounds = requireNotNull(indicatorBounds())
            val frames = mutableListOf<Bitmap>()
            try {
                repeat(if (animate) 5 else 1) { index ->
                    val full = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        if (index == 0) save(full, File(directory, "loading.png"))
                        val safe = Rect(bounds).apply { assertTrue("Indicator lies inside the screenshot", intersect(0, 0, full.width, full.height)) }
                        val frame = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
                        frames += frame
                        save(frame, File(directory, "indicator-$index.png"))
                    } finally { full.recycle() }
                    SystemClock.sleep(107)
                }
                if (animate) {
                    assertTrue("Device animation scale must allow real animation evidence", ValueAnimator.areAnimatorsEnabled())
                    val changed = frames.drop(1).map { changedPixels(frames.first(), it) }
                    File(directory, "frame-difference.txt").writeText("Indicator-only crop ${bounds.width()}x${bounds.height()}; changed pixels: $changed\n")
                    assertTrue("Spinner itself must advance, independent of the rest of the screen: $changed", changed.any { it > 12 })
                }
            } finally { frames.forEach(Bitmap::recycle) }
            if (animate) assertIndicatorAdvancesDuringMainThreadStall(bounds, directory)
            scenario.onActivity { ready.value = true }
            await("Destination reaches its first layout") { firstLayout.get() }
            assertTrue("Data readiness must not remove the indicator before destination's first layout", indicatorAtFirstLayout.get())
            await("Entry transition completes") { revealed.get() > 0 && indicatorBounds() == null }
            assertEquals("Destination is composed once", 1, compositions.get())
            assertTrue("Completed entry removes its indicator", indicatorBounds() == null)
            tap(requireNotNull(buttonBounds.get()))
            await("Destination receives input after reveal") { clicks.get() == 1 }
            capture(File(directory, "revealed.png"))
            scenario.onActivity { ready.value = false }
            SystemClock.sleep(200)
            assertNull("A later background refresh must not resurrect the startup cover", indicatorBounds())
            assertEquals("Refresh does not recreate the destination", 1, compositions.get())
            tap(requireNotNull(buttonBounds.get()))
            await("Destination remains interactive during a later refresh") { clicks.get() == 2 }
        }
    }

    /** Cached screen bounds are deliberate: asking Main for semantics during the stall hides it. */
    private fun assertIndicatorAdvancesDuringMainThreadStall(bounds: Rect, directory: File) {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val blocking = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        val frames = mutableListOf<Bitmap>()
        val captureOffsets = mutableListOf<Long>()
        val began = java.util.concurrent.atomic.AtomicLong()
        val worker = Thread({
            try {
                instrumentation.runOnMainSync {
                    began.set(SystemClock.uptimeMillis())
                    blocking.set(true)
                    started.countDown()
                    try { SystemClock.sleep(600) }
                    finally { blocking.set(false) }
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { completed.countDown() }
        }, "startup-main-stall-proof").apply { isDaemon = true }
        worker.start()
        try {
            assertTrue("Main-thread stall starts", started.await(5, TimeUnit.SECONDS))
            repeat(3) { index ->
                assertTrue("The indicator frame must be captured while Main is actually blocked", blocking.get())
                val full = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    captureOffsets += SystemClock.uptimeMillis() - began.get()
                    assertTrue("Compositor screenshot returns before the simulated stall ends", blocking.get())
                    val safe = Rect(bounds).apply { assertTrue("Indicator lies inside the screenshot", intersect(0, 0, full.width, full.height)) }
                    val frame = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
                    frames += frame
                    save(frame, File(directory, "stalled-indicator-$index.png"))
                } finally { full.recycle() }
                if (index < 2) SystemClock.sleep(107)
            }
            val changed = frames.drop(1).map { changedPixels(frames.first(), it) }
            File(directory, "frame-difference.txt").appendText(
                "600 ms Main stall; frame offsets ms: $captureOffsets; indicator-only changed pixels: $changed\n")
            assertTrue("Indicator must keep rotating through 600 ms of first-composition work: $changed", changed.any { it > 12 })
        } finally {
            frames.forEach(Bitmap::recycle)
            assertTrue("Simulated Main stall finishes", completed.await(10, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Main-stall injection failed", it) }
        }
    }

    private fun isIndicator(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null

    private fun collect(view: android.view.View): List<SemanticsNode> = buildList {
        fun walk(node: SemanticsNode) { add(node); node.children.forEach(::walk) }
        fun visit(child: android.view.View) {
            if (child is ViewRootForTest) walk(child.semanticsOwner.unmergedRootSemanticsNode)
            if (child is android.view.ViewGroup) repeat(child.childCount) { visit(child.getChildAt(it)) }
        }
        visit(view)
    }

    private fun indicatorBounds(): Rect? {
        val result = AtomicReference<Rect?>()
        instrumentation.runOnMainSync {
            android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                if (!root.isShown) null else collect(root).firstOrNull(::isIndicator)?.boundsInWindow?.let { bounds ->
                    val position = IntArray(2).also(root::getLocationOnScreen)
                    Rect((bounds.left + position[0]).toInt(), (bounds.top + position[1]).toInt(),
                        (bounds.right + position[0]).toInt(), (bounds.bottom + position[1]).toInt())
                        .takeUnless { it.isEmpty }
                }
            }.also(result::set)
        }
        return result.get()
    }

    private fun changedPixels(before: Bitmap, after: Bitmap): Int {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val first = before.getPixel(x, y); val second = after.getPixel(x, y)
            if (kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) > 12 ||
                kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) > 12 ||
                kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second)) > 12) changed++
        }
        return changed
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!ready()) {
            check(SystemClock.uptimeMillis() < deadline) { label }
            SystemClock.sleep(30)
        }
    }

    private fun tap(bounds: Rect) {
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }

    private fun capture(file: File) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { save(screenshot, file) } finally { screenshot.recycle() }
    }

    private fun save(bitmap: Bitmap, file: File) {
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

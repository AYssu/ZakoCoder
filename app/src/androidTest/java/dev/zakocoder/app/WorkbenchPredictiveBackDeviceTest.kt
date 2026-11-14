package dev.zakocoder.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import android.view.Choreographer
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ui.AiFullScreen
import dev.zakocoder.app.ui.UpgradeUiHarness
import dev.zakocoder.app.ui.WorkbenchMiuixTheme
import dev.zakocoder.app.ui.rememberWorkbenchBackMotion
import dev.zakocoder.app.ui.WorkbenchBackMotion
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Dispatches real AndroidX back events, including cancellation, without touching user data. */
class WorkbenchPredictiveBackDeviceTest {
    private fun assertColor(message: String, expected: Int, actual: Int) {
        val expectedRgb = intArrayOf(android.graphics.Color.red(expected), android.graphics.Color.green(expected), android.graphics.Color.blue(expected))
        val actualRgb = intArrayOf(android.graphics.Color.red(actual), android.graphics.Color.green(actual), android.graphics.Color.blue(actual))
        assertTrue("$message: expected rgb(${expectedRgb.joinToString()}), actual rgb(${actualRgb.joinToString()}), per-channel tolerance 8",
            expectedRgb.indices.all { kotlin.math.abs(expectedRgb[it] - actualRgb[it]) <= 8 })
    }

    @SdkSuppress(minSdkVersion = 30)
    @Test(timeout = 90000)
    fun dayFullScreenDialogProgressRevealsRealPageCancelAndCommit() = dialogGestureReview(false)

    @SdkSuppress(minSdkVersion = 30)
    @Test(timeout = 90000)
    fun nightFullScreenDialogProgressRevealsRealPageCancelAndCommit() = dialogGestureReview(true)

    /** Inject into the Dialog's own dispatcher, never the Activity's back dispatcher. */
    private fun dialogGestureReview(dark: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        warmDeviceActivity()
        val committed = AtomicInteger()
        val shown = mutableStateOf(false)
        val parentDispatcher = AtomicReference<NavigationEventDispatcher>()
        val dialogDispatcher = AtomicReference<NavigationEventDispatcher>()
        val dialogWindow = AtomicReference<Window>()
        val input = DialogBackInput()
        val theme = if (dark) "night" else "day"
        val directory = File(context.cacheDir, "ui-review/predictive-dialog").apply { mkdirs() }
        val underneath = android.graphics.Color.rgb(0, 168, 107)

        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (!condition()) {
                check(SystemClock.uptimeMillis() < deadline) { message }
                Thread.sleep(40)
            }
            instrumentation.waitForIdleSync()
        }
        fun capture(name: String): Bitmap {
            instrumentation.waitForIdleSync()
            Thread.sleep(180)
            return requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { bitmap ->
                File(directory, "$theme-$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
        fun edgePixel(bitmap: Bitmap, right: Boolean): Int =
            bitmap.getPixel(if (right) bitmap.width - 8 else 8, bitmap.height / 2)
        fun captureEdge(name: String, right: Boolean): Int {
            val bitmap = capture(name)
            return try { edgePixel(bitmap, right) } finally { bitmap.recycle() }
        }
        fun dispatch(action: DialogBackInput.() -> Unit) = instrumentation.runOnMainSync {
            assertNotNull("Dialog dispatcher remains attached", dialogDispatcher.get())
            input.action()
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                UpgradeUiHarness().configureSystemBars(activity, dark)
                activity.setContent {
                    WorkbenchMiuixTheme(dark) {
                        val parent = requireNotNull(LocalNavigationEventDispatcherOwner.current)
                            .navigationEventDispatcher
                        SideEffect { parentDispatcher.set(parent) }
                        Box(Modifier.fillMaxSize().background(Color(underneath)), contentAlignment = Alignment.Center) {
                            Text("真实的设置页面 · 返回后仍然保留", color = Color.White)
                            if (shown.value) AiFullScreen("全屏详情返回验证", close = {
                                committed.incrementAndGet()
                                shown.value = false
                            }) {
                                val dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current)
                                    .navigationEventDispatcher
                                val view = LocalView.current
                                SideEffect {
                                    dialogWindow.set((view.parent as? DialogWindowProvider)?.window)
                                }
                                DisposableEffect(dispatcher) {
                                    dispatcher.addInput(input)
                                    dialogDispatcher.set(dispatcher)
                                    onDispose {
                                        dispatcher.removeInput(input)
                                        dialogDispatcher.compareAndSet(dispatcher, null)
                                    }
                                }
                                Box(Modifier.fillMaxSize().background(Color.Blue), contentAlignment = Alignment.Center) {
                                    Text("正在查看的详情页面", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            await("Parent page is composed") { parentDispatcher.get() != null }
            assertColor("Reference is a real colored parent page", underneath,
                captureEdge("parent-reference", false))
            scenario.onActivity { shown.value = true }
            await("Full-screen Dialog owns a separate dispatcher and window") {
                dialogDispatcher.get() != null && dialogWindow.get() != null
            }
            assertNotSame("The Dialog must not reuse Activity back dispatch", parentDispatcher.get(), dialogDispatcher.get())
            instrumentation.runOnMainSync {
                assertEquals("Full-screen Dialog must not dim the real page", 0,
                    dialogWindow.get().attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            val windowBackground = AtomicReference<android.graphics.drawable.Drawable>()
            instrumentation.runOnMainSync { windowBackground.set(dialogWindow.get().decorView.background) }

            for (edge in listOf(NavigationEvent.EDGE_LEFT, NavigationEvent.EDGE_RIGHT)) {
                val right = edge == NavigationEvent.EDGE_RIGHT
                val side = if (right) "right" else "left"
                assertColor("Dialog initially covers the parent", android.graphics.Color.BLUE,
                    captureEdge("$side-covered", right))
                dispatch { started(edge); progressed(.15f, edge) }
                assertColor("Progress reveals the actual parent color without a gray scrim", underneath,
                    captureEdge("$side-progress", right))
                assertEquals("Gesture progress does not navigate", 0, committed.get())
                dispatch { cancelled() }
                Thread.sleep(650)
                instrumentation.runOnMainSync {
                    assertSame("Cancelling a gesture must not replace the window background during return",
                        windowBackground.get(), dialogWindow.get().decorView.background)
                }
                assertColor("Cancel restores the full detail surface", android.graphics.Color.BLUE,
                    captureEdge("$side-cancelled", right))
                assertEquals("Cancel never dismisses the Dialog", 0, committed.get())
            }
            // Re-grab the same edge within the first 30 ms of cancellation. The new
            // gesture starts at zero, but must carry the still-visible displacement.
            dispatch { started(NavigationEvent.EDGE_LEFT); progressed(.65f, NavigationEvent.EDGE_LEFT) }
            assertColor("First gesture exposes the retained page", underneath,
                captureEdge("regrab-before-cancel", false))
            dispatch { cancelled() }
            Thread.sleep(20)
            dispatch { started(NavigationEvent.EDGE_LEFT) }
            assertColor("Re-grab at zero progress must retain the interrupted displacement", underneath,
                captureEdge("regrab-start-carried", false))
            assertEquals("Re-grab never navigates", 0, committed.get())
            dispatch { progressed(.2f, NavigationEvent.EDGE_LEFT) }
            assertColor("Subsequent progress keeps the real parent exposed", underneath,
                captureEdge("regrab-progress", false))
            dispatch { cancelled() }
            Thread.sleep(650)
            assertColor("Interrupted Dialog spring still restores the full surface", android.graphics.Color.BLUE,
                captureEdge("regrab-cancelled", false))
            assertEquals("Repeated cancellation never dismisses the Dialog", 0, committed.get())

            dispatch { started(NavigationEvent.EDGE_LEFT); progressed(.7f, NavigationEvent.EDGE_LEFT) }
            assertColor("Commit gesture reveals the retained parent", underneath, captureEdge("commit-progress", false))
            dispatch { completed() }
            await("Commit removes the Dialog") { dialogDispatcher.get() == null }
            assertEquals("Commit closes the Dialog exactly once", 1, committed.get())
            assertColor("The retained parent is restored", underneath, captureEdge("committed-parent", false))
        }
    }

    private class DialogBackInput : NavigationEventInput() {
        fun started(edge: Int) = dispatchOnBackStarted(NavigationEvent(swipeEdge = edge, touchY = 600f))
        fun progressed(amount: Float, edge: Int) = dispatchOnBackProgressed(
            NavigationEvent(swipeEdge = edge, progress = amount, touchY = 600f))
        fun cancelled() = dispatchOnBackCancelled()
        fun completed() = dispatchOnBackCompleted()
    }

    @Test fun gestureProgressCancelAndCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        val committed = AtomicInteger()
        val motion = AtomicReference<WorkbenchBackMotion>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val back = rememberWorkbenchBackMotion { committed.incrementAndGet() }
                    SideEffect { motion.set(back) }
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        Box(Modifier.fillMaxSize().then(back.modifier).background(Color.Blue))
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            fun event(progress: Float, edge: Int) = BackEventCompat(0f, 600f, progress, edge)
            fun pixel(right: Boolean): Int {
                Thread.sleep(150)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val color = bitmap.getPixel(if (right) bitmap.width - 8 else 8, bitmap.height / 2)
                bitmap.recycle()
                return color
            }
            for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
                val right = edge == BackEventCompat.EDGE_RIGHT
                assertColor("Initial page covers background", android.graphics.Color.BLUE, pixel(right))
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, edge)) }
                Thread.sleep(80)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.08f, edge)) }
                assertColor("Page must move before gesture completion", android.graphics.Color.RED, pixel(right))
                assertEquals("Progress must not navigate", 0, committed.get())
                val recording = AtomicBoolean(true)
                val frames = java.util.Collections.synchronizedList(mutableListOf<Float>())
                instrumentation.runOnMainSync {
                    val choreographer = Choreographer.getInstance()
                    val sampler = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            frames += motion.get().progress.value
                            if (recording.get()) choreographer.postFrameCallback(this)
                        }
                    }
                    frames += motion.get().progress.value
                    choreographer.postFrameCallback(sampler)
                }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                Thread.sleep(600)
                recording.set(false)
                val sampled = synchronized(frames) { frames.toList() }
                assertTrue("Cancellation records rendered frames rather than only its final state", sampled.size > 3)
                assertTrue("Cancellation starts at the visible drag displacement", sampled.first() > .01f)
                sampled.zipWithNext().forEach { (before, after) ->
                    assertTrue("Return must not jump outward for a frame: $sampled", after <= before + .0005f)
                }
                assertEquals("Return settles completely", 0f, sampled.last(), .0005f)
                assertColor("Cancelled gesture restores page", android.graphics.Color.BLUE, pixel(right))
                assertEquals(0, committed.get())
            }
            // Re-grab the page while cancellation is still settling. Navigation must stay idle.
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.65f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            Thread.sleep(30)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.2f, BackEventCompat.EDGE_LEFT)) }
            assertColor("Re-grab must keep page displaced", android.graphics.Color.RED, pixel(false))
            assertEquals(0, committed.get())
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            Thread.sleep(600)
            assertColor("Interrupted spring still restores page", android.graphics.Color.BLUE, pixel(false))
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.7f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            val exitFrames = java.util.Collections.synchronizedList(mutableListOf<Float>())
            val sampleExit = AtomicBoolean(true)
            val exitAtClose = AtomicReference<Float>()
            instrumentation.runOnMainSync {
                val clock = Choreographer.getInstance()
                clock.postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        exitFrames += motion.get().exitProgress.value
                        if (committed.get() > 0 && exitAtClose.get() == null) exitAtClose.set(motion.get().exitProgress.value)
                        if (sampleExit.get()) clock.postFrameCallback(this)
                    }
                })
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            // A second completed Back must be consumed while the first exit is finishing.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(450)
            sampleExit.set(false)
            assertEquals("Committed gesture navigates exactly once", 1, committed.get())
            val finishedFrames = synchronized(exitFrames) { exitFrames.toList() }
            assertTrue("Commit renders intermediate exit frames before removing the page", finishedFrames.any { it > 0f && it < 1f })
            finishedFrames.zipWithNext().forEach { (before, after) ->
                assertTrue("Exit cannot snap backwards: $finishedFrames", after >= before)
            }
            assertEquals("Navigation only happens after the visible exit finishes", 1f, requireNotNull(exitAtClose.get()), .0005f)
            assertColor("Completed exit fully reveals the retained page", android.graphics.Color.RED, pixel(false))
        }
    }

    @Test fun guardedBackRestoresSurfaceBeforeRequestingConfirmation() = guardedBack(false)

    @Test fun guardBecomingBusyDuringExitRestoresTheRetainedPage() = guardedBack(true)

    private fun guardedBack(becomesBusyDuringExit: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        val requested = AtomicInteger()
        val motion = AtomicReference<WorkbenchBackMotion>()
        val progressAtRequest = AtomicReference<Float>()
        val allowExit = mutableStateOf(becomesBusyDuringExit)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val back = rememberWorkbenchBackMotion(animateExit = allowExit.value) {
                        progressAtRequest.set(motion.get().progress.value)
                        requested.incrementAndGet()
                    }
                    SideEffect { motion.set(back) }
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        Box(Modifier.fillMaxSize().then(back.modifier).background(Color.Blue))
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(60)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 600f, .6f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            if (becomesBusyDuringExit) {
                Thread.sleep(60)
                scenario.onActivity { allowExit.value = false }
            }
            Thread.sleep(650)
            assertEquals("Only one confirmation request", 1, requested.get())
            assertEquals("Dirty surface must be restored before opening confirmation", 0f, requireNotNull(progressAtRequest.get()), .0005f)
            assertEquals("Guarded destination never plays an outgoing fade", 0f, motion.get().exitProgress.value, .0005f)
            assertFalse("A retained editor must remain interactive after rejecting Back", motion.get().exiting.value)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { assertColor("Draft remains visible", android.graphics.Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) }
            finally { bitmap.recycle() }
        }
    }
}

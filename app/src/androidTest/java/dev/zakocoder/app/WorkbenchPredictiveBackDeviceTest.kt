package dev.zakocoder.app

import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ui.rememberWorkbenchBackMotion
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Dispatches real AndroidX back events, including cancellation, without touching user data. */
class WorkbenchPredictiveBackDeviceTest {
    @Test fun gestureProgressCancelAndCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        val committed = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    val back = rememberWorkbenchBackMotion { committed.incrementAndGet() }
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
                assertEquals("Initial page covers background", android.graphics.Color.BLUE, pixel(right))
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, edge)) }
                Thread.sleep(80)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.08f, edge)) }
                assertEquals("Page must move before gesture completion", android.graphics.Color.RED, pixel(right))
                assertEquals("Progress must not navigate", 0, committed.get())
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                Thread.sleep(600)
                assertEquals("Cancelled gesture restores page", android.graphics.Color.BLUE, pixel(right))
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
            assertEquals("Re-grab must keep page displaced", android.graphics.Color.RED, pixel(false))
            assertEquals(0, committed.get())
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            Thread.sleep(600)
            assertEquals("Interrupted spring still restores page", android.graphics.Color.BLUE, pixel(false))
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(event(0f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackProgressed(event(.7f, BackEventCompat.EDGE_LEFT)) }
            Thread.sleep(80)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(200)
            assertEquals("Committed gesture navigates exactly once", 1, committed.get())
        }
    }
}

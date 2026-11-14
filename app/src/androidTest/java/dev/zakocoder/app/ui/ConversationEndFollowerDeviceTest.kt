package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ConversationEndFollowerDeviceTest {
    @Test fun wrappingLinesMoveContinuouslyAndPausedHistoryStaysPut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        warmDeviceActivity()
        for (dark in listOf(false, true)) {
            val source = mutableStateOf((1..25).joinToString("\n") { "Output line $it" })
            val follow = mutableStateOf(true)
            val frames = CopyOnWriteArrayList<Int>()
            val list = AtomicReference<androidx.compose.foundation.lazy.LazyListState>()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    WorkbenchMiuixTheme(dark) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                val state = rememberLazyListState()
                                SideEffect { list.set(state) }
                                ConversationEndFollower(state, "fixture", follow.value)
                                LaunchedEffect(state) {
                                    while (isActive) {
                                        withFrameNanos { frames.add(state.firstVisibleItemScrollOffset) }
                                    }
                                }
                                LazyColumn(Modifier.height(260.dp).fillMaxWidth(), state = state,
                                    contentPadding = PaddingValues(bottom = 12.dp)) {
                                    item { ArrivalText(source.value, fontSize = 12.sp, lineHeight = 18.sp, animated = true) }
                                    item { Spacer(Modifier.height(with(LocalDensity.current) { 36.sp.toDp() })) }
                                }
                            }
                        }
                    }
                } }
                Thread.sleep(800)
                frames.clear()
                val before = list.get().firstVisibleItemScrollOffset
                instrumentation.runOnMainSync { source.value += "\n" + "这一段持续输出的文字会在末尾自动换行，已有内容应该平顺向上移动。".repeat(3) }
                Thread.sleep(80)
                saveScreenshot(if (dark) "night-moving" else "day-moving")
                Thread.sleep(850)
                val after = list.get().firstVisibleItemScrollOffset
                val samples = listOf(before) + frames.toList() + after
                val total = after - before
                assertTrue("New lines must move history upward", total > 0)
                assertTrue("Wrapping must have multiple intermediate positions", samples.filter { it > before && it < after }.distinct().size >= 4)
                val largestStep = samples.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
                assertTrue("No one-frame jump to the new line: $largestStep/$total", largestStep < total * .7f)
                val report = File(instrumentation.targetContext.cacheDir,
                    "ui-review/line-follow/${if (dark) "night" else "day"}-metrics.txt")
                report.parentFile?.mkdirs()
                report.writeText("totalPx=$total\nlargestFrameStepPx=$largestStep\nintermediatePositions=${samples.filter { it > before && it < after }.distinct().size}\n")
                assertFalse("Follower must settle at the measured end", list.get().canScrollForward)
                saveScreenshot(if (dark) "night-settled" else "day-settled")
                instrumentation.runOnMainSync { follow.value = false }
                Thread.sleep(80)
                instrumentation.runOnMainSync { source.value += "\nOutput while reading history" }
                Thread.sleep(400)
                assertEquals("Paused following must not move reading position", after, list.get().firstVisibleItemScrollOffset)
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(instrumentation.targetContext.cacheDir, "ui-review/line-follow/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

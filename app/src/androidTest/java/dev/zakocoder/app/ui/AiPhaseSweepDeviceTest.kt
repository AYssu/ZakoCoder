package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Component stream fixture. No API request, command execution or saved project modification. */
class AiPhaseSweepDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 12000
        while (!condition()) {
            if (android.os.SystemClock.uptimeMillis() > end) throw AssertionError(label)
            Thread.sleep(40)
        }
    }
    private fun text(value: String) = nodes { all -> all.any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == value } == true
    } }
    @Test fun movingGlyphShadingKeepsTextAndBoundsInBothThemes() {
        val dark = mutableStateOf(false)
        val phase = mutableStateOf(AiPhase.Requesting)
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalWorkbenchDark provides dark.value) {
                    MaterialTheme(colorScheme = if (dark.value) WorkbenchDarkColors else WorkbenchColors) {
                        Surface { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                            AiConversationProgress(AiState(active = true, phase = phase.value), {})
                        } }
                    }
                }
            } }
            for (night in listOf(false, true)) for (status in listOf(AiPhase.Requesting, AiPhase.Receiving)) {
                instrumentation.runOnMainSync { dark.value = night; phase.value = status }
                val label = context.getString(status.label)
                await("Readable phase text") { text(label) }
                Thread.sleep(120)
                val bounds = nodes { all -> all.first {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == label } == true
                }.boundsInWindow }
                fun crop(): Bitmap {
                    val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    return Bitmap.createBitmap(screen, bounds.left.toInt(), bounds.top.toInt(),
                        bounds.width.toInt(), bounds.height.toInt()).also { screen.recycle() }
                }
                val first = crop()
                val pixels = IntArray(first.width * first.height)
                first.getPixels(pixels, 0, first.width, 0, 0, first.width, first.height)
                var changed = false
                repeat(5) {
                    Thread.sleep(130)
                    val next = crop()
                    val other = IntArray(pixels.size)
                    next.getPixels(other, 0, next.width, 0, 0, next.width, next.height)
                    if (!pixels.contentEquals(other)) changed = true
                    next.recycle()
                }
                first.recycle()
                assertTrue("Glyph shading changes: $night $status", changed)
                val after = nodes { all -> all.first {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == label } == true
                }.boundsInWindow }
                assertEquals("Text never moves", bounds, after)
                val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val folder = File(context.cacheDir, "ui-phase-sweep").apply { mkdirs() }
                File(folder, "${if (night) "night" else "day"}-${status.name}.png").outputStream().use {
                    screen.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screen.recycle()
            }
        }
    }
}

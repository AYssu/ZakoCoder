package dev.zakocoder.app.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Isolated native content: no provider config, network, project files or conversation history. */
class AiConversationUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun foreground() = warmDeviceActivity()

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val nodes = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { nodes += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(nodes))
        }
        return result.get()
    }

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(70)
        }
    }

    private fun clickTextOffset(marker: String) {
        var point: Offset? = null
        nodes { list ->
            for (node in list) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                val layout = layouts.firstOrNull { it.layoutInput.text.text.contains(marker) } ?: continue
                val index = layout.layoutInput.text.text.indexOf(marker)
                val origin = node.layoutInfo.coordinates.localToWindow(Offset.Zero)
                point = layout.getBoundingBox(index).center + origin
                break
            }
        }
        val target = requireNotNull(point) { "No laid out reference: $marker" }
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, target.x, target.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        MotionEvent.obtain(start, start + 80, MotionEvent.ACTION_UP, target.x, target.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    @Test(timeout = 90000) fun userMessageShowsCopyOnlyAfterTap() {
        val message = "copy fixture message"
        val copyLabel = context.getString(R.string.ai_copy_user)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp)) {
                        AiMessage(AiEntry("copy-fixture", message, role = AiRole.User, turn = 1),
                            projectId = "fixture", onProjectPath = {}, onLink = {})
                    }
                } }
            } }
            await("message shown") { nodes { list -> list.any { text(it) == message } } }
            assertFalse(nodes { list -> list.any { text(it) == copyLabel } })
            clickTextOffset(message)
            await("copy appears after tap") { nodes { list -> list.any { text(it) == copyLabel } } }
            nodes { list ->
                val item = list.first { text(it) == copyLabel }
                var action: SemanticsNode? = item
                while (action != null && action.config.getOrNull(SemanticsActions.OnClick) == null) action = action.parent
                val density = context.resources.displayMetrics.density
                val bounds = requireNotNull(action).boundsInWindow
                assertTrue("Copy action stays compact", bounds.width <= 128 * density)
                assertTrue("Copy action retains a 48dp touch target", bounds.height >= 48 * density - 1)
                assertTrue("Copy action avoids menu vertical padding", bounds.height <= 56 * density)
                assertTrue(requireNotNull(action).config[SemanticsActions.OnClick].action!!.invoke())
            }
            await("menu dismissed") { nodes { list -> list.none { text(it) == copyLabel } } }
            instrumentation.runOnMainSync {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                assertEquals(message, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        }
    }

    @Test(timeout = 90000) fun plainInlineAndMarkdownPathsDeliverProjectRelativeCallbacks() {
        val opened = AtomicReference<String?>()
        var source by mutableStateOf("文件 app/build/outputs/apk/debug/app-debug.apk")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp)) {
                        AiMarkdown(source, projectId = "fixture", onOpenProjectPath = { path -> opened.set(path) }, onLink = { error("No external link expected") })
                    }
                } }
            } }
            val cases = listOf(
                Triple(source, "app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/debug/app-debug.apk"),
                Triple("文件 `src/Main.kt:24`", "src/Main.kt:24", "src/Main.kt"),
                Triple("[打开文件](/workspace/fixture/src/Other.kt)", "打开文件", "src/Other.kt"),
            )
            cases.forEach { (markdown, label, expected) ->
                opened.set(null)
                instrumentation.runOnMainSync { source = markdown }
                await("reference laid out: $label") { nodes { list -> list.any { text(it).contains(label) } } }
                clickTextOffset(label)
                await("path callback: $expected") { opened.get() == expected }
            }
        }
    }

    @Test(timeout = 90000) fun longTableSharesHorizontalScrollAndLongListKeepsLayout() {
        val longCell = "超长内容".repeat(28)
        val markdown = "| 第一列 | 第二列 | 第三列 | 第四列 |\n| --- | --- | --- | --- |\n| 短内容 | $longCell | 第三格 | 末列内容 |\n| 后续行 | 对齐内容 | 第三格二 | 末列二 |\n\n" +
            (1000..1003).joinToString("\n") { "$it. ${"长列表内容".repeat(18)}" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { AiMarkdown(markdown) {} }
                } }
            } }
            await("long table parsed") { nodes { list -> list.any { text(it) == longCell } } }
            nodes { list ->
                val first = list.first { text(it) == "短内容" }.boundsInRoot
                val long = list.first { text(it) == longCell }.boundsInRoot
                val next = list.first { text(it) == "后续行" }.boundsInRoot
                assertEquals("Cells in a row start together", first.top, long.top, 1f)
                assertTrue("Next row starts below the longest cell", next.top >= long.bottom)
                assertTrue("Four digit list markers retain their width", list.any { text(it) == "1000." && it.boundsInRoot.width > 0 })
                val horizontal = list.filter { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
                assertEquals("One scroll container for the entire table", 1, horizontal.size)
                val table = horizontal.single()
                val range = table.config[SemanticsProperties.HorizontalScrollAxisRange]
                assertTrue("Table exceeds the narrow screen", range.maxValue() > 0)
                assertTrue(table.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(10000f, 0f) == true)
            }
            await("table horizontal movement") { nodes { list -> list.any {
                (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() ?: 0f) > 0f
            } } }
        }
    }

    @Test(timeout = 90000) fun footerKeepsUnknownUsageHonestAndShowsKnownTotalsAndDuration() {
        var state by mutableStateOf(AiState(turn = 1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp)) { AiTurnFooter(state, 1) }
                } }
            } }
            val unknown = context.getString(R.string.ai_usage_total, "Σ", "—")
            await("unknown total is not fabricated") { nodes { list -> list.any { text(it) == unknown } } }
            instrumentation.runOnMainSync {
                state = state.copy(requests = listOf(AiRequestLog(turn = 1, elapsed = 2000, firstText = 1000, inputTokens = 100, outputTokens = 25, complete = true)),
                    turnTimings = listOf(AiTurnTiming(1, started = 1000, finished = 65000)))
            }
            val total = context.getString(R.string.ai_usage_total, "Σ", "125")
            val input = context.getString(R.string.ai_usage_input, "↑", "100")
            val output = context.getString(R.string.ai_usage_output, "↓", "25")
            await("animated usage reaches actual totals") { nodes { list -> listOf(total, input, output).all { expected -> list.any { text(it) == expected } } } }
            await("completed task duration shown") { nodes { list -> list.any { text(it).contains("1m 4s") } } }
            assertFalse(nodes { list -> list.any { text(it) == unknown } })
        }
    }
}

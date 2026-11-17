package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Synthetic historical requests only: never reads a provider, credentials or user's conversations. */
@SdkSuppress(minSdkVersion = 30)
class AiTurnUsageFooterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    @Test(timeout = 150000) fun responsiveFieldsStayOnOneLineAndKeepBothEdgesInBothThemes() {
        var dark by mutableStateOf(false)
        var width by mutableIntStateOf(360)
        var state by mutableStateOf(knownState())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                FixtureTheme(dark, width) { AiTurnFooter(state, 1) }
            } }
            listOf(false, true).forEach { night ->
                instrumentation.runOnMainSync { dark = night }
                listOf(320, 360, 375, 414, 432, 680, 850, 1010).forEach { contentWidth ->
                    instrumentation.runOnMainSync { width = contentWidth }
                    await("Usage row at $contentWidth") { tag("ai-turn-usage-row") != null &&
                        hasTag("ai-turn-usage-input") &&
                        hasTag("ai-turn-usage-duration") == (contentWidth >= 680) &&
                        hasTag("ai-turn-usage-cache") == (contentWidth >= 850) &&
                        hasTag("ai-turn-usage-summary") == (contentWidth >= 1010) }
                    assertTrue("Phones retain output usage", hasTag("ai-turn-usage-output"))
                    capture("${if (night) "night" else "day"}-$contentWidth")
                    assertSingleLineEdges()
                }
                // Real long values may lower the tier, but may never wrap or push context off-screen.
                instrumentation.runOnMainSync {
                    width = 360
                    state = state.copy(requests = listOf(AiRequestLog(turn = 1, round = 1,
                        inputTokens = 9_876_543_210L, outputTokens = 876_543_210L,
                        estimatedContextTokens = 88_300, contextWindowTokens = 128_000,
                        elapsed = 12_000, firstEvent = 1_000, complete = true)))
                }
                await("Long values remain visible") { textOfTag("ai-turn-usage-total").contains("M") }
                capture("${if (night) "night" else "day"}-long-values")
                assertSingleLineEdges()
                assertEquals("Input/output appear or collapse together", hasTag("ai-turn-usage-input"), hasTag("ai-turn-usage-output"))
                instrumentation.runOnMainSync { state = knownState() }
            }
        }
    }

    @Test(timeout = 150000) fun contextUsesOwnLastNonSummaryRequestAndDetailsKeepUnknownCacheHonest() {
        var dark by mutableStateOf(false)
        var state by mutableStateOf(historicalState())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                FixtureTheme(dark, 360) { AiTurnFooter(state, 1) }
            } }
            listOf(false, true).forEach { night ->
                instrumentation.runOnMainSync { dark = night; state = historicalState() }
                await("Historical context uses this turn's last non-summary request") {
                    contextDescription().let { "88300" in it && "128000" in it && "69%" in it }
                }
                assertFalse("Context is not cumulative billing usage", "242770" in contextDescription())
                assertFalse("Next turn and summary must not replace historical capacity", "1000000" in contextDescription())
                clickTag("ai-turn-usage-context")
                await("Anchored usage details opens") { hasTag("ai-turn-usage-details") }
                assertEquals("Missing cache report must remain unknown", "—", cacheValue())
                assertTrue(textOfTag("ai-turn-usage-detail-context").contains("88.3K"))
                capture("${if (night) "night" else "day"}-details-unknown-cache")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("System back dismisses details") { !hasTag("ai-turn-usage-details") }
                instrumentation.runOnMainSync {
                    state = state.copy(requests = listOf(AiRequestLog(turn = 1, round = 1,
                        inputTokens = 169_400, outputTokens = 1_000, cachedTokens = 0,
                        estimatedContextTokens = 169_400, contextWindowTokens = 128_000,
                        elapsed = 5_000, firstEvent = 100, complete = true)))
                }
                await("Over-capacity context keeps the actual percentage") { "132%" in contextDescription() }
                capture("${if (night) "night" else "day"}-over-capacity-row")
                assertSingleLineEdges()
                assertContextHasWarningColor()
                clickTag("ai-turn-usage-context")
                await("Over-capacity details opens") { hasTag("ai-turn-usage-details") }
                assertTrue("Details explains the exceeded budget", textOfTag("ai-turn-usage-detail-context").contains("超出"))
                assertTrue("Reported zero cache is distinct from unknown", cacheValue().contains("0"))
                capture("${if (night) "night" else "day"}-details-over-capacity")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Details closes without changing conversation") { !hasTag("ai-turn-usage-details") }
            }
        }
    }

    private fun knownState() = AiState(turn = 1, phase = AiPhase.Completed,
        requests = listOf(AiRequestLog(turn = 1, round = 1, inputTokens = 83_120, outputTokens = 5_220,
            cachedTokens = 41_200, estimatedContextTokens = 88_300, contextWindowTokens = 128_000,
            elapsed = 22_000, firstEvent = 1_000, complete = true)),
        turnTimings = listOf(AiTurnTiming(1, 1_793_376_000_000, 1_793_376_578_000)))

    private fun historicalState() = knownState().copy(turn = 2, requests = listOf(
        AiRequestLog(turn = 1, round = 1, inputTokens = 141_750, outputTokens = 7_500,
            cachedTokens = 41_200, estimatedContextTokens = 141_750, contextWindowTokens = 128_000,
            elapsed = 22_000, firstEvent = 1_000, complete = true),
        AiRequestLog(turn = 1, round = 2, inputTokens = 88_300, outputTokens = 5_220,
            estimatedContextTokens = 88_300, contextWindowTokens = 128_000,
            elapsed = 22_000, firstEvent = 1_000, complete = true),
        AiRequestLog(turn = 1, round = 3, inputTokens = 4_000, outputTokens = 500,
            estimatedContextTokens = 4_000, contextWindowTokens = 64_000, compaction = true,
            elapsed = 2_000, complete = true),
        AiRequestLog(turn = 2, round = 1, inputTokens = 500_000, outputTokens = 20_000,
            estimatedContextTokens = 500_000, contextWindowTokens = 1_000_000,
            elapsed = 30_000, complete = true)))

    @Composable private fun FixtureTheme(dark: Boolean, contentWidth: Int, content: @Composable () -> Unit) {
        val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val pixels = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
            LocalDensity provides Density(pixels / (contentWidth + 33f), 1f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp)) {
                        Text("已完成项目检查。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        content()
                    }
                }
            }
        }
    }

    private fun assertSingleLineEdges() = nodes { all ->
        val row = requireNotNull(all.findTag("ai-turn-usage-row"))
        val total = requireNotNull(all.findTag("ai-turn-usage-total"))
        val leftmost = all.findTag("ai-turn-usage-input") ?: total
        val ctx = requireNotNull(all.findTag("ai-turn-usage-context"))
        val epsilon = row.boundsInWindow.width / 360 * 2
        assertEquals("Totals remain left aligned", row.boundsInWindow.left, leftmost.boundsInWindow.left, epsilon)
        assertEquals("Context remains right aligned", row.boundsInWindow.right, ctx.boundsInWindow.right, epsilon)
        assertTrue("Both clusters remain on the same line", abs(total.boundsInWindow.center.y - ctx.boundsInWindow.center.y) < row.boundsInWindow.height / 2)
        fun check(node: SemanticsNode, inheritedTag: String = "row") {
            val field = node.config.getOrNull(SemanticsProperties.TestTag) ?: inheritedTag
            assertNull("Usage row never becomes horizontally scrollable", node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { layout ->
                val diagnostic = "field=$field text=${layout.layoutInput.text.text} " +
                    "layout=${layout.size} bounds=${node.boundsInWindow} " +
                    "constraints=${layout.layoutInput.constraints} lineCount=${layout.lineCount} " +
                    "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                    "lineTop=${layout.getLineTop(0)} lineBottom=${layout.getLineBottom(0)} " +
                    "lineRight=${layout.getLineRight(0)}"
                assertEquals("Each visible field occupies one line: $diagnostic", 1, layout.lineCount)
                assertFalse("Field glyphs must not be clipped vertically: $diagnostic", layout.didOverflowHeight)
                // Native paragraph advances can exceed their rounded IntSize by a subpixel.
                // Permit only that raster rounding; actual clipping still fails this bound.
                assertTrue("Field text exceeds its measured width: $diagnostic",
                    layout.getLineRight(0) <= layout.size.width + 1f)
            }
            node.children.forEach { check(it, field) }
        }
        check(row)
    }

    private fun assertContextHasWarningColor() {
        instrumentation.waitForIdleSync()
        Thread.sleep(180)
        val bounds = requireNotNull(tag("ai-turn-usage-context")).boundsInWindow
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var warningPixels = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height))
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width)) {
                    val color = image.getPixel(x, y)
                    if (Color.red(color) > Color.green(color) * 1.3 && Color.red(color) > Color.blue(color) * 1.2) warningPixels++
                }
            assertTrue("Over-capacity ring and percentage use a red warning tint", warningPixels > 5)
        } finally { image.recycle() }
    }
    private fun contextDescription() = nodes { all -> all.findTag("ai-turn-usage-context")?.let {
        it.config.getOrNull(SemanticsProperties.StateDescription).orEmpty() + " " +
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    }.orEmpty() }
    private fun cacheValue() = nodes { all ->
        fun values(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::values)
        values(requireNotNull(all.findTag("ai-turn-usage-detail-cache"))).last().trim()
    }
    private fun textOfTag(name: String) = nodes { all -> all.findTag(name)?.let(::descendantText).orEmpty().trim() }
    private fun descendantText(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } +
            node.children.joinToString(" ") { descendantText(it) }
    private fun List<SemanticsNode>.findTag(name: String) = firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == name }
    private fun tag(name: String) = nodes { it.findTag(name) }
    private fun hasTag(name: String) = tag(name) != null
    private fun clickTag(name: String) = nodes { all ->
        var node = requireNotNull(all.findTag(name))
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
        assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 15_000
        while (!predicate()) { check(SystemClock.uptimeMillis() < limit) { label }; Thread.sleep(60) }
    }
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
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(180)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val target = File(context.cacheDir, "ui-review/turn-usage-footer/$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

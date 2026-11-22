package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiModelContextWindows
import dev.zakocoder.app.ai.AiParameters
import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real components with memory-only drafts; no providers, credentials, or user data. */
@SdkSuppress(minSdkVersion = 30)
class ContextBudgetUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun dayDefaultFont() = render(false)
    @Test fun nightDefaultFont() = render(true)

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
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { label }
            Thread.sleep(50)
        }
    }
    private fun has(label: String) = nodes { list -> list.any { text(it) == label } }
    private fun editableNode(all: List<SemanticsNode>, label: String): SemanticsNode? = all.lastOrNull { node ->
        node.config.getOrNull(SemanticsActions.SetText) != null &&
            generateSequence(node) { it.parent }.any { parent ->
                label in parent.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
    }
    private fun actionNode(all: List<SemanticsNode>, label: String): SemanticsNode {
        var node = all.last { text(it) == label } as SemanticsNode?
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        return requireNotNull(node)
    }
    private fun toggleNode(all: List<SemanticsNode>, label: String): SemanticsNode? {
        fun toggles(node: SemanticsNode): List<SemanticsNode> = buildList {
            if (node.config.getOrNull(SemanticsProperties.ToggleableState) != null) add(node)
            node.children.forEach { addAll(toggles(it)) }
        }
        return all.asSequence().filter { text(it) == label ||
            label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.firstNotNullOfOrNull { title ->
            generateSequence(title) { it.parent }.firstNotNullOfOrNull { toggles(it).singleOrNull() }
        }
    }
    private fun click(label: String) {
        await(label) { has(label) }
        nodes { assertTrue(actionNode(it, label).config[SemanticsActions.OnClick].action?.invoke() == true) }
    }

    private fun reveal(label: String) {
        await("composed: $label") { has(label) }
        repeat(80) {
            val visible = nodes { all ->
                // The whole clickable row must fit: its label alone can be visible while
                // the model ID, exact capacity or protocol hint is below the viewport.
                val node = actionNode(all, label)
                var root = node
                while (root.parent != null) root = requireNotNull(root.parent)
                var scroll = node.parent
                while (scroll != null && scroll.config.getOrNull(SemanticsActions.ScrollBy) == null) scroll = scroll.parent
                val container = requireNotNull(scroll)
                val y = node.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                val rootTop = root.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                val viewportTop = container.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                val top = maxOf(rootTop + 80, viewportTop)
                val bottom = minOf(rootTop + root.layoutInfo.coordinates.size.height - 40,
                    viewportTop + container.layoutInfo.coordinates.size.height)
                if (y >= top && y + node.layoutInfo.coordinates.size.height <= bottom) {
                    assertTrue("complete preset row visible: $label", node.layoutInfo.coordinates.size.height > 0)
                    true
                } else {
                    container.config[SemanticsActions.ScrollBy].action?.invoke(0f, if (y < top) -400f else 400f)
                    false
                }
            }
            if (visible) return
            Thread.sleep(100)
        }
        error("not visible after scrolling: $label")
    }

    private fun render(dark: Boolean) {
        val title = context.getString(R.string.ai_options_context)
        val draft = ModelOptionsDraft(null).apply { seed = "42"; frequency = "0.25" }
        val before = draft.parameters(AiParameters())
        val diagnostics = mutableStateOf(false)
        val request = AiRequestLog(id = "budget-review", turn = 1, round = 1,
            model = "review-model", origin = "https://example.invalid", elapsed = 1250,
            status = 200, complete = true, finish = "stop", contextChars = 18600,
            requestBytes = 20800, events = 48, textChars = 1900, bytes = 2400,
            inputTokens = 6240, outputTokens = 488, cachedTokens = 2048,
            estimatedContextTokens = 6400, contextWindowTokens = 1000000, contextOutputReserveTokens = 8192)
        val diagnosticsCommitted = AtomicBoolean()
        val changed = AtomicInteger()
        val committed = AtomicBoolean()
        val renderedTokens = AtomicReference("")
        val renderedAutomatic = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = LocalConfiguration.current
                val deviceDensity = LocalDensity.current.density
                val configuration = remember(current, dark) { Configuration(current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                } }
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
                    LocalDensity provides Density(deviceDensity, 1f)) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize()) {
                            if (diagnostics.value) AiRequestsPanel(AiState(conversationId = "budget-review", requests = listOf(request)), Modifier.fillMaxSize())
                            else WorkbenchSheet(title, {}) {
                                ContextBudgetOptions(draft, true) { changed.incrementAndGet() }
                            }
                        }
                    }
                }
                val tokens = draft.windowTokens
                val automatic = draft.automatic
                val showingDiagnostics = diagnostics.value
                SideEffect { renderedTokens.set(tokens); renderedAutomatic.set(automatic); committed.set(true); diagnosticsCommitted.set(showingDiagnostics) }
            } }
            await("initial composition committed") { committed.get() && renderedTokens.get() == "1000000" }
            assertEquals(1_000_000, draft.context().contextWindowTokens)
            assertEquals(90, draft.context().compressionThresholdPercent)
            capture(dark, "window-panel")
            val references = AiModelContextWindows.referenceWindows()
            assertEquals("every reference model has a selectable row", references,
                contextWindowPresets.associate { it.modelId to it.tokens })
            assertEquals("no duplicate model rows", references.size, contextWindowPresets.size)
            assertEquals("all provider groups are available", 9, contextWindowPresets.map { it.provider }.distinct().size)
            // Do not merely count the backing list: every ID must be present in the real
            // composition, then recent models across all nine providers are scrolled into
            // view, captured and selected through the actual row click action.
            val modelsToSelect = listOf(
                "gpt-6.2-sol", "deepseek-v4-pro", "gemini-4-carbon",
                "claude-fable-5-2", "claude-sonnet-4-5-20250929",
                "grok-4.7", "glm-5.3", "qwen4-max",
                "minimax-m3.1-flash", "kimi-k3.1-pro", "kimi-k2.7-code-highspeed",
            )
            for (modelId in modelsToSelect) {
                click(context.getString(R.string.ai_context_choose_model_window))
                await("picker shows the current catalogue size") {
                    has(context.getString(R.string.ai_context_reference_count, references.size, 9))
                }
                await("all reference models composed") {
                    nodes { all -> references.keys.all { id -> all.any { text(it) == id } } }
                }
                nodes { all ->
                    assertEquals("real picker contains every reference ID once", references.keys,
                        all.map(::text).filter { it in references }.toSet())
                    references.keys.forEach { id ->
                        assertEquals("one visible composition row for $id", 1, all.count { text(it) == id })
                    }
                    contextWindowPresets.map { it.provider }.distinct().forEach { provider ->
                        assertTrue("provider heading ${context.getString(provider)}", all.any { text(it) == context.getString(provider) })
                    }
                }
                reveal(modelId)
                capture(dark, "model-presets-$modelId")
                click(modelId)
                val expectedTokens = requireNotNull(references[modelId])
                await("$modelId capacity committed and picker dismissed") {
                    renderedTokens.get() == expectedTokens.toString() && !has(modelId)
                }
                assertEquals("selected capacity for $modelId", expectedTokens, draft.context().contextWindowTokens)
                assertEquals("preset selection preserves unrelated parameters", before, draft.parameters(AiParameters()))
            }
            val inputLabel = context.getString(R.string.ai_options_context_tokens)
            // The Miuix-backed field intentionally hides its visual label from Text
            // semantics; find its real SetText action by the accessible description.
            await("main panel editable budget restored") { nodes { editableNode(it, inputLabel) != null } }
            nodes { assertTrue(requireNotNull(editableNode(it, inputLabel)).config[SemanticsActions.SetText].action?.invoke(AnnotatedString("1000000")) == true) }
            await("custom window composition committed") { renderedTokens.get() == "1000000" }
            assertEquals(1000000, draft.context().contextWindowTokens)
            val beforeToggle = changed.get()
            val automaticLabel = context.getString(R.string.ai_options_auto)
            await("automatic compression switch restored") { nodes { toggleNode(it, automaticLabel) != null } }
            fun toggle() = nodes { list ->
                val toggle = requireNotNull(toggleNode(list, automaticLabel))
                val control = requireNotNull(generateSequence(toggle) { it.parent }.firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick) != null
                })
                assertTrue(control.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            toggle()
            await("automatic off committed") { !renderedAutomatic.get() }
            assertFalse(draft.context().autoCompress)
            toggle()
            await("automatic on committed") { renderedAutomatic.get() }
            assertTrue(draft.context().autoCompress)
            assertEquals(beforeToggle + 2, changed.get())
            assertEquals(before, draft.parameters(AiParameters()))
            instrumentation.runOnMainSync { diagnostics.value = true }
            val requestRow = context.getString(R.string.ai_request_row, 1, 1)
            await("diagnostics composition committed") { diagnosticsCommitted.get() && has(requestRow) }
            click(requestRow)
            val budget = context.getString(R.string.ai_context_estimated_request, tokenWindowLabel(6400), tokenWindowLabel(1000000), tokenWindowLabel(8192))
            val usage = context.getString(R.string.ai_request_tokens, "6240", "488", "2048")
            await("request budget and actual usage composed") { has(budget) && has(usage) }
            // Scroll the real details container so the budget and provider usage share a frame.
            nodes { all ->
                var node = all.last { text(it) == usage } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.ScrollBy) == null) node = node.parent
                assertTrue(requireNotNull(node).config[SemanticsActions.ScrollBy].action?.invoke(0f, 140f) == true)
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            nodes { all ->
                for (label in listOf(budget, usage)) {
                    val node = all.last { text(it) == label }
                    var root = node
                    while (root.parent != null) root = requireNotNull(root.parent)
                    val y = node.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                    val rootY = root.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                    assertTrue("budget/usage visible in screenshot", y >= rootY && y + node.layoutInfo.coordinates.size.height <= rootY + root.layoutInfo.coordinates.size.height)
                }
            }
            capture(dark, "request-budget")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        var previous = emptyList<Pair<String, Int>>()
        var stable = 0
        val deadline = SystemClock.uptimeMillis() + 5000
        while (stable < 3 && SystemClock.uptimeMillis() < deadline) {
            val geometry = nodes { all -> all.filter { text(it).isNotBlank() }.map { text(it) to it.layoutInfo.coordinates.localToWindow(Offset.Zero).y.toInt() } }
            stable = if (geometry == previous) stable + 1 else 0
            previous = geometry
            Thread.sleep(80)
        }
        assertTrue("sheet settled: $name", stable >= 3)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/context-budget/${if (dark) "night" else "day"}-$name.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

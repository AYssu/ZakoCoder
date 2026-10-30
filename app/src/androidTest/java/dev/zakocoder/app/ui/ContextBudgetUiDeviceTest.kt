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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiParameters
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
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)

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
    private fun actionNode(all: List<SemanticsNode>, label: String, editable: Boolean = false): SemanticsNode {
        var node = all.last { text(it) == label } as SemanticsNode?
        while (node != null && if (editable) node.config.getOrNull(SemanticsActions.SetText) == null else node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        return requireNotNull(node)
    }
    private fun click(label: String) {
        await(label) { has(label) }
        nodes { assertTrue(actionNode(it, label).config[SemanticsActions.OnClick].action?.invoke() == true) }
    }

    private fun render(dark: Boolean) {
        val title = context.getString(R.string.ai_options_context)
        val draft = ModelOptionsDraft(null).apply { seed = "42"; frequency = "0.25" }
        val before = draft.parameters(AiParameters())
        val changed = AtomicInteger()
        val committed = AtomicBoolean()
        val renderedTokens = AtomicReference("")
        val renderedAutomatic = AtomicBoolean()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = LocalConfiguration.current
                val widthPx = LocalWindowInfo.current.containerSize.width
                val configuration = remember(current, dark) { Configuration(current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                } }
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
                    LocalDensity provides Density(widthPx.coerceAtLeast(1) / 360f, 1.3f)) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize()) {
                            WorkbenchSheet(title, {}) {
                                ContextBudgetOptions(draft, true) { changed.incrementAndGet() }
                            }
                        }
                    }
                }
                val tokens = draft.windowTokens
                val automatic = draft.automatic
                SideEffect { renderedTokens.set(tokens); renderedAutomatic.set(automatic); committed.set(true) }
            } }
            await("initial composition committed") { committed.get() && renderedTokens.get() == "128000" }
            assertEquals(128000, draft.context().contextWindowTokens)
            assertEquals(90, draft.context().compressionThresholdPercent)
            capture(dark, "window-panel")
            click(context.getString(R.string.ai_context_choose_model_window))
            for (preset in contextWindowPresets) await("preset ${preset.tokens}") { has(context.getString(preset.label)) }
            capture(dark, "model-presets")
            click(context.getString(R.string.ai_context_preset_gpt54))
            await("GPT window committed and sheet dismissed") {
                renderedTokens.get() == "1050000" && !has(context.getString(R.string.ai_context_preset_gpt54))
            }
            assertEquals(1050000, draft.context().contextWindowTokens)
            assertEquals(before, draft.parameters(AiParameters()))
            val inputLabel = context.getString(R.string.ai_options_context_tokens)
            nodes { assertTrue(actionNode(it, inputLabel, editable = true).config[SemanticsActions.SetText].action?.invoke(AnnotatedString("1000000")) == true) }
            await("custom window composition committed") { renderedTokens.get() == "1000000" }
            assertEquals(1000000, draft.context().contextWindowTokens)
            val beforeToggle = changed.get()
            fun toggle() = nodes { list ->
                val control = list.last { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
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

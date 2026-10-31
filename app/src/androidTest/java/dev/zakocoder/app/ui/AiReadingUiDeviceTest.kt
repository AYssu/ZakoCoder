package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** The same isolated fixture can be rendered against the base and proposed native components.
 * Pass -e readingReviewStage before on the base tree; no live settings/history are accessed. */
@SdkSuppress(minSdkVersion = 30)
class AiReadingUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val code = (1..72).joinToString("\n") { "val item$it = \"sample-$it\"" } + "\n"
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 120000) fun day360Font130() = render(false)
    @Test(timeout = 120000) fun night360Font130() = render(true)

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
        val until = SystemClock.uptimeMillis() + 15000
        while (!predicate()) { check(SystemClock.uptimeMillis() < until) { label }; Thread.sleep(50) }
    }
    private fun click(label: String) = nodes { all ->
        var node: SemanticsNode? = all.last { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun alignLabel(label: Int) {
        nodes { all ->
            val target = all.last { text(it) == context.getString(label) }
            var container: SemanticsNode? = target
            while (container != null && container.config.getOrNull(SemanticsActions.ScrollBy) == null) container = container.parent
            val scroll = requireNotNull(container)
            val origin = target.layoutInfo.coordinates.localToWindow(Offset.Zero)
            assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f,
                origin.y - scroll.boundsInWindow.top - 24f) == true)
        }
        await("requested section becomes visible: ${context.getString(label)}") { nodes { all -> all.any {
            text(it) == context.getString(label) && it.boundsInWindow.height > 0
        } } }
    }

    private fun render(dark: Boolean) {
        var page by mutableIntStateOf(0)
        var reasoning by mutableStateOf((1..48).joinToString("\n") { "分析步骤 $it：先核对项目现有实现，再修改与验证。" })
        val draft = ModelOptionsDraft(null).apply { frequency = "3"; threshold = "99"; windowTokens = "100" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                val density = context.resources.displayMetrics.widthPixels / 360f
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, 1.3f), LocalWorkbenchDark provides dark,
                    LocalMinimumInteractiveComponentSize provides 48.dp) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                                key(page) { Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    when (page) {
                                        0 -> {
                                            Text("编码回复", style = MaterialTheme.typography.titleLarge)
                                            AiMarkdown("实现已更新，可先检查代码。\n\n```kotlin\n$code```\n\n接下来运行项目测试并核对变更。") {}
                                        }
                                        1 -> AiReasoningContent(AiEntry("reading-reasoning", "", reasoning = reasoning), streaming = true)
                                        else -> {
                                            Text("模型参数", style = MaterialTheme.typography.titleLarge)
                                            ModelOptions(draft, null, editable = true, changed = {})
                                        }
                                    }
                                } }
                            }
                        }
                    }
                }
            } }
            await("code parsed") { nodes { all -> all.any { text(it).contains("val item1") } } }
            capture(dark, "code")
            instrumentation.runOnMainSync { page = 1 }
            await("reasoning shown") { nodes { all -> all.any { text(it).contains("分析步骤") } } }
            Thread.sleep(200)
            nodes { all ->
                val body = all.last { text(it).contains("分析步骤") }
                var container: SemanticsNode? = body
                while (container != null && container.config.getOrNull(SemanticsActions.ScrollBy) == null) container = container.parent
                requireNotNull(container).config[SemanticsActions.ScrollBy].action?.invoke(0f, -600f)
            }
            Thread.sleep(200)
            instrumentation.runOnMainSync { reasoning += "\n后续分析：检查构建命令和执行结果。" }
            Thread.sleep(200)
            capture(dark, "reasoning")
            instrumentation.runOnMainSync { page = 2 }
            await("options header") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_options_title) } } }
            click(context.getString(R.string.ai_options_title))
            await("options expanded") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_options_frequency) } } }
            nodes { all ->
                val fieldLabel = all.last { text(it) == context.getString(R.string.ai_options_frequency) }
                var container: SemanticsNode? = fieldLabel
                while (container != null && container.config.getOrNull(SemanticsActions.ScrollBy) == null) container = container.parent
                requireNotNull(container).config[SemanticsActions.ScrollBy].action?.invoke(0f, 600f)
            }
            Thread.sleep(200)
            capture(dark, "parameters")
            alignLabel(R.string.ai_options_context_tokens)
            capture(dark, "context")
            nodes { all ->
                listOf("100", "99").forEach { value ->
                    assertTrue("Context fixture keeps the invalid input visible: $value", all.any {
                        it.config.getOrNull(SemanticsProperties.EditableText)?.text == value && it.boundsInWindow.height > 0
                    })
                }
            }
            instrumentation.runOnMainSync {
                draft.replaceHeaders = true
                draft.headersText = "Missing header separator"
            }
            await("header field appears") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_options_headers_lines) } } }
            alignLabel(R.string.ai_options_headers_lines)
            capture(dark, "headers")
            nodes { all ->
                var header: SemanticsNode? = all.last { text(it) == context.getString(R.string.ai_options_headers_lines) }
                while (header != null && !header.config.contains(SemanticsProperties.EditableText)) header = header.parent
                val field = requireNotNull(header)
                assertTrue("Masked header editor remains visible", field.boundsInWindow.height > 0)
                assertTrue("Header values remain masked in both revisions", field.config.contains(SemanticsProperties.Password))
                if (InstrumentationRegistry.getArguments().getString("readingReviewStage") != "before") {
                    assertNotNull("Invalid header format is explained at its field", field.config.getOrNull(SemanticsProperties.Error))
                }
            }
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue("Two native draw frames before screenshot", frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        // Semantics can be published before RenderThread submits the matching image buffer.
        Thread.sleep(250)
        val phase = if (InstrumentationRegistry.getArguments().getString("readingReviewStage") == "before") "before" else "after"
        val root = File(context.cacheDir, "ui-review/reading-$phase").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}

package dev.zakocoder.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.lsp.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Shared production LSP status control/sheets with synthetic state, no downloads or server processes. */
@SdkSuppress(minSdkVersion = 30)
class EditorLspUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(n: SemanticsNode) { all += n; n.children.forEach(::collect) }
            fun visit(v: android.view.View) {
                if (!v.isShown) return
                if (v is ViewRootForTest) collect(v.semanticsOwner.unmergedRootSemanticsNode)
                if (v is android.view.ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    private fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun matches(node: SemanticsNode, label: String): Boolean {
        val labels = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return labels.any { it == label || it.lineSequence().any { line -> line == label } } ||
            descriptions.any { it == label || it.endsWith(": $label") || it.endsWith("：$label") }
    }
    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var clicked = false
        while (!clicked && SystemClock.uptimeMillis() < deadline) {
            clicked = nodes { all ->
                val action = all.asReversed().firstNotNullOfOrNull { candidate ->
                    if (!matches(candidate, label)) return@firstNotNullOfOrNull null
                    var node: SemanticsNode? = candidate
                    while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                    node?.takeUnless { it.config.contains(SemanticsProperties.Disabled) }?.config?.getOrNull(SemanticsActions.OnClick)?.action
                }
                action?.invoke() == true
            }
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("Missing enabled clickable LSP action: $label", clicked)
        settle()
    }
    private fun click(resource: Int) = click(context.getString(resource))
    private fun await(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!nodes { all -> all.any { matches(it, label) && it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 } }) {
            check(SystemClock.uptimeMillis() < deadline) { "Missing LSP UI state: $label" }; Thread.sleep(50)
        }
    }
    private fun render(dark: Boolean) {
        val projectId = "lsp-ui-review"
        val path = "src/long_directory_name/example.py"
        val state = mutableStateOf(EditorLspState(LspPhase.Ready, LspServer.Python, 3, projectId = projectId, path = path))
        val events = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    EditorLspStatusContent(state.value, projectId, path, { events += "install:${it.name}" }, { events += "retry" })
                }
            } } }
            await(context.getString(R.string.editor_lsp_diagnostics, 3)); capture(dark, "compact")
            click(context.getString(R.string.editor_lsp_diagnostics, 3)); await(context.getString(R.string.editor_lsp_diagnostic_count, 3)); capture(dark, "ready")
            click(R.string.close)
            instrumentation.runOnMainSync { state.value = state.value.copy(phase = LspPhase.MissingServer, code = "lsp_server_missing") }
            await(context.getString(R.string.editor_lsp_missing)); click(R.string.editor_lsp_missing); capture(dark, "missing")
            click(R.string.editor_lsp_install); await(context.getString(R.string.editor_lsp_confirm_install)); capture(dark, "install-confirm")
            assertTrue(events.isEmpty())
            click(R.string.editor_lsp_confirm_install); assertEquals(listOf("install:Python"), events)
            instrumentation.runOnMainSync { state.value = state.value.copy(phase = LspPhase.Failed, code = "lsp_server_exited", stderr = "Traceback (most recent call last):\n  File \"/workspace/review/src/example.py\", line 2\nModuleNotFoundError: No module named diagnostic_fixture\nAuthorization: Bearer test-only-placeholder") }
            await(context.getString(R.string.editor_lsp_failed)); click(R.string.editor_lsp_failed)
            click(R.string.editor_lsp_show_diagnostics)
            assertFalse(nodes { all -> all.any { text(it).contains("test-only-placeholder") } })
            assertTrue(nodes { all -> all.any { text(it).contains("ModuleNotFoundError") && text(it).contains("[REDACTED]") } })
            capture(dark, "failure")
            click(R.string.editor_lsp_retry); assertEquals("retry", events.last())
            instrumentation.runOnMainSync { state.value = state.value.copy(phase = LspPhase.Checking, code = "") }
            await(context.getString(R.string.editor_lsp_checking)); click(R.string.editor_lsp_checking); capture(dark, "checking")
            click(R.string.close)
            instrumentation.runOnMainSync { state.value = state.value.copy(phase = LspPhase.Ready, projectId = "other-project") }
            await(context.getString(R.string.editor_lsp_idle))
            assertFalse(nodes { all -> all.any { text(it) == context.getString(R.string.editor_lsp_diagnostics, 3) } })
        }
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val cfg = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        val width = LocalWindowInfo.current.containerSize.width
        CompositionLocalProvider(LocalConfiguration provides cfg, LocalWorkbenchDark provides dark, LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun capture(dark: Boolean, name: String) {
        settle()
        val dir = File(context.cacheDir, "ui-review/editor-lsp").apply { mkdirs() }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(dir, "${if (dark) "night" else "day"}-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }
}

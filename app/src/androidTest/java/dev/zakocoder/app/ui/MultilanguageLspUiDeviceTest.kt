package dev.zakocoder.app.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.lsp.EditorLspState
import dev.zakocoder.app.editor.lsp.LspPhase
import dev.zakocoder.app.editor.lsp.LspServer
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ZakoApplication
import dev.zakocoder.app.runtime.ToolBundles
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Production ToolBundleList and real catalog; unknown detection fixture, no install or fake Ready results.
 * Uses the isolated instrumentation application's workbench only for untouched action callbacks.
 */
@SdkSuppress(minSdkVersion = 30)
class MultilanguageLspUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    @Test fun allNewBundlesInDayAndNight() {
        for (dark in listOf(false, true)) render(dark)
    }

    /** Shared production status sheet; Idle fixture never claims an installed or running server. */
    @Test fun optInControlsInDayAndNight() {
        for (dark in listOf(false, true)) {
            val enabled = mutableStateOf(false)
            val status = EditorLspState(LspPhase.Idle, LspServer.Java, projectId = "lsp-opt-in-review", path = "src/Main.java")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                                Column(Modifier.padding(16.dp)) {
                                    EditorLspStatusContent(status, "lsp-opt-in-review", "src/Main.java",
                                        onInstall = { error("Screenshot fixture must not install") },
                                        onRetry = { error("Screenshot fixture must not connect") },
                                        enabled = enabled.value, onEnabledChanged = { enabled.value = it })
                                }
                            }
                        }
                    }
                } }
                instrumentation.waitForIdleSync()
                click(context.getString(R.string.editor_lsp_disabled))
                awaitNodes("disabled help") { all -> all.any { text(it) == context.getString(R.string.editor_lsp_disabled_help) } }
                capture(dark, "off-sheet")
                click(context.getString(R.string.editor_lsp_enabled_label))
                assertTrue("Production switch callback enables the fixture", enabled.value)
                awaitNodes("enabled idle help") { all -> all.any { text(it) == context.getString(R.string.editor_lsp_idle_help) } }
                capture(dark, "on-sheet")
                click(context.getString(R.string.editor_lsp_enabled_label))
                assertTrue("Production switch callback disables the fixture", !enabled.value)
            }
        }
    }

    private fun awaitNodes(description: String, predicate: (List<SemanticsNode>) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!nodes(predicate)) {
            check(SystemClock.uptimeMillis() < deadline) { "LSP UI not composed: $description" }
            Thread.sleep(50)
        }
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var clicked = false
        while (!clicked && SystemClock.uptimeMillis() < deadline) {
            clicked = nodes { all ->
                val action = all.asReversed().firstNotNullOfOrNull { candidate ->
                    val matches = text(candidate) == label || candidate.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
                    if (!matches) return@firstNotNullOfOrNull null
                    var node: SemanticsNode? = candidate
                    while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
                    node?.config?.getOrNull(SemanticsActions.OnClick)?.action
                }
                action?.invoke() == true
            }
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("Missing clickable LSP control: $label", clicked)
        Thread.sleep(650)
        instrumentation.waitForIdleSync()
    }

    private fun render(dark: Boolean) {
        val scroll = ScrollState(0)
        val selected = mutableStateOf(emptyList<String>())
        // No physical environment status is claimed. Empty checks render the real Unknown state.
        val fixture = WorkbenchState(loading = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val workbench = (activity.application as ZakoApplication).workbench
                activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                                ToolBundleList(workbench, fixture, selected.value, { selected.value = it }, {})
                            }
                        }
                    }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val seen = mutableSetOf<String>()
            // Two grouped screenshots show the actual rows without filtering or duplicating their UI.
            for ((index, anchor) in listOf("lsp-java", "lsp-go").withIndex()) {
                val anchorLabel = context.getString(ToolBundles.catalog.single { it.id == anchor }.title)
                awaitNodes(anchorLabel) { all -> all.any { text(it) == anchorLabel } }
                val top = nodes { all -> all.first { text(it) == anchorLabel }.layoutInfo.coordinates.localToWindow(Offset.Zero).y }
                runBlocking(Dispatchers.Main) { scroll.scrollTo((scroll.value + top - 130).toInt().coerceAtLeast(0)) }
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
                for (id in listOf("lsp-java", "lsp-kotlin", "lsp-cpp", "lsp-go", "lsp-rust", "lsp-dart")) {
                    val label = context.getString(ToolBundles.catalog.single { it.id == id }.title)
                    if (nodes { all -> all.any { text(it) == label && it.boundsInWindow.top >= 0 &&
                            it.boundsInWindow.bottom <= context.resources.displayMetrics.heightPixels && it.boundsInWindow.height > 0 } }) seen += id
                }
                capture(dark, index)
            }
            assertTrue("All six language rows must be visible across captures: $seen", seen.size == 6)
        }
    }

    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()

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

    private fun capture(dark: Boolean, index: Int) {
        capture(dark, if (index == 0) "" else "2")
    }

    private fun capture(dark: Boolean, suffix: String) {
        val root = File(context.filesDir, "screen-review").apply { mkdirs() }
        val name = "multilsp-${if (dark) "night" else "day"}${if (suffix.isEmpty()) "" else "-$suffix"}.png"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}

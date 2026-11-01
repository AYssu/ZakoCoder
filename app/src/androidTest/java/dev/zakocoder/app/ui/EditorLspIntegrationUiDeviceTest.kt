package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ZakoApplication
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.editor.lsp.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.warmDeviceActivity
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lsp.editor.LspLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual editor + actual Ubuntu Python server. Only the source fixture and draft location are isolated. */
@SdkSuppress(minSdkVersion = 30)
class EditorLspIntegrationUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360Font130RealEditorAndDiagnostics() = render(false)
    @Test(timeout = 180000) fun night360Font130RealEditorAndDiagnostics() = render(true)
    private suspend fun await(message: String, predicate: () -> Boolean) = withTimeout(45000) {
        while (!predicate()) delay(100)
        assertTrue(message, predicate())
    }
    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(n: SemanticsNode) { all += n; n.children.forEach(::collect) }
        fun visit(v: android.view.View) {
            if (!v.isShown) return
            if (v is ViewRootForTest) collect(v.semanticsOwner.unmergedRootSemanticsNode)
            if (v is android.view.ViewGroup) repeat(v.childCount) { visit(v.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }
    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == label || it.endsWith(": $label") || it.endsWith("：$label") }
    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var clicked = false
        while (!clicked && SystemClock.uptimeMillis() < deadline) {
            clicked = nodes { all -> all.asReversed().firstNotNullOfOrNull { candidate ->
                if (!matches(candidate, label)) return@firstNotNullOfOrNull null
                var current: SemanticsNode? = candidate
                while (current != null && current.config.getOrNull(SemanticsActions.OnClick) == null) current = current.parent
                current?.takeUnless { it.config.contains(SemanticsProperties.Disabled) }?.config?.getOrNull(SemanticsActions.OnClick)?.action
            }?.invoke() == true }
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("LSP action available: $label", clicked)
    }
    private fun render(dark: Boolean) = runBlocking {
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("Workbench loaded") { !workbench.state.value.loading && !workbench.state.value.busy }
        assumeTrue("Requires installed Ubuntu", workbench.state.value.installed)
        val detection = workbench.runtime.execute(LspServer.Python.detectScript, null, workbench.state.value.settings, timeoutMillis = 20000)
        assumeTrue("Requires explicitly installed Python language server", detection.exitCode == 0)
        val ownedContextRoot = withContext(Dispatchers.IO) { File(context.cacheDir, "lsp-editor-ui-${UUID.randomUUID()}").apply { mkdirs() } }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(ownedContextRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(ownedContextRoot, "no-backup").apply { mkdirs() }
        }
        val project = withContext(Dispatchers.IO) { workbench.projects.create("lsp-ui-owned-${UUID.randomUUID()}") }
        val directory = withContext(Dispatchers.IO) { workbench.projects.directory(project.id) }
        val filename = "game_score.py"
        val baseline = "def calculate_score():\n    return missing_game_score\n\n\nprint(calculate_score())\n"
        withContext(Dispatchers.IO) { File(directory, filename).writeText(baseline) }
        lateinit var controller: EditorController
        lateinit var lsp: EditorLspController
        var initialized = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        lsp = EditorLspController(isolated, workbench.runtime, { workbench.state.value.settings }, { workbench.state.value.installed })
                        controller = EditorController(isolated, workbench.projects, {}, lsp = lsp)
                        initialized = true
                        activity.setContent { Theme(dark) {
                            CodeEditorPage(controller, lspEnabled = true, onInstallLsp = { error("Integration UI test must not install dependencies") })
                        } }
                    }
                    await("Isolated editor ready") { !controller.state.value.loading }
                    main { controller.open(project.id, filename) }
                    await("Real server ready") { lsp.state.value.phase in setOf(LspPhase.Ready, LspPhase.Failed, LspPhase.MissingServer) }
                    assertEquals(lsp.state.value.toString(), LspPhase.Ready, lsp.state.value.phase)
                    await("Actual diagnostics delivered") { lsp.state.value.diagnosticCount > 0 && main {
                        val view = actualEditor(scenario)
                        if (view == null || view.editorLanguage !is LspLanguage) false else {
                            val regions = mutableListOf<DiagnosticRegion>()
                            view.diagnostics?.queryInRegion(regions, 0, view.text.length)
                            regions.isNotEmpty()
                        }
                    } }
                    main {
                        val view = requireNotNull(actualEditor(scenario))
                        assertTrue(view.editorLanguage is LspLanguage)
                        assertEquals(project.id, lsp.state.value.projectId)
                        assertEquals(filename, lsp.state.value.path)
                        assertEquals(baseline, view.text.toString())
                        view.clearFocus()
                        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
                    }
                    capture(dark, "editor-real-diagnostics")
                    val count = lsp.state.value.diagnosticCount
                    click(context.getString(R.string.editor_lsp_diagnostics, count))
                    await("Real status sheet shows diagnostic count") { nodes { all -> all.any { matches(it, context.getString(R.string.editor_lsp_diagnostic_count, count)) } } }
                    capture(dark, "status-real-diagnostics")
                    withContext(Dispatchers.IO) { assertEquals(baseline, File(directory, filename).readText()) }
                    assertFalse(controller.state.value.dirty)
                } finally {
                    if (initialized) {
                        scenario.onActivity { activity -> activity.setContent { } }
                        instrumentation.waitForIdleSync()
                        main { controller.close(); lsp.close() }
                    }
                }
            }
        } finally {
            if (initialized) main { lsp.close() }
            // Only this test's never-registered project and its private fixture context are removed.
            withContext(NonCancellable + Dispatchers.IO) {
                delay(300)
                PrivateFiles.deleteTree(directory)
                PrivateFiles.deleteTree(ownedContextRoot)
            }
        }
    }
    private fun actualEditor(scenario: ActivityScenario<MainActivity>): CodeEditor? {
        var result: CodeEditor? = null
        scenario.onActivity { activity ->
            fun find(v: android.view.View) {
                if (v is CodeEditor && v.isShown) result = v
                if (v is android.view.ViewGroup) repeat(v.childCount) { find(v.getChildAt(it)) }
            }
            find(activity.window.decorView)
        }
        return result
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
    private fun capture(dark: Boolean, name: String) {
        Thread.sleep(650); instrumentation.waitForIdleSync()
        val root = File(context.cacheDir, "ui-review/editor-lsp-integration").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}

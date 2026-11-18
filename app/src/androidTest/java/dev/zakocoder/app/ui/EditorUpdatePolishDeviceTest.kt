package dev.zakocoder.app.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.LinkAnnotation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.updates.*
import dev.zakocoder.app.warmDeviceActivity
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Isolated documents and release metadata; no user projects, credentials or external network. */
class EditorUpdatePolishDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (!condition()) { check(SystemClock.uptimeMillis() < end) { label }; Thread.sleep(40) }
    }
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun click(label: String) {
        await("action available: $label") { nodes { all -> all.any { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } } }
        nodes { all ->
            var node: SemanticsNode? = all.last { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        instrumentation.waitForIdleSync()
    }
    private fun editor(scenario: ActivityScenario<MainActivity>): CodeEditor? {
        var result: CodeEditor? = null
        fun visit(view: View) {
            if (view is CodeEditor) result = view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        scenario.onActivity { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit) }
        return result
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), content = content)
            }
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(context.cacheDir, "ui-review/editor-update/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test(timeout = 120_000) fun symbolKeysCursorTabsUndoAndSaveKeepNativeBuffersInBothThemes() {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "editor-polish-$token").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val id = UUID.randomUUID().toString()
        val project = File(root, "projects/$id").apply { mkdirs() }
        val original = "def greet():\n    return 42\n"
        val source = File(project, "main.py").apply { writeText(original) }
        File(project, "helper.py").writeText("answer = 42\n")
        lateinit var controller: EditorController
        var initialized = false
        try {
            instrumentation.runOnMainSync { controller = EditorController(isolated, ProjectRepository(isolated, File(root, "projects")), {}) }
            initialized = true
            await("controller initialized") { !controller.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    instrumentation.runOnMainSync { controller.open(id, "main.py") }
                    await("main source loaded") { controller.state.value.path == "main.py" && !controller.state.value.loading }
                    scenario.onActivity { it.setContent { Theme(dark) {
                        val documentState by controller.state.collectAsState()
                        Text("EDITOR_FILES_PARENT")
                        if (documentState.projectId != null) CodeEditorPage(controller, fullScreen = true)
                    } } }
                    await("native buffer mounted") { editor(scenario)?.text === controller.state.value.document?.content }
                    val view = requireNotNull(editor(scenario))
                    await("native code area follows the current theme") {
                        var matches = false
                        instrumentation.runOnMainSync {
                            val color = view.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
                            matches = (android.graphics.Color.red(color) < 128) == dark
                        }
                        matches
                    }
                    click(context.getString(R.string.editor_file_actions))
                    await("file operations are in the overflow menu") { has(context.getString(R.string.editor_disk_check)) }
                    click(context.getString(R.string.editor_reload))
                    await("reload still requires confirmation") { has(context.getString(R.string.editor_reload_message)) }
                    capture("editor-reload-${if (dark) "night" else "day"}")
                    click(context.getString(R.string.cancel))
                    await("reload confirmation dismissed") { !has(context.getString(R.string.editor_reload_message)) }
                    assertEquals(original, source.readText())
                    instrumentation.runOnMainSync { view.setSelection(1, 4) }
                    await("cursor position published") { has("2:5") }
                    click(context.getString(R.string.editor_indent_key))
                    await("native indentation changes buffer") { controller.state.value.document!!.content.getLineString(1) == "        return 42" }
                    assertEquals(original, source.readText())
                    click(context.getString(R.string.editor_undo))
                    await("indentation is undoable") { controller.state.value.document!!.content.toString() == original }
                    instrumentation.runOnMainSync { view.setSelectionRegion(1, 4, 1, 10) }
                    click("{")
                    val edited = "def greet():\n    { 42\n"
                    await("symbol replaces selected text") { controller.state.value.document!!.content.toString() == edited }
                    assertEquals(original, source.readText())
                    val buffer = controller.state.value.document!!.content
                    instrumentation.runOnMainSync { controller.open(id, "helper.py") }
                    await("second file mounted") { controller.state.value.path == "helper.py" && !controller.state.value.loading }
                    click("main.py •")
                    await("tab restores the unsaved original buffer") { controller.state.value.document?.content === buffer && editor(scenario)?.text === buffer }
                    assertEquals(edited, buffer.toString())
                    capture("editor-${if (dark) "night" else "day"}")
                    val mounted = requireNotNull(editor(scenario))
                    val root = mounted.rootView
                    assertEquals("Editor window covers the complete display", context.resources.displayMetrics.widthPixels, root.width)
                    var barTop = 0
                    var barBottom = 0
                    instrumentation.runOnMainSync {
                        val insets = checkNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(WindowInsetsCompat.Type.systemBars())
                        barTop = insets.top; barBottom = insets.bottom
                    }
                    nodes { all ->
                        val save = all.last { text(it) == context.getString(R.string.editor_save) }
                        assertTrue("Editor controls clear the status bar", save.boundsInWindow.top >= barTop)
                        val symbol = all.first { text(it) == context.getString(R.string.editor_indent_key) }
                        assertTrue("Editor symbols clear the navigation bar", symbol.boundsInWindow.bottom <= root.height - barBottom + 1)
                    }
                    instrumentation.runOnMainSync {
                        // Injected Android key events can expose a hardware keyboard on AOSP.
                        // This fixture verifies the phone's software-keyboard layout explicitly.
                        mounted.setDisableSoftKbdIfHardKbdAvailable(false)
                        mounted.requestFocus(); mounted.showSoftInput()
                    }
                    await("keyboard visible") {
                        var visible = false
                        instrumentation.runOnMainSync { visible = ViewCompat.getRootWindowInsets(mounted.rootView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                        visible
                    }
                    await("symbol input clears keyboard after inset animation") {
                        var keyboardTop = 0
                        instrumentation.runOnMainSync {
                            val decor = mounted.rootView
                            keyboardTop = decor.height - checkNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.ime()).bottom
                        }
                        nodes { all ->
                            val tab = all.firstOrNull { text(it) == context.getString(R.string.editor_indent_key) }
                            tab != null && tab.boundsInWindow.bottom <= keyboardTop + 1
                        }
                    }
                    capture("editor-keyboard-${if (dark) "night" else "day"}")
                    instrumentation.runOnMainSync { mounted.hideSoftInput() }
                    click(context.getString(R.string.editor_save))
                    await("only explicit save writes the source") { !controller.state.value.saving && !controller.state.value.dirty && source.readText() == edited }
                    instrumentation.runOnMainSync { mounted.undo(); controller.changed(); controller.save() }
                    await("source restored for next theme") { !controller.state.value.saving && source.readText() == original }
                    val input = DirectNavigationEventInput()
                    val dispatcher = AtomicReference<androidx.navigationevent.NavigationEventDispatcher>()
                    instrumentation.runOnMainSync {
                        dispatcher.set(requireNotNull(mounted.findViewTreeNavigationEventDispatcherOwner()).navigationEventDispatcher)
                        dispatcher.get().addInput(input)
                    }
                    val headerX = nodes { all -> all.first { text(it) == "main.py" }.boundsInWindow.left }
                    try {
                        instrumentation.runOnMainSync { input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT)) }
                        instrumentation.runOnMainSync { input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = .6f)) }
                        await("Editor window follows gesture progress") {
                            nodes { all -> all.first { text(it) == "main.py" }.boundsInWindow.left > headerX + 40f }
                        }
                        capture("editor-back-progress-${if (dark) "night" else "day"}")
                        instrumentation.runOnMainSync { input.backCancelled() }
                        await("Cancelled gesture restores the native editor") {
                            nodes { all -> kotlin.math.abs(all.first { text(it) == "main.py" }.boundsInWindow.left - headerX) < 1f }
                        }
                        assertSame(mounted, editor(scenario))
                        assertEquals(original, controller.state.value.document!!.content.toString())
                        instrumentation.runOnMainSync { input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT)); input.backCompleted() }
                        await("Committed Back reveals the retained parent") { controller.state.value.projectId == null && editor(scenario) == null }
                    } finally { instrumentation.runOnMainSync { dispatcher.get().removeInput(input) } }
                }
            }
        } finally {
            if (initialized) instrumentation.runOnMainSync { controller.close() }
            Thread.sleep(700)
            root.deleteRecursively()
            context.deleteSharedPreferences("$token-projects")
        }
    }

    @Test(timeout = 90_000) fun releaseNotesRenderMarkdownAndOnlyOpenLinksAfterConfirmation() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val notes = "## 本次更新\n\n- **修复连接失败**\n- 编辑器体验优化\n\n| 功能 | 状态 |\n| --- | --- |\n| 快捷输入 | 已改进 |\n\n```sh\ngit status\n```\n\n[查看说明](https://example.invalid/release)"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
          for (dark in listOf(false, true)) {
            scenario.onActivity { it.setContent { Theme(dark) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    val release = AppRelease("v0.1.5", notes, false, ReleaseAsset("app.apk", "", 12_000_000), ReleaseAsset("SHA256SUMS.txt", "", 100))
                    UpdateContent(UpdateState(initialized = true, currentVersion = "0.1.4", phase = UpdatePhase.Available, release = release), { _, _ -> }, {}, {}, {}, {})
                }
            } } }
            await("headings and lists rendered without Markdown delimiters") { has("本次更新") && has("修复连接失败") && has("查看说明") }
            assertFalse(has("## 本次更新"))
            assertTrue(has("功能")); assertTrue(has("快捷输入"))
            assertTrue(nodes { all -> all.any { text(it).contains("git status") } })
            capture("updates-${if (dark) "night" else "day"}")
            val before = opened.size
            nodes { all ->
                val value = all.first { text(it) == "查看说明" }.config[SemanticsProperties.Text].single()
                val annotation = value.getLinkAnnotations(0, value.length).single().item as LinkAnnotation.Clickable
                requireNotNull(annotation.linkInteractionListener).onClick(annotation)
            }
            await("external link requires confirmation") { has(context.getString(R.string.ai_open_link)) }
            assertEquals(before, opened.size)
            click(context.getString(R.string.cancel))
            assertEquals(before, opened.size)
            await("link confirmation dismissed") { !has(context.getString(R.string.ai_open_link)) }
            nodes { all ->
                val value = all.first { text(it) == "查看说明" }.config[SemanticsProperties.Text].single()
                val annotation = value.getLinkAnnotations(0, value.length).single().item as LinkAnnotation.Clickable
                requireNotNull(annotation.linkInteractionListener).onClick(annotation)
            }
            click(context.getString(R.string.ai_open_browser))
            await("browser opens after the confirmation exit") { opened.size == before + 1 }
            assertEquals(before + 1, opened.size)
            assertEquals("https://example.invalid/release", opened.last())
            await("confirmed dialog dismissed before theme changes") { !has(context.getString(R.string.ai_open_link)) }
          }
        }
    }
}

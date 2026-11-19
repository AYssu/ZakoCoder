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
import dev.zakocoder.app.test.R as TestR
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
    private fun has(label: String) = nodes { all -> all.any { text(it) == label || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } }
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

    @Test(timeout = 120_000) fun compactFindReplaceAndTabsInBothThemes() {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "editor-search-$token").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val id = UUID.randomUUID().toString()
        val project = File(root, "projects/$id").apply { mkdirs() }
        File(project, "main.py").writeText("alpha alpha\nbeta\n")
        val longName = "workspace_configuration_helpers.py"
        File(project, longName).writeText("answer = 42\n")
        lateinit var controller: EditorController
        var initialized = false
        val density = context.resources.displayMetrics.density
        fun setField(label: Int, value: String) = nodes { all ->
            val field = all.single { context.getString(label) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                it.config.contains(SemanticsActions.SetText) }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString(value)))
        }
        fun taggedHeight(tag: String) = nodes { all ->
            all.single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInWindow.height / density
        }
        try {
            instrumentation.runOnMainSync { controller = EditorController(isolated, ProjectRepository(isolated, File(root, "projects")), {}) }
            initialized = true
            await("controller ready") { !controller.state.value.loading }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    instrumentation.runOnMainSync { controller.open(id, longName) }
                    await("long file opened") { controller.state.value.path == longName && !controller.state.value.loading }
                    instrumentation.runOnMainSync { controller.open(id, "main.py") }
                    await("main opened") { controller.state.value.path == "main.py" && !controller.state.value.loading }
                    val previousView = editor(scenario)
                    instrumentation.runOnMainSync {
                        val content = requireNotNull(controller.state.value.document).content
                        content.replace(0, 0, content.lineCount - 1, content.getColumnCount(content.lineCount - 1), "alpha alpha\nbeta\n")
                        controller.changed()
                    }
                    scenario.onActivity { it.setContent { Theme(dark) { CodeEditorPage(controller, fullScreen = true) } } }
                    await("new themed editor mounted") { editor(scenario)?.let { it !== previousView && it.text === controller.state.value.document?.content } == true }
                    val view = requireNotNull(editor(scenario))
                    assertTrue("tab strip is compact", taggedHeight("editor-file-tabs") <= 44.5f)
                    nodes { all -> all.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }.forEach {
                        assertTrue("tab width is bounded", it.boundsInWindow.width / density <= 152.5f)
                    } }
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_find))
                    await("find is one row") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "editor-search-bar" } } }
                    assertTrue(taggedHeight("editor-search-bar") <= 48.5f)
                    assertFalse("find has no replacement field", has(context.getString(R.string.editor_replacement)))
                    setField(R.string.editor_find, "alpha")
                    await("two matches found") { view.searcher.hasQuery() && view.searcher.matchedPositionCount == 2 }
                    capture("editor-find-${if (dark) "night" else "day"}")
                    click(context.getString(R.string.editor_next_match))
                    await("native match selected") { view.cursor.isSelected }
                    click(context.getString(R.string.editor_close_search))
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_replace))
                    await("replacement field only in replace mode") { has(context.getString(R.string.editor_replacement)) }
                    assertTrue(taggedHeight("editor-search-bar") <= 96.5f)
                    setField(R.string.editor_replacement, "omega")
                    capture("editor-replace-${if (dark) "night" else "day"}")
                    click(context.getString(R.string.editor_replace))
                    await("single match replaced") { view.text.toString().contains("omega") && view.text.toString().contains("alpha") }
                    click(context.getString(R.string.editor_replace_all))
                    await("remaining matches replaced") { view.text.toString() == "omega omega\nbeta\n" }
                    click(context.getString(R.string.editor_close_search))
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_find))
                    assertFalse("reopening find never inherits replace mode", has(context.getString(R.string.editor_replacement)))
                    click(context.getString(R.string.editor_close_search))
                }
            }
        } finally {
            if (initialized) instrumentation.runOnMainSync { controller.dispose() }
            root.deleteRecursively()
        }
    }

    @Test(timeout = 120_000) fun compactEditorMenusAutosaveWorkspaceAndConversationInBothThemes() {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "editor-polish-$token").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$token-$name", mode)
        }
        val id = UUID.randomUUID().toString()
        val project = File(root, "projects/$id").apply { mkdirs() }
        val source = File(project, "main.py").apply { writeText("alpha\nBeta\nlast\n") }
        File(project, "helper.py").writeText("answer = 42\n")
        lateinit var controller: EditorController
        var initialized = false
        var conversations = 0
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
                        if (documentState.projectId != null) CodeEditorPage(controller, fullScreen = true, onConversation = { conversations++ })
                    } } }
                    await("native buffer mounted") { editor(scenario)?.text === controller.state.value.document?.content }
                    val view = requireNotNull(editor(scenario))
                    assertFalse("manual save is no longer a permanent button", has(context.getString(R.string.editor_save)))
                    instrumentation.runOnMainSync { view.setSelection(1, 1) }
                    click(context.getString(R.string.editor_file_actions))
                    await("compact editing menu") { has(context.getString(R.string.editor_uppercase_line)) }
                    assertFalse(has(instrumentation.context.getString(TestR.string.editor_disk_check)))
                    assertFalse(has(context.getString(R.string.editor_reload)))
                    capture("editor-menu-${if (dark) "night" else "day"}")
                    click(context.getString(R.string.editor_select_line))
                    assertEquals("Beta", view.text.toString().substring(view.cursor.left, view.cursor.right))
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_uppercase_line))
                    await("line uppercased") { view.text.getLineString(1) == "BETA" }
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_lowercase_line))
                    await("line lowercased") { view.text.getLineString(1) == "beta" }
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_delete_line))
                    await("whole line deleted") { view.text.toString() == "alpha\nlast\n" }
                    click(context.getString(R.string.editor_undo))
                    await("deletion undoable") { view.text.toString() == "alpha\nbeta\nlast\n" }
                    await("periodic autosave writes changed content") { source.readText() == "alpha\nbeta\nlast\n" && !controller.state.value.saving }
                    click(context.getString(R.string.editor_file_actions)); click(context.getString(R.string.editor_find))
                    await("search editor shown") { has(context.getString(R.string.editor_find)) }
                    click(context.getString(R.string.editor_close_search))
                    val conversationX = nodes { all -> all.single { context.getString(R.string.editor_conversation) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInWindow.left }
                    nodes { all ->
                        val bar = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "editor-shortcuts" }
                        assertTrue(bar.config[SemanticsActions.ScrollBy].action!!.invoke(500f, 0f))
                    }
                    await("undo scrolls away while conversation stays fixed") { nodes { all ->
                        val undo = all.firstOrNull { context.getString(R.string.editor_undo) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        val chat = all.single { context.getString(R.string.editor_conversation) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                        (undo == null || undo.boundsInWindow.width <= 0f) && kotlin.math.abs(chat.boundsInWindow.left - conversationX) < 1f
                    } }
                    capture("editor-workspace-${if (dark) "night" else "day"}")
                    // Exercise the bottom bar's actual upward touch gesture, rather than invoking its click fallback.
                    val bounds = nodes { all -> all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "editor-workspace-bar" }.boundsInWindow }
                    val downTime = android.os.SystemClock.uptimeMillis()
                    val x = bounds.center.x; val y = bounds.center.y
                    fun touch(action: Int, dy: Float, elapsed: Long) {
                        val event = android.view.MotionEvent.obtain(downTime, downTime + elapsed, action, x, y + dy, 0)
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN, 0f, 0)
                    for (step in 1..10) touch(android.view.MotionEvent.ACTION_MOVE, -step * 14f, step * 20L)
                    touch(android.view.MotionEvent.ACTION_UP, -140f, 220)
                    await("swipe opens workspace file panel") { has(context.getString(R.string.editor_workspace_files)) && has("helper.py") }
                    capture("editor-files-panel-${if (dark) "night" else "day"}")
                    click("helper.py")
                    await("panel opens chosen file") { controller.state.value.path == "helper.py" && !controller.state.value.loading }
                    val before = conversations
                    click(context.getString(R.string.editor_conversation))
                    assertEquals(before + 1, conversations)
                    instrumentation.runOnMainSync { controller.close() }
                    await("editor closed") { controller.state.value.projectId == null }
                    source.writeText("alpha\nBeta\nlast\n")
                }
            }
        } finally {
            if (initialized) instrumentation.runOnMainSync { controller.dispose() }
            root.deleteRecursively()
        }
    }

    @Test(timeout = 90000) fun latestControlOverlaysHistoryWithoutMovingComposerInBothThemes() = kotlinx.coroutines.runBlocking {
        val root = File(context.cacheDir, "latest-overlay-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val repo = ProjectRepository(isolated, File(isolated.filesDir, "workspaces").apply { mkdirs() })
        val history = dev.zakocoder.app.ai.AiHistoryStore(isolated)
        val project = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val project = repo.create("Overlay preview")
            repo.save(listOf(project)); repo.select(project.id)
            history.save(project.id, dev.zakocoder.app.ai.AiState(projectId = project.id, conversationId = UUID.randomUUID().toString(),
                timeline = (1..24).map { index -> dev.zakocoder.app.ai.AiEntry("line-$index", "Message $index\n" + (1..4).joinToString("\n") { "History line $it for review." }, role = dev.zakocoder.app.ai.AiRole.Assistant) }))
            project
        }
        lateinit var host: dev.zakocoder.app.Workbench
        instrumentation.runOnMainSync { host = dev.zakocoder.app.Workbench(isolated, aiHistory = history) }
        try {
            await("isolated workbench loaded") { !host.state.value.loading }
            instrumentation.runOnMainSync { host.ai.select(project.id) }
            await("isolated history loaded") { !host.state.value.loading && host.ai.state.value.projectId == project.id && !host.ai.state.value.permissionsLoading && host.ai.state.value.timeline.size == 24 }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (dark in listOf(false, true)) {
                    scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                        val state by host.state.collectAsState()
                        key(dark) { AiPage(host, state, onModels = {}, onProjects = {}, onTerminal = {}) }
                    } } }
                    await("history viewport visible") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-message-viewport" } } }
                    Thread.sleep(1200)
                    val before = nodes { all -> all.first { it.config.contains(SemanticsActions.SetText) }.boundsInWindow.top }
                    val viewport = nodes { all -> all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-message-viewport" }.boundsInWindow }
                    val downTime = android.os.SystemClock.uptimeMillis()
                    for (step in 0..12) {
                        val event = android.view.MotionEvent.obtain(downTime, downTime + step * 20L,
                            if (step == 0) android.view.MotionEvent.ACTION_DOWN else if (step == 12) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE,
                            viewport.center.x, viewport.top + viewport.height * (.25f + step / 12f * .45f), 0)
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    await("latest appears while reviewing history") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-latest-overlay" } } }
                    nodes { all ->
                        val latest = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-latest-overlay" }.boundsInWindow
                        val content = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-message-viewport" }.boundsInWindow
                        val composerTop = all.first { it.config.contains(SemanticsActions.SetText) }.boundsInWindow.top
                        assertEquals("overlay never reserves a separate composer row", before, composerTop, 1f)
                        assertTrue(latest.bottom <= content.bottom + 1f && latest.top >= content.top)
                    }
                    capture("chat-latest-overlay-${if (dark) "night" else "day"}")
                    click(context.getString(R.string.ai_latest))
                    await("latest action returns to end") { nodes { all -> all.none { it.config.getOrNull(SemanticsProperties.TestTag) == "ai-latest-overlay" } } }
                }
            }
        } finally {
            instrumentation.runOnMainSync { host.editor.dispose(); host.stopAll() }
            kotlinx.coroutines.delay(400)
            root.deleteRecursively()
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

package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.*

/** Real native gestures and clipboard/IME. Synthetic content and isolated config; no external API. */
@SdkSuppress(minSdkVersion = 30)
class AiReadingBehaviorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
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
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { label }; Thread.sleep(50) }
    }
    private fun click(label: Int) = nodes { all ->
        val name = context.getString(label)
        var node: SemanticsNode? = all.last { text(it) == name || name in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun field(all: List<SemanticsNode>, label: Int): SemanticsNode {
        var node: SemanticsNode? = all.first { text(it) == context.getString(label) }
        while (node != null && !node.config.contains(SemanticsProperties.EditableText)) node = node.parent
        return requireNotNull(node)
    }
    private fun showField(label: Int) {
        repeat(10) {
            val visible = nodes { all ->
                val target = field(all, label)
                var scroll = target.parent
                while (scroll != null && !scroll.config.contains(SemanticsActions.ScrollBy)) scroll = scroll.parent
                val container = requireNotNull(scroll)
                val bounds = target.boundsInWindow
                if (bounds.height >= target.size.height * .98f && bounds.top >= container.boundsInWindow.top &&
                    bounds.bottom <= container.boundsInWindow.bottom) true else {
                    val origin = target.layoutInfo.coordinates.localToWindow(Offset.Zero)
                    assertTrue(container.config[SemanticsActions.ScrollBy].action?.invoke(0f,
                        origin.y - container.boundsInWindow.top - 24f) == true)
                    false
                }
            }
            if (visible) {
                var stableSince = SystemClock.uptimeMillis()
                var previous = nodes { all -> field(all, label).boundsInWindow }
                await("field position settles before a native tap") {
                    val bounds = nodes { all -> field(all, label).boundsInWindow }
                    if (bounds != previous) { previous = bounds; stableSince = SystemClock.uptimeMillis() }
                    SystemClock.uptimeMillis() - stableSince >= 300
                }
                return
            }
            Thread.sleep(100)
        }
        error("Field never became visible before user tap: ${context.getString(label)}")
    }
    private fun tapField(label: Int, scenario: ActivityScenario<MainActivity>) {
        var windowToScreen = Offset.Zero
        scenario.onActivity { activity ->
            val onScreen = IntArray(2)
            val inWindow = IntArray(2)
            activity.window.decorView.getLocationOnScreen(onScreen)
            activity.window.decorView.getLocationInWindow(inWindow)
            windowToScreen = Offset((onScreen[0] - inWindow[0]).toFloat(), (onScreen[1] - inWindow[1]).toFloat())
        }
        val point = nodes { all ->
            val bounds = field(all, label).boundsInWindow
            Offset(bounds.center.x, bounds.top + 24f * context.resources.displayMetrics.widthPixels / 360f)
        } + windowToScreen
        println("Native field tap: screenPoint=$point, windowToScreen=$windowToScreen")
        val started = SystemClock.uptimeMillis()
        MotionEvent.obtain(started, started, MotionEvent.ACTION_DOWN, point.x, point.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        MotionEvent.obtain(started, started + 80, MotionEvent.ACTION_UP, point.x, point.y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
    }
    @Composable private fun Theme(content: @Composable () -> Unit) {
        val density = context.resources.displayMetrics.widthPixels / 360f
        CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
            MaterialTheme(colorScheme = WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }

    @Test(timeout = 120000) fun longCodeCopyExpansionAndFullSourceRetainOriginalAcrossStreamUpdates() {
        val original = (1..240).joinToString("\n") { "val received$it = \"line-$it 😀\"" } + "\n"
        var code by mutableStateOf(original)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Theme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AiMarkdown("```kotlin\n$code", streaming = true) {}
                }
            } } }
            await("long code preview parsed") { has(context.getString(R.string.ai_reading_code_expand)) }
            nodes { all ->
                val preview = all.first { text(it).startsWith("val received1") }
                assertTrue(text(preview).contains("received12")); assertFalse(text(preview).contains("received13"))
            }
            click(R.string.ai_copy_code)
            instrumentation.runOnMainSync { assertEquals(original,
                context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
            click(R.string.ai_reading_code_expand)
            await("expanded preview remains bounded and offers complete source") { has(context.getString(R.string.ai_reading_code_full)) }
            val expanded = nodes { all -> text(all.first { text(it).startsWith("val received1") }) }
            assertTrue(expanded.length <= AiCodePreview.EXPANDED_CHARS)
            instrumentation.runOnMainSync { code += "val received241 = \"next\"\n" }
            await("new line count received without resetting expansion") { has(context.getString(R.string.ai_reading_code_lines, 241)) }
            assertTrue(has(context.getString(R.string.ai_reading_code_collapse)))
            assertEquals(expanded, nodes { all -> text(all.first { text(it).startsWith("val received1") }) })
            click(R.string.ai_reading_code_full)
            await("snapshot full source opens") { has(context.getString(R.string.ai_full_text_title)) }
            val pageLabel = context.getString(R.string.ai_full_text_body)
            val snapshotPage = nodes { all -> text(all.last { pageLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }) }
            assertTrue(snapshotPage.startsWith("val received1"))
            instrumentation.runOnMainSync { code += "val received242 = \"later\"\n" }
            Thread.sleep(200)
            assertEquals("Open source review is a stable snapshot", snapshotPage,
                nodes { all -> text(all.last { pageLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }) })
            click(R.string.ai_full_text_next)
            await("source pagination moves beyond first page") {
                nodes { all -> text(all.last { pageLabel in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }) != snapshotPage }
            }
        }
    }

    private fun dragDown(bounds: androidx.compose.ui.geometry.Rect) {
        val x = bounds.center.x
        val startY = bounds.top + 50f
        val endY = bounds.bottom - 50f
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0).also {
            try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, startY)
        repeat(16) { step -> Thread.sleep(16); event(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (step + 1) / 16f) }
        event(MotionEvent.ACTION_UP, endY)
    }

    @Test(timeout = 120000) fun reasoningUserScrollPausesFollowAndLatestRestoresIt() {
        var source by mutableStateOf((1..600).joinToString("\n") { "分析 $it：读取项目，核对实现和验证结果。" })
        val interactions = java.util.concurrent.atomic.AtomicInteger()
        fun range() = nodes { all ->
            var body: SemanticsNode? = all.first { text(it).length > 1000 }
            while (body != null && body.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) body = body.parent
            requireNotNull(body).config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() to it.maxValue() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Theme {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { AiReasoningContent(AiEntry("reasoning-scroll", "", reasoning = source), streaming = true,
                    onReadingInteraction = { interactions.incrementAndGet() }) }
            } } }
            await("reasoning auto follows received content") { runCatching { range().let { it.second > 0 && it.first >= it.second - 2 } }.getOrDefault(false) }
            assertEquals("Streaming updates do not count as reading interactions", 0, interactions.get())
            val initial = nodes { all -> text(all.first { text(it).length > 1000 }) }
            assertTrue(source.length > AiReasoningPreview.MAX_CHARS)
            assertTrue(initial.length <= AiReasoningPreview.MAX_CHARS)
            val bounds = nodes { all -> all.first { text(it).length > 1000 }.boundsInWindow }
            dragDown(bounds)
            await("actual drag pauses reasoning follow") { has(context.getString(R.string.ai_reading_reasoning_latest)) }
            var stableSince = SystemClock.uptimeMillis()
            var lastOffset = range().first
            val settling = mutableListOf<Float>()
            await("native gesture fling settles before checking a later stream update") {
                val offset = range().first
                settling += offset
                if (offset != lastOffset) { stableSince = SystemClock.uptimeMillis(); lastOffset = offset }
                SystemClock.uptimeMillis() - stableSince >= 300
            }
            println("Reasoning fling offsets before append: $settling")
            assertTrue("Inner reading must pause the outer conversation before its height changes", interactions.get() > 0)
            val before = range().first
            val frozen = nodes { all -> text(all.first { text(it).length > 1000 }) }
            instrumentation.runOnMainSync { source += "\n新增分析：接收后续内容仍保留当前阅读位置。".repeat(120) }
            Thread.sleep(300)
            assertEquals("New reasoning must not hijack the reading position", before, range().first, 3f)
            assertEquals("A >12K live tail cannot replace the actual text being read", frozen,
                nodes { all -> text(all.first { text(it).length > 1000 }) })
            click(R.string.ai_reading_reasoning_latest)
            await("latest releases the frozen snapshot") { nodes { all -> all.any { text(it).endsWith("新增分析：接收后续内容仍保留当前阅读位置。") } } }
            await("latest resumes follow and removes the pause control") {
                range().let { it.first >= it.second - 2 } && !has(context.getString(R.string.ai_reading_reasoning_latest))
            }
            val interactionsAfterResume = interactions.get()
            val rangeBeforeAppend = range()
            // >12K of short independent lines replaces the complete rolling window, making the
            // measured content substantially taller than the prior long-line source at 360dp.
            instrumentation.runOnMainSync { source += (1..2000).joinToString("\n", prefix = "\n", postfix = "\n") { "末尾${it.toString().padStart(4, '0')}" } }
            try {
                await("subsequent streamed lines are visible and automatically followed in the bounded preview") {
                    nodes { all -> all.any { text(it).endsWith("末尾2000\n") } } &&
                        range().let { it.second > rangeBeforeAppend.second && it.first >= it.second - 2 } &&
                        !has(context.getString(R.string.ai_reading_reasoning_latest))
                }
                assertEquals("Programmatic follow must not be reported as a new user scroll", interactionsAfterResume, interactions.get())
            } finally {
                println("Reasoning resumed preview: beforeRange=$rangeBeforeAppend, afterRange=${range()}, " +
                    "newTailShown=${nodes { all -> all.any { text(it).endsWith("末尾2000\n") } }}, " +
                    "pausedButton=${has(context.getString(R.string.ai_reading_reasoning_latest))}, " +
                    "readingInteractions=$interactionsAfterResume -> ${interactions.get()}")
            }
        }
    }

    @Test(timeout = 120000) fun numericErrorIsLocalAndSaveRemainsReachableWithRealKeyboard() {
        AiDeviceTest.Fixture(context).use { fixture ->
            // Editing uses the catalog identity/metadata actually stored for this local service.
            // A made-up origin would correctly trigger credential-origin protection on save.
            val original = requireNotNull(fixture.config.catalog().active)
            val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = File(fixture.root, "ui-files").apply { mkdirs() } }
            val controller = AiController(isolated, { File(fixture.root, "project") }, { _, _ -> false },
                { _, _ -> error("No execution") }, {}, {}, fixture.transport, fixture.config,
                permissionStore = AiPermissionStore(isolated, "reading-ui-permissions"), history = fixture.history)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { Theme { AiModelEditor(controller, original) {} } } }
                    await("model editor") { has(context.getString(R.string.ai_options_title)) }
                    await("valid draft initially allows saving") { nodes { all ->
                        var save: SemanticsNode? = all.last { text(it) == context.getString(R.string.editor_save) }
                        while (save != null && !save.config.contains(SemanticsActions.OnClick)) save = save.parent
                        save != null && !save.config.contains(SemanticsProperties.Disabled)
                    } }
                    click(R.string.ai_options_title)
                    await("advanced options") { has(context.getString(R.string.ai_options_frequency)) }
                    // Follow the actual user path: reveal a field before touching it. An OnClick
                    // semantics action on an offscreen node does not prove usable keyboard layout.
                    var imeTop = 0
                    var windowState = ""
                    var stableSince = SystemClock.uptimeMillis()
                    try {
                        await("Activity window focused before actual tap") {
                            var focused = false; scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }; focused
                        }
                        showField(R.string.ai_options_frequency)
                        tapField(R.string.ai_options_frequency, scenario)
                        await("actual touch focuses the visible numeric field") { nodes { all ->
                            field(all, R.string.ai_options_frequency).config.getOrNull(SemanticsProperties.Focused) == true
                        } }
                        nodes { all ->
                            assertTrue(field(all, R.string.ai_options_frequency).config[SemanticsActions.SetText].action?.invoke(AnnotatedString("3")) == true)
                        }
                        await("real keyboard shown") {
                            var shown = false; scenario.onActivity { shown = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }; shown
                        }
                        await("specific range error") { has(context.getString(R.string.ai_reading_number_penalty)) }
                        await("focused field settles above the fully shown IME") {
                            scenario.onActivity { activity ->
                                val view = activity.window.decorView
                                imeTop = view.height - requireNotNull(view.rootWindowInsets).getInsets(android.view.WindowInsets.Type.ime()).bottom
                            }
                            val observation = nodes { all ->
                                val field = field(all, R.string.ai_options_frequency)
                                val bounds = field.boundsInWindow
                                Triple("imeTop=$imeTop, bounds=$bounds, fieldHeight=${field.size.height}",
                                    bounds.height >= field.size.height * .98f && bounds.bottom <= imeTop, bounds.height > 0)
                            }
                            if (windowState != observation.first) { windowState = observation.first; stableSince = SystemClock.uptimeMillis() }
                            observation.second && observation.third && SystemClock.uptimeMillis() - stableSince >= 300
                        }
                    } finally {
                        scenario.onActivity { activity ->
                            val view = activity.window.decorView
                            val onScreen = IntArray(2)
                            val inWindow = IntArray(2)
                            view.getLocationOnScreen(onScreen); view.getLocationInWindow(inWindow)
                            windowState += "; activityWindowFocus=${view.hasWindowFocus()}, decorScreen=${onScreen.toList()}, " +
                                "decorWindow=${inWindow.toList()}, decorHeight=${view.height}, " +
                                "imeVisible=${view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())}, " +
                                "imeBottom=${view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom}"
                        }
                        windowState += nodes { all ->
                            val field = field(all, R.string.ai_options_frequency)
                            "; fieldBounds=${field.boundsInWindow}, fieldHeight=${field.size.height}, " +
                                "fieldFocus=${field.config.getOrNull(SemanticsProperties.Focused)}"
                        }
                        println("Actual model field / IME layout: $windowState")
                        val screenshots = File(context.cacheDir, "ui-review/reading-after").apply { mkdirs() }
                        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try { File(screenshots, "day-parameters-keyboard.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        finally { bitmap.recycle() }
                    }
                    nodes { all ->
                        val field = field(all, R.string.ai_options_frequency)
                        assertEquals("Invalid user input remains editable", "3", field.config.getOrNull(SemanticsProperties.EditableText)?.text)
                        assertNotNull(field.config.getOrNull(SemanticsProperties.Error))
                        var save: SemanticsNode? = all.last { text(it) == context.getString(R.string.editor_save) }
                        while (save != null && !save.config.contains(SemanticsActions.OnClick)) save = save.parent
                        assertNotNull(save?.config?.getOrNull(SemanticsProperties.Disabled))
                        assertTrue("Save stays above the IME", requireNotNull(save).boundsInWindow.bottom <= imeTop)
                        assertTrue("Field remains visible above the IME", field.boundsInWindow.height > 0 && field.boundsInWindow.bottom <= imeTop)
                    }
                    nodes { all ->
                        val field = field(all, R.string.ai_options_frequency)
                        assertTrue(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("2")) == true)
                    }
                    await("correcting the field removes error and enables save") { nodes { all ->
                        val field = field(all, R.string.ai_options_frequency)
                        var save: SemanticsNode? = all.lastOrNull { text(it) == context.getString(R.string.editor_save) }
                        while (save != null && !save.config.contains(SemanticsActions.OnClick)) save = save.parent
                        field.config.getOrNull(SemanticsProperties.EditableText)?.text == "2" &&
                            !field.config.contains(SemanticsProperties.Error) && save != null && !save.config.contains(SemanticsProperties.Disabled)
                    } }
                    assertFalse("Valid optional fields do not repeat range hints", has(context.getString(R.string.ai_reading_number_penalty)))
                    assertFalse(has(context.getString(R.string.ai_reading_number_seed)))
                    click(R.string.editor_save)
                    await("valid changed parameter persists through actual editor") { controller.models.state.value.catalog.profiles.any { it.id == original.id && it.parameters.frequencyPenalty == 2.0 } }
                    assertEquals(0, fixture.server.requestCount)
                }
            } finally { instrumentation.runOnMainSync { controller.stop(); controller.select(null) } }
        }
    }

    @Test(timeout = 150000) fun actualConversationExpansionPausesFollowAndLatestResumesForNextReply() = runBlocking {
        val base = context
        val root = File(base.cacheDir, "reading-workbench-${UUID.randomUUID()}")
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("${root.name}-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        try {
            AiDeviceTest.Fixture(isolated).use { fixture ->
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport,
                    aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("isolated workbench initialized") { !wb.state.value.loading }
                withContext(Dispatchers.Main) { wb.create("阅读位置测试") }
                await("isolated project created") { !wb.state.value.busy && wb.state.value.project != null }
                val project = requireNotNull(wb.state.value.project)
                val source = "```kotlin\n" + (1..200).joinToString("\n") { "val result$it = $it" } + "\n```"
                withContext(Dispatchers.IO) { fixture.history.save(project.id, AiState(turn = 1, phase = AiPhase.Completed,
                    timeline = listOf(AiEntry("reading-user", "展示需要审阅的长代码", role = AiRole.User, turn = 1),
                        AiEntry("reading-assistant", source, role = AiRole.Assistant, turn = 1)))) }
                withContext(Dispatchers.Main) { wb.ai.select(project.id) }
                await("isolated history ready") { wb.ai.state.value.timeline.size == 2 && !wb.ai.state.value.permissionsLoading && !wb.ai.state.value.restoring }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { Theme {
                        val state by wb.state.collectAsState()
                        AiPage(wb, state, onModels = {}, onProjects = {}, onTerminal = {})
                    } } }
                    await("collapsed code appears in actual conversation") { has(context.getString(R.string.ai_reading_code_expand)) }
                    await("initial conversation at bottom") { !has(context.getString(R.string.ai_latest)) }
                    click(R.string.ai_reading_code_expand)
                    await("expansion preserves reading instead of jumping to footer") { has(context.getString(R.string.ai_latest)) }
                    val range = nodes { all -> all.first { it.config.contains(SemanticsProperties.CollectionInfo) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                        .config[SemanticsProperties.VerticalScrollAxisRange].let { it.value() to it.maxValue() } }
                    assertTrue("Expanded conversation still has content below the current reading position", range.first < range.second)
                    click(R.string.ai_latest)
                    await("explicit latest reaches actual conversation end") { !has(context.getString(R.string.ai_latest)) }
                    fixture.enqueue("下一轮回复已显示，自动跟随恢复。")
                    withContext(Dispatchers.Main) { assertTrue(wb.ai.send("继续核对阅读位置")) }
                    await("next actual request completes") { !wb.ai.state.value.active && wb.ai.state.value.timeline.any { it.text == "下一轮回复已显示，自动跟随恢复。" } }
                    await("new assistant reply remains visible after latest resumes") { nodes { all -> all.any { text(it) == "下一轮回复已显示，自动跟随恢复。" && it.boundsInWindow.height > 0 } } }
                    assertFalse(has(context.getString(R.string.ai_latest)))
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                withContext(Dispatchers.IO) { root.deleteRecursively(); preferences.forEach(base::deleteSharedPreferences) }
            }
        }
    }

    @Test(timeout = 120000) fun oversizedMessageCodeCopyIsExplicitlyOnlyPreview() {
        val source = "```kotlin\n" + (1..5000).joinToString("\n") { "val line$it = \"source-$it\"" } + "\n```"
        assertTrue(source.length > AiTextPages.MARKDOWN_LIMIT)
        val expectedCode = (AiMarkdownDocument.parse(source.take(AiTextPages.MARKDOWN_LIMIT)).firstChild as org.commonmark.node.FencedCodeBlock).literal
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Theme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { AiMarkdown(source) {} }
            } } }
            await("oversize code clearly labels preview copy") { has(context.getString(R.string.ai_reading_code_copy_preview)) }
            assertFalse(has(context.getString(R.string.ai_copy_code)))
            assertTrue(has(context.getString(R.string.ai_reading_code_source_limited, context.getString(R.string.ai_full_text_open))))
            click(R.string.ai_reading_code_copy_preview)
            instrumentation.runOnMainSync { assertEquals(expectedCode,
                context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()) }
            assertTrue(expectedCode.length < source.length)
            click(R.string.ai_full_text_open)
            await("untruncated message snapshot opens separately") { has(context.getString(R.string.ai_full_text_title)) }
            assertTrue(has(context.getString(R.string.ai_full_text_range, 1, AiTextPages.ranges(source).first().last + 1, source.length)))
        }
    }
}

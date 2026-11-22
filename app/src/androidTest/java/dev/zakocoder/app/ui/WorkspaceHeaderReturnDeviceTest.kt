package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The complete workspace header must already exist behind the outgoing files page. */
@SdkSuppress(minSdkVersion = 30)
class WorkspaceHeaderReturnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T = main {
        val all = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            all += node
            if (!node.config.isClearingSemantics) node.children.forEach(::collect)
        }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(all)
    }

    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("\n") { it.text }.orEmpty()

    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        val display = base.resources.displayMetrics
        return bounds.width > 0f && bounds.height > 0f && bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < display.widthPixels && bounds.top < display.heightPixels
    }

    private fun matches(node: SemanticsNode, resource: Int): Boolean {
        val text = base.getString(resource)
        return label(node) == text || text in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun shown(resource: Int) = nodes { all -> all.any { visible(it) && matches(it, resource) } }
    private fun left(resource: Int) = nodes { all -> all.last { visible(it) && matches(it, resource) }.boundsInWindow.left }
    private fun windowCount() = main { WindowInspector.getGlobalWindowViews().count { it.isShown } }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) {
                "$message; visible=" + nodes { all -> all.filter(::visible).map(::label).filter(String::isNotEmpty).distinct() }
            }
            Thread.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }

    private fun click(resource: Int) = clickText(base.getString(resource))

    private fun clickText(text: String) {
        fun enabled(node: SemanticsNode) = visible(node) && generateSequence(node) { it.parent }.none {
            it.config.getOrNull(SemanticsProperties.Disabled) != null
        }
        fun action(node: SemanticsNode): (() -> Boolean)? {
            if (!enabled(node)) return null
            node.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            return node.children.firstNotNullOfOrNull(::action)
        }
        await("Enabled action missing: $text") {
            nodes { all ->
                val click = all.asReversed().asSequence().filter { visible(it) && (label(it) == text || text in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) }
                    .firstNotNullOfOrNull { node -> action(node) ?: generateSequence(node.parent) { it.parent }
                        .firstNotNullOfOrNull { parent -> if (enabled(parent)) parent.config.getOrNull(SemanticsActions.OnClick)?.action else null } }
                    ?: return@nodes false
                assertTrue("Action accepted: $text", click())
                true
            }
        }
    }

    private fun bounds(resource: Int): Rect = nodes { all ->
        all.last { visible(it) && matches(it, resource) }.boundsInWindow
    }

    private fun capture(dark: Boolean, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        // A newly attached Dialog may expose semantics before SurfaceFlinger presents it.
        Thread.sleep(200)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(base.cacheDir, "ui-review/workspace-header-return").apply { mkdirs() }
        File(directory, "${if (dark) "night" else "day"}-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return bitmap
    }

    private fun progress(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
        }
        Thread.sleep(80)
        scenario.onActivity {
            it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 600f, .85f, BackEventCompat.EDGE_LEFT))
        }
        Thread.sleep(150)
    }

    private fun assertTitlePixels(reference: Bitmap, actual: Bitmap, title: Rect, exposedRight: Float) {
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        assertTrue("Header title must be fully revealed during the gesture", title.right < exposedRight - 8f)
        // API 35 can round text-edge colors differently when the retained page is
        // composited behind the translating child. Allow only that small RGB drift;
        // compare the exact same coordinates and keep the 98% coverage requirement.
        fun samePixel(expected: Int, observed: Int) =
            kotlin.math.abs(Color.red(expected) - Color.red(observed)) <= 4 &&
                kotlin.math.abs(Color.green(expected) - Color.green(observed)) <= 4 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(observed)) <= 4
        var equal = 0
        var total = 0
        for (y in floor(title.top).toInt().coerceAtLeast(0) until ceil(title.bottom).toInt().coerceAtMost(reference.height)) {
            for (x in floor(title.left).toInt().coerceAtLeast(0) until ceil(title.right).toInt().coerceAtMost(reference.width)) {
                total++
                if (samePixel(reference.getPixel(x, y), actual.getPixel(x, y))) equal++
            }
        }
        assertTrue("Retained header title must not disappear or reflow ($equal/$total pixels match)",
            total > 0 && equal >= total * .98)
    }

    private fun assertPosition(resource: Int, reference: Rect) {
        val actual = bounds(resource)
        assertEquals("Title top remains stable", reference.top, actual.top, 1.5f)
        assertEquals("Title left remains stable", reference.left, actual.left, 1.5f)
        assertEquals("Title height remains stable", reference.height, actual.height, 1.5f)
    }

    /** Covered pages intentionally remove semantics. Check the revealed file's actual pixels instead. */
    private fun assertTranslatedFilePixels(reference: Bitmap, actual: Bitmap, file: Rect, dx: Float, dy: Float) {
        fun similar(a: Int, b: Int, tolerance: Int) =
            kotlin.math.abs(Color.red(a) - Color.red(b)) <= tolerance &&
                kotlin.math.abs(Color.green(a) - Color.green(b)) <= tolerance &&
                kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= tolerance
        val offsetX = dx.roundToInt()
        val offsetY = dy.roundToInt()
        val sourceLeft = ceil(file.left).toInt().coerceAtLeast(0)
        val sourceTop = ceil(file.top).toInt().coerceAtLeast(0)
        val sourceRight = floor(file.right).toInt().coerceAtMost(actual.width - offsetX - 3)
        val sourceBottom = floor(file.bottom).toInt().coerceAtMost(actual.height - offsetY - 3)
        assertTrue("A meaningful portion of the fixture filename is exposed", sourceRight - sourceLeft >= 40)
        val background = reference.getPixel(sourceLeft, sourceTop)
        var ink = 0
        var matching = 0
        for (y in sourceTop until sourceBottom) for (x in sourceLeft until sourceRight) {
            val expected = reference.getPixel(x, y)
            if (similar(expected, background, 70)) continue
            ink++
            var found = false
            // Subpixel layer translation can shift rasterized glyph edges by one or two pixels.
            for (oy in -2..2) for (ox in -2..2) {
                val targetX = x + offsetX + ox
                val targetY = y + offsetY + oy
                if (targetX in 0 until actual.width && targetY in 0 until actual.height &&
                    similar(expected, actual.getPixel(targetX, targetY), 35)) found = true
            }
            if (found) matching++
        }
        assertTrue("Reference contains identifiable filename ink ($ink pixels)", ink >= 40)
        assertTrue("The actual file is drawn in the translated workspace, not a blank placeholder ($matching/$ink ink pixels)",
            matching >= ink * .85)
    }

    private fun isolated(block: (Workbench) -> Unit) = runBlocking {
        warmDeviceActivity()
        val token = "workspace-header-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, token).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val original = base
        val isolated = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences(
                "$token-$name".also { synchronized(names) { names += it } }, mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var host: Workbench? = null
        try {
            withContext(Dispatchers.IO) {
                assertTrue(isolated.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit())
                OnboardingRepository(isolated).save(OnboardingProgress(completed = true, environmentConfirmed = false))
            }
            val workbench = main { Workbench(isolated) }.also { host = it }
            await("Isolated workbench initialized") {
                !workbench.state.value.loading && !workbench.ai.models.state.value.loading &&
                    workbench.capabilityPackages.state.value.loaded && workbench.deviceAccess.state.value.loaded
            }
            block(workbench)
        } finally {
            withContext(NonCancellable) {
                main { host?.stopAll(); host?.browser?.close() }
                delay(250)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(directory)
                    synchronized(names) { names.toList() }.forEach(original::deleteSharedPreferences)
                }
            }
        }
    }

    private fun tagged(tag: String) = nodes { all ->
        all.any { visible(it) && it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    }

    private fun taggedBounds(tag: String): Rect = nodes { all ->
        all.last { visible(it) && it.config.getOrNull(SemanticsProperties.TestTag) == tag }.boundsInWindow
    }

    private fun setApp(scenario: ActivityScenario<MainActivity>, host: Workbench, dark: Boolean) {
        val mountedRoot = "workspace-header-root-${UUID.randomUUID()}"
        scenario.onActivity { activity -> activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                Box(Modifier.fillMaxSize().testTag(mountedRoot)) { ZakoCoderApp(host) }
            }
        } }
        // Do not interact with MainActivity's previous app tree before setContent commits.
        await("Isolated root mounted") { tagged(mountedRoot) }
        await("Assistant root ready") { tagged("ai-message-viewport") && shown(R.string.app_navigation_open) }
        settle()
    }

    private fun createFixture(host: Workbench, name: String): String {
        main { host.create(name) }
        await("Isolated fixture created") { !host.state.value.busy && host.state.value.projects.size == 1 }
        val id = host.state.value.projects.single().id
        await("Fixture selection is ready") { !host.ai.state.value.permissionsLoading && !host.ai.state.value.restoring }
        main { assertTrue("Open isolated fixture", host.openWorkspace(id)) }
        await("Fixture conversation ready") {
            host.ai.state.value.projectId == id && !host.ai.state.value.permissionsLoading && !host.ai.state.value.restoring
        }
        return id
    }

    private fun returnToConversation(scenario: ActivityScenario<MainActivity>) {
        if (shown(R.string.editor_back)) editorBack(scenario) else {
            progress(scenario)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        await("Workspace Back returns directly to conversation") {
            tagged("ai-message-viewport") && !shown(R.string.back_to_library) && !shown(R.string.editor_back)
        }
        settle()
        assertTrue("Conversation return must not detour through the workspace library", !shown(R.string.workspace_flow_open))
    }

    /** Inline workspace editors share the Activity dispatcher; independent editors may use a Dialog. */
    private fun editorBack(scenario: ActivityScenario<MainActivity>, predictive: Boolean = true) {
        var activityFocused = false
        scenario.onActivity { activityFocused = it.hasWindowFocus() }
        if (activityFocused) {
            if (predictive) progress(scenario)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            return
        }
        val dispatcher = main {
            val root = WindowInspector.getGlobalWindowViews().last { it.isShown && it.hasWindowFocus() }
            fun ownerView(view: View): View? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) repeat(view.childCount) { child ->
                    ownerView(view.getChildAt(child))?.let { return it }
                }
                return null
            }
            requireNotNull(requireNotNull(ownerView(root)).findViewTreeNavigationEventDispatcherOwner()).navigationEventDispatcher
        }
        val input = DirectNavigationEventInput()
        main { dispatcher.addInput(input) }
        try {
            if (predictive) {
                main { input.backStarted(NavigationEvent(touchY = 600f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
                Thread.sleep(80)
                main { input.backProgressed(NavigationEvent(touchY = 600f, progress = .85f, swipeEdge = NavigationEvent.EDGE_LEFT)) }
                Thread.sleep(150)
            }
            main { input.backCompleted() }
        } finally { main { dispatcher.removeInput(input) } }
    }

    /** Physical motion in the message viewport, away from the Android system-back edges. */
    private fun swipeMessages(left: Boolean) {
        val viewport = taggedBounds("ai-message-viewport")
        val startX = viewport.left + viewport.width * if (left) .75f else .25f
        val endX = viewport.left + viewport.width * if (left) .25f else .75f
        val y = viewport.top + viewport.height * .65f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).let { event ->
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.sendPointerSync(event)
                } finally { event.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN, startX)
        repeat(12) { step ->
            Thread.sleep(16)
            send(MotionEvent.ACTION_MOVE, startX + (endX - startX) * (step + 1) / 12f)
        }
        send(MotionEvent.ACTION_UP, endX)
        instrumentation.waitForIdleSync()
    }

    @Test(timeout = 90_000)
    fun saveAndCloseNotifiesOnlyAfterSuccessfulClosure() = isolated { host ->
        val id = createFixture(host, "editor-close-callback-fixture")
        val root = host.projects.directory(id)
        fun write(path: String, text: String) = runBlocking(Dispatchers.IO) {
            File(root, path).writeText(text)
        }
        fun read(path: String) = runBlocking(Dispatchers.IO) { File(root, path).readText() }
        fun open(path: String) {
            await("Editor ready to open $path") { !host.editor.state.value.loading && !host.editor.state.value.saving }
            main { host.editor.open(id, path) }
            await("Editor loaded $path") {
                host.editor.state.value.path == path && host.editor.state.value.document != null && !host.editor.state.value.loading
            }
        }

        write("saved.txt", "before\n")
        open("saved.txt")
        val savedCalls = AtomicInteger()
        val savedCallbackProject = AtomicReference("not-called")
        main {
            val doc = requireNotNull(host.editor.state.value.document)
            doc.content.replace(0, doc.content.length, "saved draft\n")
            host.editor.changed()
            assertTrue("Save-and-close starts with a dirty buffer", host.editor.state.value.dirty)
            host.editor.save(closeAfter = true, onClosed = {
                savedCallbackProject.set(host.editor.state.value.projectId ?: "closed")
                savedCalls.incrementAndGet()
            })
        }
        await("Successful dirty save closes and notifies") {
            savedCalls.get() == 1 && host.editor.state.value.projectId == null && !host.editor.state.value.saving
        }
        assertEquals("Dirty save callback observes the already-closed buffer", "closed", savedCallbackProject.get())
        assertEquals("Dirty save persists exact content before closing", "saved draft\n", read("saved.txt"))
        settle()
        assertEquals("Dirty save callback fires exactly once", 1, savedCalls.get())

        write("conflict.txt", "original\n")
        open("conflict.txt")
        main {
            val doc = requireNotNull(host.editor.state.value.document)
            doc.content.replace(0, doc.content.length, "local draft\n")
            host.editor.changed()
            // Keep the periodic autosaver from racing this deliberate external-write fixture.
            doc.autosaveEnabled = false
        }
        write("conflict.txt", "external change\n")
        val conflictCalls = AtomicInteger()
        main { host.editor.save(closeAfter = true, onClosed = { conflictCalls.incrementAndGet() }) }
        await("Baseline conflict rejects save-and-close") {
            !host.editor.state.value.saving && host.editor.state.value.conflict
        }
        assertEquals("Conflicting save must not invoke navigation", 0, conflictCalls.get())
        assertEquals("Conflicting save retains its workspace", id, host.editor.state.value.projectId)
        assertEquals("Conflicting save retains its file", "conflict.txt", host.editor.state.value.path)
        assertTrue("Conflicting save retains the dirty draft", host.editor.state.value.dirty)
        assertEquals("Conflicting save retains draft content", "local draft\n", host.editor.state.value.document?.content.toString())
        assertEquals("Conflicting save preserves the external disk edit", "external change\n", read("conflict.txt"))

        write("clean.txt", "clean content\n")
        open("clean.txt")
        val cleanCalls = AtomicInteger()
        val cleanCallbackProject = AtomicReference("not-called")
        main {
            assertTrue("Clean closure starts without changes", !host.editor.state.value.dirty)
            host.editor.save(closeAfter = true, onClosed = {
                cleanCallbackProject.set(host.editor.state.value.projectId ?: "closed")
                cleanCalls.incrementAndGet()
            })
        }
        await("Clean save-and-close notifies after closure") {
            cleanCalls.get() == 1 && host.editor.state.value.projectId == null
        }
        assertEquals("Clean callback observes an already-closed buffer", "closed", cleanCallbackProject.get())
        assertEquals("Clean closure preserves disk content", "clean content\n", read("clean.txt"))
        settle()
        assertEquals("Clean close callback fires exactly once", 1, cleanCalls.get())
        assertEquals("Failed save never gains a delayed close callback", 0, conflictCalls.get())
    }

    @Test(timeout = 180_000)
    fun assistantWorkspaceAndRestoredEditorBackReturnDirectlyToConversation() {
        for (dark in listOf(false, true)) isolated { host ->
            val name = "assistant-workspace-fixture"
            val id = createFixture(host, name)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setApp(scenario, host, dark)
                clickText(name)
                await("Conversation header opens workspace files") { shown(R.string.back_to_library) && shown(R.string.project_root) }
                capture(dark, "assistant-files").recycle()
                returnToConversation(scenario)
                runBlocking(Dispatchers.IO) {
                    File(host.projects.directory(id), "return-fixture.txt").writeText("Workspace return fixture\n")
                }
                clickText(name)
                await("File listing ready") { shown(R.string.back_to_library) }
                clickText("return-fixture.txt")
                await("Fixture editor ready") {
                    shown(R.string.editor_back) && host.editor.state.value.path == "return-fixture.txt" && !host.editor.state.value.loading
                }
                capture(dark, "assistant-editor").recycle()
                returnToConversation(scenario)
                clickText(name)
                await("Reopening workspace restores last file") {
                    shown(R.string.editor_back) && host.editor.state.value.path == "return-fixture.txt" && !host.editor.state.value.loading
                }
                click(R.string.editor_file_actions)
                click(R.string.editor_find)
                await("Editor search opened") { tagged("editor-search-bar") }
                editorBack(scenario, predictive = false)
                await("First internal Back closes search while keeping the editor") {
                    !tagged("editor-search-bar") && shown(R.string.editor_back)
                }
                assertEquals("Internal Back preserves the opened file", "return-fixture.txt", host.editor.state.value.path)
                capture(dark, "assistant-editor-search-closed").recycle()
                returnToConversation(scenario)
                assertTrue("No model task started", !host.ai.state.value.active)
            }
        }
    }

    @Test(timeout = 180_000)
    fun messageViewportPhysicalSwipesOpenDrawerAndCurrentWorkspace() {
        for (dark in listOf(false, true)) isolated { host ->
            createFixture(host, "swipe-workspace-fixture")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setApp(scenario, host, dark)
                swipeMessages(left = false)
                await("Physical right swipe opens conversation sidebar") { shown(R.string.drawer_manage_workspaces) }
                settle()
                capture(dark, "message-right-swipe-drawer").recycle()
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await("Sidebar Back restores conversation") {
                    !shown(R.string.drawer_manage_workspaces) && tagged("ai-message-viewport")
                }
                settle()
                swipeMessages(left = true)
                await("Physical left swipe opens current workspace") { shown(R.string.back_to_library) && shown(R.string.project_root) }
                capture(dark, "message-left-swipe-files").recycle()
                returnToConversation(scenario)
                assertTrue("Gesture navigation starts no model task", !host.ai.state.value.active)
            }
        }
    }

    @Test(timeout = 180_000)
    fun shortWorkspaceSwipeRevealsActualFilesAndCancelsBackToConversation() {
        for (dark in listOf(false, true)) isolated { host ->
            val id = createFixture(host, "workspace-drag-fixture")
            val fileName = "visible-during-drag.txt"
            runBlocking(Dispatchers.IO) {
                File(host.projects.directory(id), fileName).writeText("Actual workspace preview fixture\n")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                setApp(scenario, host, dark)
                clickText("workspace-drag-fixture")
                await("Reference workspace contains the actual fixture file") {
                    nodes { all -> all.any { visible(it) && label(it) == fileName } }
                }
                settle()
                val referencePanel = taggedBounds("assistant-workspace-panel")
                val referenceFile = nodes { all -> all.last { visible(it) && label(it) == fileName }.boundsInWindow }
                val reference = capture(dark, "workspace-drag-reference")
                try {
                    returnToConversation(scenario)
                    assertTrue("Workspace panel begins closed", !tagged("assistant-workspace-panel"))
                    val initialWindows = windowCount()
                    val viewport = taggedBounds("ai-message-viewport")
                    val displayWidth = base.resources.displayMetrics.widthPixels.toFloat()
                    val startX = viewport.left + viewport.width * .75f
                    val y = viewport.top + viewport.height * .65f
                    val downTime = SystemClock.uptimeMillis()
                    var released = false
                    fun touch(action: Int, x: Float) {
                        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).let { event ->
                            try {
                                event.source = InputDevice.SOURCE_TOUCHSCREEN
                                instrumentation.sendPointerSync(event)
                            } finally { event.recycle() }
                        }
                    }
                    fun move(from: Float, to: Float) {
                        repeat(6) { step ->
                            Thread.sleep(16)
                            touch(MotionEvent.ACTION_MOVE, from + (to - from) * (step + 1) / 6f)
                        }
                    }
                    touch(MotionEvent.ACTION_DOWN, startX)
                    try {
                        val firstX = startX - displayWidth * .06f
                        move(startX, firstX)
                        await("Workspace becomes visible before the finger is released") {
                            tagged("assistant-workspace-panel")
                        }
                        val first = taggedBounds("assistant-workspace-panel")
                        assertTrue("Workspace slides in from the right: $first / $displayWidth", first.left > 0f && first.left < displayWidth)
                        val secondX = startX - displayWidth * .12f
                        move(firstX, secondX)
                        await("Continuing the left drag moves the same workspace panel") {
                            taggedBounds("assistant-workspace-panel").left < first.left - 8f
                        }
                        val second = taggedBounds("assistant-workspace-panel")
                        assertTrue("Incomplete drag keeps the workspace partially offscreen", second.left > 0f)
                        assertTrue("Left drag must never open the conversation sidebar", !shown(R.string.drawer_manage_workspaces))
                        assertEquals("Dragging adds no replacement window", initialWindows, windowCount())
                        val halfX = startX - displayWidth * .50f
                        move(secondX, halfX)
                        await("Halfway drag reveals a substantial portion of the workspace") {
                            taggedBounds("assistant-workspace-panel").left < displayWidth * .60f
                        }
                        val halfway = taggedBounds("assistant-workspace-panel")
                        capture(dark, "workspace-half-drag-in-progress").let { actual ->
                            try {
                                assertTranslatedFilePixels(reference, actual, referenceFile,
                                    halfway.left - referencePanel.left, halfway.top - referencePanel.top)
                            } finally { actual.recycle() }
                        }
                        move(halfX, secondX)
                        await("Dragging back brings the workspace below the completion threshold") {
                            taggedBounds("assistant-workspace-panel").left > displayWidth * .80f
                        }
                        capture(dark, "workspace-short-drag-in-progress").recycle()
                        // Pause before release so this small displacement cannot become a fast fling.
                        Thread.sleep(300)
                        touch(MotionEvent.ACTION_UP, secondX)
                        released = true
                        await("Short drag springs back to the conversation") {
                            !tagged("assistant-workspace-panel") && tagged("ai-message-viewport")
                        }
                        settle()
                        assertTrue("Cancelled drag leaves no sidebar", !shown(R.string.drawer_manage_workspaces))
                        assertTrue("Cancelled drag leaves no file page", !shown(R.string.back_to_library))
                        assertTrue("Cancelled drag leaves no editor", !shown(R.string.editor_back))
                        assertEquals("Cancelled drag preserves the selected workspace", id, host.ai.state.value.projectId)
                        assertTrue("Navigation does not start model work", !host.ai.state.value.active)
                        capture(dark, "workspace-short-drag-cancelled").recycle()
                    } finally {
                        if (!released) touch(MotionEvent.ACTION_CANCEL, startX)
                    }
                } finally { reference.recycle() }
            }
        }
    }

    @Test(timeout = 180_000)
    fun filesBackRetainsCompleteWorkspaceHeaderInBothThemes() = isolated { host ->
        main { host.create("workspace-header-fixture") }
        await("Isolated fixture created") { !host.state.value.busy && host.state.value.projects.size == 1 }
        val fixture = host.state.value.projects.single().id
        for (dark in listOf(false, true)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var stage = "root"
            try {
                setApp(scenario, host, dark)
                click(R.string.app_navigation_open)
                await("Workspace management ready") { shown(R.string.drawer_manage_workspaces) }
                click(R.string.drawer_manage_workspaces)
                await("Workspace library ready") { shown(R.string.workspace_flow_open) && shown(R.string.projects_title) }
                settle()
                val brand = bounds(R.string.app_name)
                val heading = bounds(R.string.projects_title)
                val reference = capture(dark, "library-before")
                val initialWindows = windowCount()
                try {
                    repeat(2) { attempt ->
                        stage = "files-$attempt"
                        click(R.string.workspace_flow_open)
                        await("Workspace files ready") { shown(R.string.back_to_library) && shown(R.string.project_root) }
                        settle()
                        val restingLeft = left(R.string.back_to_library)
                        val restingTop = bounds(R.string.back_to_library).top
                        capture(dark, "files-$attempt").recycle()
                        stage = "progress-$attempt"
                        progress(scenario)
                        val exposedRight = left(R.string.back_to_library)
                        assertTrue("Files track the current Back progress", exposedRight > restingLeft + 12f)
                        capture(dark, "progress-$attempt").let { actual ->
                            try {
                                assertTitlePixels(reference, actual, brand, exposedRight)
                                assertTitlePixels(reference, actual, heading, exposedRight)
                            } finally { actual.recycle() }
                        }
                        scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                        settle()
                        assertTrue("Cancelling Back preserves files", shown(R.string.back_to_library))
                        assertEquals("Cancellation restores file horizontal position", restingLeft, left(R.string.back_to_library), 1.5f)
                        assertEquals("Cancellation restores file top", restingTop, bounds(R.string.back_to_library).top, 1.5f)
                        assertEquals("Gesture creates no replacement windows", initialWindows, windowCount())
                        capture(dark, "cancelled-$attempt").recycle()
                        stage = "commit-$attempt"
                        progress(scenario)
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                        await("Back restores workspace library") {
                            shown(R.string.workspace_flow_open) && shown(R.string.projects_title) && !shown(R.string.back_to_library)
                        }
                        settle()
                        assertPosition(R.string.app_name, brand)
                        assertPosition(R.string.projects_title, heading)
                        capture(dark, "restored-$attempt").let { actual ->
                            try {
                                assertTitlePixels(reference, actual, brand, reference.width.toFloat())
                                assertTitlePixels(reference, actual, heading, reference.width.toFloat())
                            } finally { actual.recycle() }
                        }
                        assertEquals("Only the isolated fixture exists", listOf(fixture), host.state.value.projects.map { it.id })
                        assertTrue("Navigation must not start model work", !host.ai.state.value.active)
                    }
                    stage = "library-back"
                    progress(scenario)
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    await("Workspace management Back returns to conversation") {
                        tagged("ai-message-viewport") && !shown(R.string.workspace_flow_open)
                    }
                    capture(dark, "library-returned-to-conversation").recycle()
                } finally { reference.recycle() }
            } catch (failure: Throwable) {
                runCatching { capture(dark, "failure-$stage").recycle() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

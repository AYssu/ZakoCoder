package dev.zakocoder.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiLogRedactor
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Read-only review of the retained real-game conversation. No API call, project creation or shell build. */
class AiExistingConversationReviewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var evidence: File
    private val actions = JSONArray()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(15000) { while (!predicate()) delay(80) } }
        catch (error: TimeoutCancellationException) {
            if (::evidence.isInitialized) screenshot("failure-${label.take(45).replace(Regex("[^A-Za-z0-9]"), "-")}")
            throw AssertionError(label, error)
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(descendants(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::descendants)
    }
    private fun find(label: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString()?.lines()?.contains(label) == true ||
            it.contentDescription?.toString()?.lines()?.contains(label) == true)
    }
    private suspend fun tap(label: String) {
        await("Visible $label") { find(label) != null }
        var node = requireNotNull(find(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Enabled visible $label", node.refresh() && node.isEnabled && node.isVisibleToUser)
        val rect = Rect().also(node::getBoundsInScreen)
        assertFalse("Nonempty $label touch target", rect.isEmpty)
        touch(rect.centerX().toFloat(), rect.centerY().toFloat())
        delay(250)
    }
    private fun touch(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }
    private fun <T> semantics(block: (List<SemanticsNode>) -> T): T {
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
    private fun draft(): String? = semantics { all -> all.firstOrNull {
        it.config.getOrNull(SemanticsActions.SetText) != null
    }?.config?.getOrNull(SemanticsProperties.EditableText)?.text }
    private fun setDraft(text: String) = semantics { all ->
        val editor = all.single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue("Draft edit accepted", editor.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text)) == true)
    }
    private suspend fun openDrawer() {
        tap(context.getString(R.string.app_navigation_open))
        await("Drawer open") { find(context.getString(R.string.app_navigation_close)) != null }
        delay(600)
    }
    private suspend fun destination(label: Int) {
        openDrawer()
        semantics { all ->
            val title = context.getString(label)
            val target = all.mapNotNull { node ->
                if (node.config.getOrNull(SemanticsProperties.Text)?.none { it.text == title } != false) return@mapNotNull null
                var ancestor: SemanticsNode? = node
                while (ancestor != null) {
                    if (ancestor.config.getOrNull(SemanticsProperties.Selected) != null &&
                        ancestor.config.getOrNull(SemanticsActions.OnClick) != null) return@mapNotNull ancestor
                    ancestor = ancestor.parent
                }
                null
            }.firstOrNull()
            assertNotNull("Actual drawer destination: $title", target)
            assertTrue(requireNotNull(target).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        await("Drawer closes after selecting destination") { find(context.getString(R.string.app_navigation_close)) == null }
        delay(250)
    }
    private suspend fun screenshot(name: String) {
        delay(700)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(evidence, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }
    private fun conversationRange(): Pair<Float, Float>? = semantics { all ->
        all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            ?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.value() to it.maxValue() }
    }
    private suspend fun top() {
        forward(reverse = true) // Physical drag disables automatic following before accessible reposition.
        val started = semantics { all ->
            all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
                ?.config?.get(SemanticsActions.ScrollToIndex)?.action?.invoke(0) == true
        }
        assertTrue("Conversation provides accessible scroll to beginning", started)
        await("Conversation at first item") { conversationRange()?.first == 0f }
        delay(250)
    }
    /** Physical swipe traverses about half a viewport, retaining substantial overlap between screenshots. */
    private suspend fun forward(reverse: Boolean = false) {
        val node = nodes().filter { it.isVisibleToUser && it.isScrollable && !it.isEditable }
            .maxByOrNull { Rect().also(it::getBoundsInScreen).height() }
        val rect = Rect().also(requireNotNull(node)::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        val x = rect.centerX().toFloat()
        for (step in 0..12) {
            val y = rect.top + rect.height() * (if (reverse) .25f + .55f * step / 12 else .80f - .55f * step / 12)
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            delay(18)
        }
        delay(250)
    }
    private suspend fun captureConversation(theme: String) {
        top()
        var ended = false
        repeat(32) { index ->
            if (ended) return@repeat
            screenshot("$theme-conversation-${index.toString().padStart(2, '0')}")
            val range = requireNotNull(conversationRange())
            if (range.first >= range.second) ended = true else forward()
        }
        val range = requireNotNull(conversationRange())
        actions.put(JSONObject().put("theme", theme).put("conversationCaptureReachedEnd", range.first >= range.second)
            .put("lastPosition", range.first).put("maxPosition", range.second))
        assertTrue("All actual conversation content captured", ended)
    }
    private fun toolVisible(target: String, click: Boolean = false): Boolean = semantics { all ->
        val viewport = all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }?.boundsInWindow
            ?: return@semantics false
        val control = all.mapNotNull { node ->
            if (node.config.getOrNull(SemanticsProperties.Text)?.none { it.text == target } != false) return@mapNotNull null
            var parent: SemanticsNode? = node
            while (parent != null) {
                val action = parent.config.getOrNull(SemanticsActions.OnClick)
                if (action?.label in setOf(context.getString(R.string.ai_tool_details), context.getString(R.string.ai_tool_collapse))) {
                    val bounds = parent.boundsInWindow
                    if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) return@mapNotNull parent
                    return@mapNotNull null
                }
                parent = parent.parent
            }
            null
        }.firstOrNull() ?: return@semantics false
        if (click) control.config[SemanticsActions.OnClick].action?.invoke() == true else true
    }
    private suspend fun reviewToolDetails(theme: String) {
        for ((target, name) in listOf("main.cpp" to "write", "bash build.sh" to "build", "bash test.sh" to "test")) {
            top()
            var summary: String? = null
            for (attempt in 0..20) {
                summary = nodes().mapNotNull { it.text?.toString() }.firstOrNull {
                    it.contains(context.getString(R.string.ai_tool_write) + " 5") && it.contains(" · ")
                }
                if (summary != null) break
                forward()
            }
            assertNotNull("Actual successful tool group", summary)
            tap(requireNotNull(summary))
            screenshot("$theme-tools-$name-group")
            var found = false
            for (attempt in 0..20) {
                if (toolVisible(target)) { found = true; break }
                forward()
            }
            assertTrue("Actual tool target reachable: $target", found)
            assertTrue("Expand actual tool row", toolVisible(target, click = true))
            screenshot("$theme-tools-$name-detail-0")
            forward()
            screenshot("$theme-tools-$name-detail-1")
        }
    }
    private suspend fun reviewChanges(theme: String, fileCount: Int) {
        val label = context.getString(R.string.ai_file_changes_title)
        top()
        var found = false
        for (attempt in 0..14) {
            if (find(label) != null) { found = true; break }
            forward()
        }
        assertTrue("Actual compact change card is reachable", found)
        tap(label)
        await("Real change sheet visible") { find(context.getString(R.string.ai_file_changes_title)) != null }
        screenshot("$theme-changes-list")
        // The existing project contains only the model-created game files. Select its first visible file.
        val names = setOf("main.cpp", "README.md", "build.sh", "run.sh", "test.sh")
        val first = nodes().firstNotNullOfOrNull { node ->
            node.text?.toString()?.lines()?.firstOrNull { it in names }?.takeIf { node.isVisibleToUser }
        }
        assertNotNull("A real generated file is visible", first)
        tap(requireNotNull(first))
        await("Actual inline diff visible") { find(context.getString(R.string.ai_review_content)) != null }
        screenshot("$theme-first-file-diff")
        tap(context.getString(R.string.close))
        await("Change sheet dismissed") { find(context.getString(R.string.ai_review_content)) == null }
    }

    @Test(timeout = 240000) fun retainedRealGameConversationAndDrawerReview() = runBlocking {
        assertEquals("Default application only", "dev.zakocoder.app", context.packageName)
        val projectId = requireNotNull(InstrumentationRegistry.getArguments().getString("projectId"))
        require(java.util.UUID.fromString(projectId).toString() == projectId)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        await("Application ready") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading }
        assertFalse("Do not interrupt existing AI", workbench.ai.state.value.active)
        assertFalse("Do not interrupt operations", workbench.state.value.busy)
        assertFalse("Do not interrupt project command", workbench.projectTasks.state.value.busy)
        assertFalse("Do not interrupt Git", workbench.git.state.value.busy)
        val project = requireNotNull(workbench.state.value.projects.singleOrNull { it.id == projectId })
        assertTrue("Only the retained acceptance project may be reviewed", project.name.startsWith("C++小游戏验收"))
        val originalNight = shell("cmd uimode night").substringAfter(':').trim()
        require(originalNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        evidence = File(context.cacheDir, "ui-real-experience/review-${System.currentTimeMillis()}").apply { mkdirs() }
        var originalDraft: String? = null
        var restoredDraft = true
        var reportFailure: String? = null
        try {
            withContext(Dispatchers.Main) { workbench.select(projectId) }
            await("Retained project selected") { !workbench.state.value.busy && workbench.state.value.selectedId == projectId }
            withContext(Dispatchers.Main) { workbench.ai.select(projectId) }
            await("Retained conversation loaded") { workbench.ai.state.value.projectId == projectId &&
                !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading }
            InstrumentationRegistry.getArguments().getString("conversationId")?.let { conversation ->
                withContext(Dispatchers.Main) { workbench.ai.conversation(conversation) }
                await("Requested retained conversation loaded") { !workbench.ai.state.value.permissionsLoading &&
                    workbench.ai.state.value.conversationId == conversation }
            }
            assertTrue("Review actual generated assistant messages", workbench.ai.state.value.timeline.any { it.role == AiRole.Assistant })
            val initialRequests = workbench.ai.state.value.requests.map { it.id }
            val conversationId = workbench.ai.state.value.conversationId
            val checkpoints = workbench.ai.checkpointList()
            val checkpoint = requireNotNull(checkpoints.firstOrNull { it.conversation == conversationId && it.sealed })
            val fileCount = workbench.ai.checkpointChanges(checkpoint.id).size
            assertTrue("Existing real file changes", fileCount > 0)
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            warmDeviceActivity()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity.setContent { ZakoCoderApp(workbench) }
                }
                try {
                    destination(R.string.nav_ai)
                    await("Composer present") { draft() != null }
                    originalDraft = draft()
                    for (theme in listOf("day", "night")) {
                        shell("cmd uimode night ${if (theme == "night") "yes" else "no"}")
                        delay(800)
                        await("Navigation after configuration change") { find(context.getString(R.string.app_navigation_open)) != null }
                        destination(R.string.nav_ai)
                        await("Assistant after configuration change") { find(context.getString(R.string.ai_more)) != null }
                        if (InstrumentationRegistry.getArguments().getString("drawerOnly") != "true") {
                            captureConversation(theme)
                            if (InstrumentationRegistry.getArguments().getString("conversationOnly") != "true") {
                                reviewToolDetails(theme)
                                reviewChanges(theme, fileCount)
                            }
                        }
                        openDrawer()
                        screenshot("$theme-drawer")
                        tap(context.getString(R.string.app_navigation_close))
                        await("Explicit drawer close works") { find(context.getString(R.string.app_navigation_close)) == null }
                    }
                    assertEquals("No new API requests during read-only review", initialRequests, workbench.ai.state.value.requests.map { it.id })
                    assertEquals("Review keeps the original retained conversation", conversationId, workbench.ai.state.value.conversationId)
                } finally {
                    if (!restoredDraft && originalDraft != null) {
                        if (draft() == null) destination(R.string.nav_ai)
                        setDraft(originalDraft.orEmpty()); restoredDraft = true
                    }
                }
            }
        } catch (error: Throwable) {
            reportFailure = AiLogRedactor("").clean("${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            shell("cmd uimode night $originalNight")
            withContext(NonCancellable + Dispatchers.IO) {
                File(evidence, "review.json").writeText(JSONObject().put("projectId", projectId)
                    .put("failure", reportFailure).put("draftRestored", restoredDraft)
                    .put("originalNightMode", originalNight).put("actions", actions).toString(2))
            }
        }
    }
}

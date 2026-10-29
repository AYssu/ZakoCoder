package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import dev.zakocoder.app.ui.label
import dev.zakocoder.app.workflow.verification
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AiFilesUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val workbench get() = (context.applicationContext as ZakoApplication).workbench
    @Before fun windows() { warmDeviceActivity(); instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(60) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private fun reviewText(): String? {
        var result: String? = null
        fun walk(view: android.view.View) {
            if (view is io.github.rosemoe.sora.widget.CodeEditor && !view.isEditable && view.isShown) result = view.text.toString()
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::walk) }
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true || node.contentDescription?.toString()?.lines()?.contains(label) == true)) {
                var ancestor = node
                while (!ancestor.isClickable && !ancestor.isSelected && ancestor.parent != null) ancestor = ancestor.parent
                if (ancestor.isClickable || ancestor.isSelected) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.mapNotNull(::walk).firstOrNull()
    }
    private suspend fun click(label: String, long: Boolean = false) {
        val details = listOf(R.string.ai_requests, R.string.project_validation).map(context::getString)
        val rootTargets = listOf(R.string.ai_conversation, R.string.ai_changes_title, R.string.ai_more, R.string.ai_new_conversation,
            R.string.nav_workspace, R.string.nav_environment, R.string.nav_terminal, R.string.nav_settings, R.string.nav_ai).map(context::getString)
        if (label == context.getString(R.string.ai_skills)) {
            if (!visibleText(context.getString(R.string.ai_extensions))) {
                if (details.any(::visibleText) && find(context.getString(R.string.close)) != null) click(context.getString(R.string.close))
                click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_extensions))
            }
            await { visibleText(context.getString(R.string.ai_skills_detail)) }
            return
        }
        if (label in details && find(label) == null) {
            if (details.any(::visibleText) && find(context.getString(R.string.close)) != null) click(context.getString(R.string.close))
            click(context.getString(R.string.ai_more))
        }
        if (label in rootTargets && find(label) == null) {
            if (details.any(::visibleText) && find(context.getString(R.string.close)) != null) click(context.getString(R.string.close))
            if (visibleText(context.getString(R.string.ai_extensions)) && find(context.getString(R.string.ai_extensions_back)) != null) click(context.getString(R.string.ai_extensions_back))
        }
        try { await { find(label) != null } } catch (error: TimeoutCancellationException) { throw AssertionError("Clickable label not found: $label", error) }
        var node = find(label)!!
        if (long) { while (!node.isLongClickable && node.parent != null) node = node.parent }
        else { while (!node.isClickable && !node.isSelected && node.parent != null) node = node.parent }
        if (!long && node.isSelected && label in listOf(context.getString(R.string.ai_conversation), context.getString(R.string.ai_changes_title))) return
        assertTrue(label, node.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)); delay(300)
    }
    private fun visibleText(label: String): Boolean {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == label) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::walk) == true }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.any(::walk)
    }
    private suspend fun scrollProcess(forward: Boolean = true): Boolean {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        var candidate: AccessibilityNodeInfo? = null; var height = 0
        fun walk(node: AccessibilityNodeInfo) {
            val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
            if (node.isScrollable && node.isVisibleToUser && rect.height() > height) { candidate = node; height = rect.height() }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        instrumentation.uiAutomation.windows.mapNotNull { it.root }.forEach(::walk)
        val moved = candidate?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        delay(350)
        return moved
    }
    private suspend fun field(value: String, label: String? = null, keyboard: Boolean = false, revealEditable: Boolean = false) {
        var edit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.isEditable) {
                fun hasLabel(candidate: AccessibilityNodeInfo): Boolean = candidate.text?.toString()?.lines()?.contains(label) == true || candidate.contentDescription?.toString()?.lines()?.contains(label) == true || candidate.hintText?.toString()?.lines()?.contains(label) == true ||
                    (0 until candidate.childCount).any { index -> candidate.getChild(index)?.let(::hasLabel) == true }
                val matches = label == null || hasLabel(node)
                if (matches) edit = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        if (revealEditable) repeat(8) {
            edit = null
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.forEach(::walk)
            if (edit == null) scrollProcess()
        }
        try { await {
            edit = null
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.forEach(::walk)
            edit != null
        } } catch (error: TimeoutCancellationException) { throw AssertionError("Editable field not found: $label", error) }
        if (keyboard) { edit!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); edit!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(400) }
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })); delay(200)
    }
    private suspend fun reveal(label: String) {
        repeat(5) {
            if (find(label)?.isVisibleToUser == true) return
            scrollProcess()
        }
        assertNotNull(label, find(label))
    }
    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "system"
            val directory = File(context.cacheDir, "ui-review/$theme").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
    @Test(timeout = 90000) fun emptySetupOffersProjectAndModelRecoveryWithoutDownloads() = runBlocking {
        val base = File(context.cacheDir, "empty-setup-ui-${java.util.UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(base, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${base.name}-$name", mode)
        }
        lateinit var w: Workbench
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                main { w = Workbench(isolated) }; await { !w.state.value.loading && !w.editor.state.value.loading }
                scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                assertTrue(w.state.value.projects.isEmpty()); assertFalse(w.state.value.installed); assertFalse(w.ai.state.value.configured)
                click(context.getString(R.string.nav_ai)); screenshot("ux-empty-ai")
                click(context.getString(R.string.ai_select_project_first))
                assertTrue(visibleText(context.getString(R.string.library_empty_title)))
                click(context.getString(R.string.nav_environment)); click(context.getString(R.string.select_project_first))
                assertTrue(visibleText(context.getString(R.string.library_empty_title)))
                main { w.create("setup-owned-fixture") }; await { !w.state.value.busy && w.state.value.projects.size == 1 }
                click(context.getString(R.string.nav_ai)); await { !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                click(context.getString(R.string.ai_models_add_first))
                assertTrue(visibleText(context.getString(R.string.ai_settings)))
                assertFalse("No automatic runtime install", w.state.value.installed || w.state.value.busy)
                assertFalse("No automatic AI task", w.ai.state.value.active || w.ai.state.value.requests.isNotEmpty())
            }
        } finally { withContext(Dispatchers.IO) { base.deleteRecursively() } }
    }

    @Test(timeout = 90000) fun runningNotificationSettingsReflectSystemAndReturnWithoutStartingWork() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val sessions = workbench.state.value.sessions.map { it.id }
            val notifications = dev.zakocoder.app.settings.DevelopmentNotifications(context)
            fun label(): String = context.getString(notifications.status().label())
            click(context.getString(R.string.nav_settings))
            reveal(context.getString(R.string.running_notifications)); click(context.getString(R.string.running_notifications))
            assertTrue(visibleText(context.getString(R.string.notification_help)))
            assertTrue(visibleText(label())); screenshot("notification-status")
            click(context.getString(R.string.notification_system_settings))
            await { instrumentation.uiAutomation.windows.mapNotNull { it.root }.any { it.packageName?.toString()?.let { p -> p != context.packageName && (p.contains("settings") || p.contains("securitycenter")) } == true } }
            screenshot("notification-system-page")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await { visibleText(context.getString(R.string.notification_help)) && visibleText(label()) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await { visibleText(label()) }; screenshot("notification-returned")
            assertFalse(workbench.state.value.busy); assertFalse(workbench.ai.state.value.active)
            assertEquals(sessions, workbench.state.value.sessions.map { it.id })
            click(context.getString(R.string.close))
        }
    }
    @Test(timeout = 120000) fun guestEditsOfOpenFileAreVisibleWithoutDiscardingLocalBuffer() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "editor-external-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { val root = workbench.projects.directory(id); File(root, "a.py").writeText("print('original')\n"); File(root, "b.py").writeText("print('other')\n") }
                click("浏览文件"); click("a.py"); await { !workbench.editor.state.value.loading }
                val original = workbench.editor.state.value.document!!
                assertEquals(0, workbench.runtime.execute("printf '%s\\n' \"print('guest update')\" > a.py", id, workbench.state.value.settings, timeoutMillis = 30000, output = {}).exitCode)
                click(context.getString(R.string.editor_open_file)); click("b.py"); await { workbench.editor.state.value.path == "b.py" && !workbench.editor.state.value.loading }
                click("a.py"); await { !workbench.editor.state.value.diskChecking && workbench.editor.state.value.diskState == dev.zakocoder.app.editor.EditorDiskState.Changed }
                assertSame(original, workbench.editor.state.value.document); assertEquals("print('original')\n", original.content.toString())
                assertTrue(visibleText(context.getString(R.string.editor_disk_changed_clean))); screenshot("editor-external-clean")
                main { original.content.replace(0, original.content.length, "print('local draft')\n"); workbench.editor.changed() }
                assertEquals(0, workbench.runtime.execute("printf '%s\\n' \"print('guest newer')\" > a.py", id, workbench.state.value.settings, timeoutMillis = 30000, output = {}).exitCode)
                click(context.getString(R.string.editor_disk_check)); await { !workbench.editor.state.value.diskChecking }
                assertTrue(visibleText(context.getString(R.string.editor_disk_changed_dirty))); screenshot("editor-external-dirty")
                click(context.getString(R.string.editor_disk_compare)); await { visibleText(context.getString(R.string.editor_disk_review_help)) }
                await { reviewText()?.let { it.contains("@@") && it.contains("-print('local draft')") && it.contains("+print('guest newer')") } == true }
                screenshot("editor-external-diff"); click(context.getString(R.string.editor_disk_version))
                await { reviewText() == "print('guest newer')\n" }; screenshot("editor-external-disk")
                click(context.getString(R.string.editor_buffer_version)); await { reviewText() == "print('local draft')\n" }; screenshot("editor-external-buffer")
                click(context.getString(R.string.ai_review_diff)); await { reviewText()?.contains("@@") == true }
                click(context.getString(R.string.close)); assertEquals("print('local draft')\n", original.content.toString())
                click(context.getString(R.string.editor_disk_load)); screenshot("editor-external-reload-confirm"); click(context.getString(R.string.cancel))
                assertSame(original, workbench.editor.state.value.document); assertTrue(workbench.editor.state.value.dirty)
                assertEquals("print('guest newer')\n", withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "a.py").readText() })
                click(context.getString(R.string.editor_disk_load)); click(context.getString(R.string.editor_confirm_reload))
                await { !workbench.editor.state.value.loading && !workbench.editor.state.value.dirty }
                assertEquals("print('guest newer')\n", workbench.editor.state.value.document!!.content.toString())
                screenshot("editor-external-loaded")
                withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "a.py").delete() }
                click(context.getString(R.string.editor_disk_check)); await { !workbench.editor.state.value.diskChecking }
                assertTrue(visibleText(context.getString(R.string.editor_disk_unavailable))); screenshot("editor-external-missing")
                val retained = workbench.editor.state.value.document!!
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "a.py").writeText("print('while stopped')\n") }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await { !workbench.editor.state.value.diskChecking && workbench.editor.state.value.diskVersion?.text == "print('while stopped')\n" }
                assertSame(retained, workbench.editor.state.value.document); assertEquals("print('guest newer')\n", retained.content.toString())
                assertTrue(visibleText(context.getString(R.string.editor_disk_changed_clean))); screenshot("editor-external-resumed")
                withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "a.py").writeText("print('before recreation')\n") }
                scenario.recreate()
                await { !workbench.editor.state.value.diskChecking && workbench.editor.state.value.diskVersion?.text == "print('before recreation')\n" }
                assertSame(retained, workbench.editor.state.value.document); assertEquals("print('guest newer')\n", retained.content.toString())
                screenshot("editor-external-recreated")
            } finally {
                main { workbench.editor.state.value.tabs.filter { it.projectId == owned }.forEach { tab -> workbench.editor.open(tab.projectId, tab.path); workbench.editor.close() } }; delay(700)
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 90000) fun closingEditorTabKeepsOtherBufferAndConfirmsDirtyDiscard() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "tabs-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { val root = workbench.projects.directory(id); File(root, "a.kt").writeText("a original"); File(root, "b.kt").writeText("b original") }
                click("浏览文件"); click("a.kt"); await { !workbench.editor.state.value.loading }
                click(context.getString(R.string.editor_open_file)); click("b.kt"); await { workbench.editor.state.value.path == "b.kt" && !workbench.editor.state.value.loading }
                main { val doc = workbench.editor.state.value.document!!; doc.content.replace(0, doc.content.length, "b unsaved"); workbench.editor.changed() }
                click("a.kt"); click(context.getString(R.string.editor_close_tab))
                await { workbench.editor.state.value.path == "b.kt" && workbench.editor.state.value.tabs.size == 1 }
                assertTrue(workbench.editor.state.value.dirty)
                screenshot("ux-editor-close-tab")
                click(context.getString(R.string.editor_close_tab)); click(context.getString(R.string.editor_keep_editing))
                assertEquals("b unsaved", workbench.editor.state.value.document!!.content.toString())
                click(context.getString(R.string.editor_close_tab)); click(context.getString(R.string.editor_discard))
                await { workbench.editor.state.value.projectId == null && workbench.editor.state.value.tabs.isEmpty() }
                assertEquals("b original", withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "b.kt").readText() })
            } finally {
                main { workbench.editor.state.value.tabs.filter { it.projectId == owned }.forEach { tab -> workbench.editor.open(tab.projectId, tab.path); workbench.editor.close() } }; delay(600)
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 120000) fun commandDraftSurvivesRecreationWithoutSavingOrExecuting() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val previousAi = workbench.ai.state.value.projectId
            var owned: String? = null
            try {
                val name = "command-draft-${System.currentTimeMillis()}"
                main { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { check(File(workbench.projects.directory(id), "src").mkdir()) }
                main { workbench.ai.select(id) }
                await { workbench.ai.state.value.projectId == id && !workbench.ai.state.value.permissionsLoading }
                click(context.getString(R.string.nav_ai))
                click(context.getString(R.string.project_validation))
                click(context.getString(R.string.project_validation_commands))
                click(context.getString(R.string.project_command_configure))
                val script = "printf 'DRAFT_ONLY_DO_NOT_RUN\\n'\n" + "# retain command editing context\n".repeat(10)
                field(script, context.getString(R.string.project_command_script))
                scrollProcess()
                field("src", context.getString(R.string.project_command_cwd))
                field("not-a-number", context.getString(R.string.project_command_timeout))
                scenario.recreate(); delay(600)
                await { visibleText("not-a-number") }
                assertTrue(visibleText("src"))
                screenshot("command-draft-edit-restored")
                assertTrue("Restored draft must not save or execute", workbench.projectTasks.state.value.commands.isEmpty() && workbench.projectTasks.state.value.runs.isEmpty())
                assertFalse(workbench.projectTasks.state.value.busy)
                field("321", context.getString(R.string.project_command_timeout))
                click(context.getString(R.string.editor_save))
                await { workbench.projectTasks.state.value.commands.any { it.script == script && it.cwd == "src" && it.timeoutSeconds == 321 } }
                assertTrue(workbench.projectTasks.state.value.runs.isEmpty())
                click(context.getString(R.string.project_command_execute))
                scenario.recreate(); delay(600)
                assertTrue(visibleText(context.getString(R.string.project_commands)))
                assertNull("Execution confirmation is not restored", find(context.getString(R.string.cancel)))
                assertTrue(workbench.projectTasks.state.value.runs.isEmpty())
                assertFalse(workbench.projectTasks.state.value.busy)
                screenshot("command-draft-restored")
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
                main { workbench.ai.select(previousAi) }
            }
        }
    }

    @Test(timeout = 120000) fun commandConfigurationKeyboardKeepsSaveAndCancelReachable() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            suspend fun keyboardActions(stage: String) {
                await { instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                val keyboardTop = instrumentation.uiAutomation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .minOf { window -> android.graphics.Rect().also(window::getBoundsInScreen).top }
                for (label in listOf(context.getString(R.string.editor_save), context.getString(R.string.cancel))) {
                    var button = find(label) ?: throw AssertionError("$stage missing action $label")
                    while (!button.isClickable && button.parent != null) button = button.parent
                    val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
                    assertTrue("$stage $label above IME: $bounds / $keyboardTop", button.isVisibleToUser && bounds.top >= 0 && bounds.height() > 0 && bounds.bottom <= keyboardTop)
                }
                screenshot("command-keyboard-$stage")
            }
            fun tapAction(label: String) {
                var button = find(label)!!
                while (!button.isClickable && button.parent != null) button = button.parent
                val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
                val down = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            try {
                val name = "command-ime-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { check(File(workbench.projects.directory(id), "src").mkdir()) }
                click("浏览文件"); click("项目操作"); click(context.getString(R.string.project_commands))
                click(context.getString(R.string.project_command_configure))
                val script = "printf 'IME_DRAFT_ONLY\\n'\n" + "# a long command remains editable\n".repeat(12)
                field(script, context.getString(R.string.project_command_script), keyboard = true)
                keyboardActions("script")
                tapAction(context.getString(R.string.cancel))
                await { find(context.getString(R.string.cancel)) == null }
                assertTrue("Cancel does not persist or run", workbench.projectTasks.state.value.commands.isEmpty() && workbench.projectTasks.state.value.runs.isEmpty())
                click(context.getString(R.string.project_command_configure))
                field(script, context.getString(R.string.project_command_script))
                scrollProcess()
                field("src", context.getString(R.string.project_command_cwd), keyboard = true)
                keyboardActions("directory")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); delay(300)
                field("321", context.getString(R.string.project_command_timeout), keyboard = true)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MOVE_END)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_0)
                await { visibleText("3210") }
                keyboardActions("timeout")
                tapAction(context.getString(R.string.editor_save))
                await { workbench.projectTasks.state.value.commands.any { it.script == script && it.cwd == "src" && it.timeoutSeconds == 3210 } }
                assertTrue("Save does not execute", workbench.projectTasks.state.value.runs.isEmpty())
                assertFalse(workbench.projectTasks.state.value.busy)
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 150000) fun gitCommitFormSurvivesRecreationAndKeyboard() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "git-form-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "fixture.txt").writeText("git form fixture") }
                click("浏览文件"); click("项目操作"); click(context.getString(R.string.git_title))
                await { !workbench.git.state.value.busy }; click(context.getString(R.string.git_initialize)); click(context.getString(R.string.git_initialize))
                await { !workbench.git.state.value.busy && workbench.git.state.value.repository }
                assertTrue(visibleText(context.getString(R.string.git_untracked)))
                click("fixture.txt"); click(context.getString(R.string.git_stage))
                await { !workbench.git.state.value.busy && workbench.git.state.value.entries.any { it.index == 'A' } }
                click(context.getString(R.string.git_commit))
                val message = "feat: a clear local commit message\n" + "Describe the verified change and its intent.\n".repeat(12)
                field(message, context.getString(R.string.git_message))
                field("UX Test Author", context.getString(R.string.git_name))
                field("ux-test@example.invalid", context.getString(R.string.git_email))
                scenario.recreate(); delay(600)
                await { visibleText("UX Test Author") && visibleText("ux-test@example.invalid") }
                field("ux-test@example.invalid", context.getString(R.string.git_email), keyboard = true)
                await { instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                screenshot("ux-git-keyboard")
                val keyboardTop = instrumentation.uiAutomation.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .minOf { window -> android.graphics.Rect().also(window::getBoundsInScreen).top }
                for (label in listOf(context.getString(R.string.git_commit), context.getString(R.string.cancel))) {
                    var button = find(label)!!
                    while (!button.isClickable && button.parent != null) button = button.parent
                    val bounds = android.graphics.Rect().also(button::getBoundsInScreen)
                    assertTrue("$label must be fully above keyboard: $bounds / $keyboardTop", button.isVisibleToUser && bounds.top >= 0 && bounds.height() > 0 && bounds.bottom <= keyboardTop)
                }
                val cancelBounds = android.graphics.Rect().also { find(context.getString(R.string.cancel))!!.getBoundsInScreen(it) }
                val tapTime = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(tapTime, android.os.SystemClock.uptimeMillis(), action, cancelBounds.exactCenterX(), cancelBounds.exactCenterY(), 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                await { find(context.getString(R.string.git_email)) == null }
                delay(400); click(context.getString(R.string.close))
                assertTrue("Cancel does not commit staged files", workbench.git.state.value.entries.any { it.index == 'A' })
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 120000) fun unsentPromptSurvivesNavigationModelsAndActivityRecreation() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val previousAi = workbench.ai.state.value.projectId
            var owned: String? = null
            try {
                val name = "composer-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                owned = workbench.state.value.selectedId!!
                await { !workbench.ai.state.value.permissionsLoading }
                main { workbench.ai.select(owned) }; await { workbench.ai.state.value.projectId == owned && !workbench.ai.state.value.permissionsLoading }
                click(context.getString(R.string.nav_ai))
                val prompt = "UX_DRAFT_MARKER 请保留尚未发送的完整需求，切换页面后继续编辑。"
                field(prompt)
                click(context.getString(R.string.nav_environment)); click(context.getString(R.string.nav_ai))
                assertTrue("Unsent prompt survives bottom navigation", visibleText(prompt))
                click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_settings)); click(context.getString(R.string.ai_models_back))
                assertTrue("Unsent prompt survives model settings", visibleText(prompt))
                scenario.recreate(); delay(600)
                await { visibleText(prompt) }
                screenshot("ux-composer-restored")
                field(prompt, keyboard = true)
                await { instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                assertTrue("Composer send remains visible above keyboard", find(context.getString(R.string.ai_send))?.isVisibleToUser == true)
                screenshot("ux-composer-keyboard")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); delay(300)
                click(context.getString(R.string.ai_new_conversation)); await { !workbench.ai.state.value.permissionsLoading }
                assertFalse("New conversation does not inherit another conversation draft", visibleText(prompt))
                assertTrue("Test never sends a model request", !workbench.ai.state.value.active)
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                main { workbench.ai.select(previousAi); previous?.let(workbench::select) }
                await { !workbench.state.value.busy && !workbench.ai.state.value.permissionsLoading }
            }
        }
    }

    @Test(timeout = 120000) fun interruptedCheckpointConflictIsVisibleAndCurrentFilesRequireReview() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "recovery-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiHistory = f.history) }
                    await { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    val file = withContext(Dispatchers.IO) { File(w.projects.directory(id), "review.py").apply { writeText("before") } }
                    withContext(Dispatchers.IO) {
                        val cp = AiCheckpoints(f.history.checkpointDirectory, w.projects::directory)
                        cp.begin(id, w.ai.state.value.conversationId, 1)
                        file.writeText("after interrupted command")
                    }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai)); click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_checkpoints))
                    click(context.getString(R.string.ai_checkpoint_unsealed_row, 1))
                    await { visibleText("review.py") && find(context.getString(R.string.ai_checkpoint_restore)) != null }
                    assertTrue(visibleText(context.getString(R.string.ai_checkpoint_interrupted_review)))
                    screenshot("recovery-interrupted-review")
                    withContext(Dispatchers.IO) { file.writeText("later user edit") }
                    click(context.getString(R.string.ai_checkpoint_restore)); assertTrue(visibleText(context.getString(R.string.ai_checkpoint_interrupted_confirm)))
                    click(context.getString(R.string.ai_checkpoint_restore))
                    await { visibleText(context.getString(R.string.ai_checkpoint_conflict)) }
                    assertEquals("later user edit", withContext(Dispatchers.IO) { file.readText() })
                    screenshot("recovery-conflict-visible")
                    click(context.getString(R.string.ai_checkpoint_review_again))
                    await { visibleText("review.py") && !visibleText(context.getString(R.string.ai_checkpoint_conflict)) }
                    click(context.getString(R.string.ai_checkpoint_restore)); click(context.getString(R.string.ai_checkpoint_restore))
                    await { visibleText(context.getString(R.string.ai_checkpoint_restored)) }
                    assertEquals("before", withContext(Dispatchers.IO) { file.readText() })
                    assertFalse(visibleText(context.getString(R.string.ai_checkpoint_conflict)))
                    assertFalse(visibleText(context.getString(R.string.ai_checkpoint_unsealed)))
                    screenshot("recovery-completed")
                    assertEquals(0, f.server.requestCount)
                    click(context.getString(R.string.close))
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 120000) fun directoryChangesHaveDistinctReviewAndRestoreStructure() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "directory-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiHistory = f.history) }
                    await { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    withContext(Dispatchers.IO) {
                        val root = w.projects.directory(id); val source = File(root, "src").apply { mkdir() }
                        File(source, "empty").mkdir(); File(source, "a.py").writeText("original\n")
                        val cp = AiCheckpoints(f.history.checkpointDirectory, w.projects::directory)
                        val key = cp.begin(id, w.ai.state.value.conversationId, 1)
                        assertTrue(source.renameTo(File(root, "moved"))); cp.update(id, key, true)
                    }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai)); click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_checkpoints))
                    click(context.getString(R.string.ai_checkpoint_row, 1, 6))
                    val directory = context.getString(R.string.ai_checkpoint_directory_path, "src")
                    await { visibleText(directory) && find(context.getString(R.string.ai_checkpoint_restore))?.isEnabled == true }; screenshot("directory-change-list")
                    click(directory)
                    await { visibleText(context.getString(R.string.ai_review_directory)) }
                    screenshot("directory-permission-review")
                    click(context.getString(R.string.ai_review_after))
                    await { visibleText(context.getString(R.string.ai_review_directory_missing_after)) }
                    screenshot("directory-missing-review")
                    click(context.getString(R.string.close))
                    click(context.getString(R.string.ai_checkpoint_directory_path, "moved"))
                    click(context.getString(R.string.ai_review_before))
                    await { visibleText(context.getString(R.string.ai_review_directory_missing_before)) }
                    screenshot("directory-new-review")
                    click(context.getString(R.string.close))
                    click(context.getString(R.string.ai_checkpoint_restore)); click(context.getString(R.string.ai_checkpoint_restore))
                    await { visibleText(context.getString(R.string.ai_checkpoint_restored)) }
                    withContext(Dispatchers.IO) {
                        val root = w.projects.directory(id)
                        assertEquals("original\n", File(root, "src/a.py").readText())
                        assertTrue(File(root, "src/empty").isDirectory); assertFalse(File(root, "moved").exists())
                    }
                    assertEquals(0, f.server.requestCount); screenshot("directory-restored")
                    click(context.getString(R.string.close))
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 120000) fun discoveredRulesExplainDeferredWriteAndExposeActualSources() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "scoped-rules-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiHistory = f.history) }
                    await { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    withContext(Dispatchers.IO) {
                        val root = w.projects.directory(id); File(root, "src").mkdir()
                        File(root, "AGENTS.md").writeText("Inspect the actual source before editing.")
                        File(root, "src/AGENTS.md").writeText("Keep the public API and verify the changed file.")
                        File(root, "src/a.py").writeText("before\n")
                    }
                    val read = AiCall("read", "read_file", """{"path":"src/a.py","reason":"检查源文件"}""")
                    fun write(key: String) = AiCall(key, "write_file", """{"path":"src/a.py","content":"after\n","reason":"按子目录规则修改"}""")
                    f.enqueue(calls = listOf(read, write("early"))); f.enqueue(calls = listOf(write("retry")))
                    f.enqueue("已修改 src/a.py；本次未执行构建或测试。")
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai))
                    main { w.ai.send("检查并修改 src 下的源文件") }; await { w.ai.state.value.approval != null }
                    assertEquals(AiToolStatus.Incomplete, w.ai.state.value.events.single { it.callId == "early" }.status)
                    withContext(Dispatchers.IO) { assertEquals("before\n", File(w.projects.directory(id), "src/a.py").readText()) }
                    screenshot("scoped-rules-approval")
                    click(context.getString(R.string.ai_approve_once)); await { !w.ai.state.value.active }
                    repeat(5) { if (!visibleText(context.getString(R.string.ai_rules_deferred_visible))) scrollProcess(forward = false) }
                    assertTrue(visibleText(context.getString(R.string.ai_rules_deferred_visible))); screenshot("scoped-rules-deferred")
                    click(context.getString(R.string.ai_context_attach))
                    val sources = context.getString(R.string.ai_rules_sources, "AGENTS.md, src/AGENTS.md")
                    await { visibleText(sources) }; screenshot("scoped-rules-sources")
                    click(context.getString(R.string.close))
                    withContext(Dispatchers.IO) { assertEquals("after\n", File(w.projects.directory(id), "src/a.py").readText()) }
                    assertEquals("", w.ai.state.value.error)
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 180000) fun unsavedBuffersNeverMasqueradeAsValidatedDiskAndAreNotSentToModel() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null; var injected: Workbench? = null; var other: String? = null
            try {
                val name = "unsaved-validation-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiHistory = f.history); injected = w }
                    await { !w.state.value.loading && !w.editor.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    val foreign = withContext(Dispatchers.IO) { w.projects.create("owned foreign buffer fixture") }; other = foreign.id
                    withContext(Dispatchers.IO) {
                        File(w.projects.directory(id), "main.py").writeText("import time\nprint('DISK_VERSION_ONLY', flush=True)\ntime.sleep(2)\n")
                        File(w.projects.directory(id), "helper.py").writeText("# helper on disk\n")
                        File(w.projects.directory(id), "late.py").writeText("# late on disk\n")
                        File(w.projects.directory(foreign.id), "foreign.py").writeText("# foreign disk\n")
                    }
                    main { w.editor.open(foreign.id, "foreign.py") }; await { !w.editor.state.value.loading }
                    main { val doc = w.editor.state.value.document!!; doc.content.replace(0, doc.content.length, "FOREIGN_DRAFT_NOT_REFERENCED"); w.editor.changed(); w.editor.open(id, "main.py") }; await { !w.editor.state.value.loading }
                    main { val doc = w.editor.state.value.document!!; doc.content.replace(0, doc.content.length, "raise RuntimeError('LOCAL_DRAFT_NOT_REFERENCED')\n"); w.editor.changed(); w.editor.open(id, "helper.py") }; await { !w.editor.state.value.loading }
                    main { val doc = w.editor.state.value.document!!; doc.content.replace(0, doc.content.length, "# HELPER_DRAFT_NOT_REFERENCED\n"); w.editor.changed() }
                    main { w.editor.open(id, "late.py") }; await { !w.editor.state.value.loading }
                    val command = dev.zakocoder.app.workflow.ProjectCommand("test", "python3 main.py")
                    main { w.projectTasks.select(id); w.ai.setPolicy(w.ai.state.value.policy.copy(overrides = w.ai.state.value.policy.overrides + (AiTool.ReadFile to AiPermission.Deny))) }
                    await { !w.projectTasks.state.value.loading && !w.ai.state.value.permissionsSaving }
                    main { w.projectTasks.save(command) }; await { !w.projectTasks.state.value.saving }
                    f.enqueue("只检查磁盘版本，不宣称草稿已验证。")
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai)); main { w.ai.send("说明验证范围") }; await { !w.ai.state.value.active }
                    assertEquals("", w.ai.state.value.error)
                    val first = f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
                    assertTrue(first.contains("unsaved_files=2")); assertFalse(first.contains("LOCAL_DRAFT_NOT_REFERENCED")); assertFalse(first.contains("HELPER_DRAFT_NOT_REFERENCED")); assertFalse(first.contains("FOREIGN_DRAFT_NOT_REFERENCED"))
                    click(context.getString(R.string.project_validation))
                    val warning = context.getString(R.string.project_unsaved_validation, 2)
                    assertTrue(visibleText(warning)); screenshot("unsaved-validation-panel")
                    click(context.getString(R.string.project_validation_commands)); assertTrue(visibleText(warning)); screenshot("unsaved-command-page"); click(context.getString(R.string.close))
                    val rerun = context.getString(R.string.project_validation_rerun, context.getString(R.string.project_test))
                    reveal(rerun); click(rerun); assertTrue(visibleText(warning)); screenshot("unsaved-command-confirm"); click(context.getString(R.string.cancel))
                    assertTrue(w.projectTasks.state.value.runs.isEmpty()); assertEquals(3, w.editor.state.value.tabs.count { it.dirty })
                    click(rerun); click(context.getString(R.string.project_command_execute))
                    await { w.projectTasks.state.value.busy && w.projectTasks.state.value.log.contains("DISK_VERSION_ONLY") }
                    main { val doc = w.editor.state.value.document!!; doc.content.replace(0, doc.content.length, "# LATE_DRAFT_NOT_REFERENCED\n"); w.editor.changed() }
                    await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 1 }
                    val disk = w.projectTasks.state.value.runs.first(); assertEquals(0, disk.exit)
                    assertTrue(w.projectTasks.log(id, disk.id).contains("DISK_VERSION_ONLY"))
                    val lateWarning = context.getString(R.string.project_unsaved_validation, 3)
                    repeat(5) { if (!visibleText(lateWarning)) scrollProcess(false) }; assertTrue(visibleText(lateWarning)); screenshot("unsaved-disk-passed")
                    main { w.editor.save() }; await { !w.editor.state.value.saving }
                    main { w.editor.open(id, "helper.py") }; await { !w.editor.state.value.diskChecking }
                    main { w.editor.save() }; await { !w.editor.state.value.saving }
                    main { w.editor.open(id, "main.py") }; await { !w.editor.state.value.diskChecking }
                    assertTrue(w.editor.state.value.document!!.content.toString().contains("LOCAL_DRAFT_NOT_REFERENCED"))
                    main { w.editor.save() }; await { !w.editor.state.value.saving }
                    assertFalse(w.editor.state.value.dirty); assertEquals(1, w.editor.state.value.tabs.count { it.dirty })
                    assertFalse(visibleText(warning)); assertFalse(visibleText(lateWarning)); assertFalse(visibleText(context.getString(R.string.project_unsaved_validation, 1))); screenshot("unsaved-saved-panel")
                    reveal(rerun); click(rerun); assertFalse(visibleText(warning)); click(context.getString(R.string.project_command_execute))
                    await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 2 }
                    assertEquals(1, w.projectTasks.state.value.runs.first().exit)
                    assertTrue(w.projectTasks.log(id, w.projectTasks.state.value.runs.first().id).contains("LOCAL_DRAFT_NOT_REFERENCED"))
                    f.enqueue("当前项目已加载页签没有未保存修改，磁盘命令真实失败。")
                    main { w.ai.send("重新说明验证范围") }; await { !w.ai.state.value.active }
                    val next = f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
                    assertTrue(next.contains("unsaved_files=0")); assertFalse(next.contains("FOREIGN_DRAFT_NOT_REFERENCED"))
                    assertEquals(2, f.server.requestCount)
                }
            } finally {
                injected?.let { w ->
                    main { w.ai.stop(); w.editor.state.value.tabs.filter { it.projectId == owned || it.projectId == other }.forEach { tab -> w.editor.open(tab.projectId, tab.path); w.editor.close() } }; delay(700)
                    other?.let { id -> withContext(Dispatchers.IO) { dev.zakocoder.app.runtime.PrivateFiles.deleteTree(w.projects.directory(id)) } }
                }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 180000) fun failedValidationLongLogReturnsToDraftThenRepairIsReviewedAndVerified() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            assertTrue(workbench.state.value.installed)
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "validation-repair-${java.util.UUID.randomUUID()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    val permissionDirectory = withContext(Dispatchers.IO) { File(f.root, "permissions").apply { mkdirs() } }
                    val permissionContext = object : ContextWrapper(context) { override fun getFilesDir() = permissionDirectory }
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiPermissions = AiPermissionStore(permissionContext), aiHistory = f.history) }
                    try {
                        await { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                        val root = w.projects.directory(id)
                        val check = "from main import total\nprint('ACCEPTANCE_REPAIR_MARKER: total must be 1')\nprint('verbose log line\\n' * 2000)\nassert total() == 1, 'ACCEPTANCE_REPAIR_MARKER'\nprint('ACCEPTANCE_PASS_MARKER')\n"
                        withContext(Dispatchers.IO) { File(root, "main.py").writeText("def total(): return 0\n"); File(root, "check.py").writeText(check) }
                        main { w.projectTasks.select(id); w.ai.setPolicy(AiPolicy(AiPermission.Ask, setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.UpdatePlan).associateWith { AiPermission.Allow })) }
                        await { !w.projectTasks.state.value.loading && !w.ai.state.value.permissionsSaving }
                        val command = dev.zakocoder.app.workflow.ProjectCommand("test", "python3 check.py")
                        main { w.projectTasks.save(command) }; await { !w.projectTasks.state.value.saving }
                        f.enqueue(calls = listOf(AiCall("baseline", "run_command", JSONObject().put("command", command.shell()).put("reason", "执行配置的测试").toString())))
                        f.enqueue("测试退出 1，尚未修复，未运行构建。")
                        scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                        click(context.getString(R.string.nav_ai))
                        field("运行配置的测试，真实失败也请记录。源文件尚未修改。")
                        click(context.getString(R.string.ai_send))
                        await { w.ai.state.value.approval != null }; click(context.getString(R.string.ai_approve_once))
                        await { !w.ai.state.value.active }
                        assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                        val failedRun = w.projectTasks.state.value.runs.single()
                        assertEquals(1, failedRun.exit)
                        assertTrue(withContext(Dispatchers.IO) { w.projectTasks.log(id, failedRun.id).length > 16000 })
                        val conversation = w.ai.state.value.conversationId
                        val draft = "请根据失败日志继续修复，保留测试文件并重新验证。"
                        field(draft)
                        click(context.getString(R.string.project_validation))
                        reveal(context.getString(R.string.project_validation_open_log)); click(context.getString(R.string.project_validation_open_log))
                        await { visibleText(context.getString(R.string.project_run_log)) }
                        screenshot("validation-failed-long-log")
                        click(context.getString(R.string.project_log_reference_page))
                        await { w.ai.state.value.references.size == 1 }
                        val reference = w.ai.state.value.references.single()
                        assertEquals(id, w.ai.state.value.projectId)
                        assertEquals(conversation, w.ai.state.value.conversationId)
                        assertTrue(reference.text.length <= 6000 && reference.text.length > 5800)
                        assertTrue(reference.truncated)
                        val excerpt = reference.text.substringAfter('\n')
                        assertEquals("[run_id=${failedRun.id}; retained_offset=0; retained_end=${excerpt.length}]", reference.text.substringBefore('\n'))
                        assertTrue(excerpt.contains("ACCEPTANCE_REPAIR_MARKER"))
                        assertTrue("Unsent requirement survives returning from log", visibleText(draft))
                        assertFalse(w.ai.state.value.active)
                        assertEquals("Referencing must not send a request", 2, f.server.requestCount)
                        screenshot("validation-log-returned-to-draft")
                        withContext(Dispatchers.IO) { repeat(2) { assertNotNull(f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) } }
                        f.enqueue(calls = listOf(AiCall("read", "read_file", "{\"path\":\"main.py\"}")))
                        f.enqueue(calls = listOf(AiCall("fix", "write_file", JSONObject().put("path", "main.py").put("content", "def total(): return 1\n").put("reason", "修复实际测试失败").toString())))
                        f.enqueue(calls = listOf(AiCall("verify", "run_command", JSONObject().put("command", command.shell()).put("reason", "修复后重新测试").toString())))
                        f.enqueue("只修改 main.py，配置的测试退出 0；没有执行构建或运行命令。")
                        click(context.getString(R.string.ai_send))
                        await { w.ai.state.value.let { state -> state.approval != null && state.events.any { it.id == state.approval.eventId && it.tool == AiTool.WriteFile } } }
                        val sent = withContext(Dispatchers.IO) { f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8() }
                        assertTrue(sent.contains(draft)); assertTrue(sent.contains("ACCEPTANCE_REPAIR_MARKER")); assertTrue(sent.contains(failedRun.id))
                        assertTrue("Previous task context survives", sent.contains("源文件尚未修改"))
                        click(context.getString(R.string.ai_review_content))
                        await { reviewText()?.contains("return 1") == true }
                        screenshot("validation-repair-approval-diff"); click(context.getString(R.string.close))
                        withContext(Dispatchers.IO) { assertEquals("def total(): return 0\n", File(root, "main.py").readText()) }
                        click(context.getString(R.string.ai_approve_once))
                        await { w.ai.state.value.let { state -> state.approval != null && state.events.any { it.id == state.approval.eventId && it.tool == AiTool.RunCommand } } }; click(context.getString(R.string.ai_approve_once))
                        await { !w.ai.state.value.active }
                        assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                        val fixed = w.projectTasks.state.value.runs.first()
                        assertEquals(0, fixed.exit); assertEquals(2, fixed.turn); assertEquals(conversation, fixed.conversation)
                        withContext(Dispatchers.IO) { assertEquals(check, File(root, "check.py").readText()); assertEquals("def total(): return 1\n", File(root, "main.py").readText()) }
                        click(context.getString(R.string.project_validation))
                        await { !w.projectTasks.state.value.loading && !w.projectTasks.state.value.sourceChecking && w.projectTasks.state.value.currentSource != null }
                        assertEquals(dev.zakocoder.app.workflow.ProjectVerification.Unchanged, fixed.verification(w.projectTasks.state.value.currentSource))
                        // Read-only status text need not have a clickable ancestor; reveal() is for actions.
                        repeat(5) { if (!visibleText(context.getString(R.string.project_source_unchanged))) scrollProcess() }
                        assertTrue(visibleText(context.getString(R.string.project_source_unchanged)))
                        assertTrue(visibleText(context.getString(R.string.project_check_passed)))
                        screenshot("validation-repair-passed")
                        reveal("main.py"); click("main.py")
                        await { reviewText()?.contains("return 0") == true && reviewText()?.contains("return 1") == true }
                        screenshot("validation-repair-final-diff"); click(context.getString(R.string.close))
                        val restored = withContext(Dispatchers.IO) { AiHistoryStore(File(f.root, "history")).load(id).state!! }
                        assertEquals(w.ai.state.value.timeline, restored.timeline)
                        assertEquals(6, f.server.requestCount)
                    } finally {
                        withContext(NonCancellable) {
                            main { w.ai.stop(); w.projectTasks.stop() }
                            await { !w.ai.state.value.active && !w.projectTasks.state.value.busy }
                        }
                    }
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 180000) fun filePermissionOnlyChangeIsVisibleInValidationAndReloadedRecovery() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "mode-review-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                val source = "#!/bin/sh\necho MODE_REVIEW_OK\n"
                val script = File(workbench.projects.directory(id), "dev.sh")
                withContext(Dispatchers.IO) {
                    script.writeText(source)
                    java.nio.file.Files.setPosixFilePermissions(script.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
                }
                AiDeviceTest.Fixture(context).use { f ->
                    val permissions = withContext(Dispatchers.IO) { File(f.root, "permissions").apply { mkdirs() } }
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir() = permissions }
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiPermissions = AiPermissionStore(isolated), aiHistory = f.history) }
                    try {
                        await { !w.state.value.loading && w.ai.state.value.configured }
                        main { w.ai.select(id) }
                        await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                        f.enqueue(calls = listOf(AiCall("chmod", "run_command", JSONObject().put("command", "chmod 755 dev.sh").put("reason", "仅添加脚本执行权限").toString())))
                        f.enqueue("仅调整 dev.sh 权限，正文未变化。实际 chmod 退出 0，未运行脚本或测试。")
                        scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                        click(context.getString(R.string.nav_ai)); field("仅将 dev.sh 改为可执行，保留正文。")
                        click(context.getString(R.string.ai_send))
                        await { w.ai.state.value.approval != null }
                        assertEquals("rw-r--r--", withContext(Dispatchers.IO) { java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(script.toPath())) })
                        click(context.getString(R.string.ai_approve_once))
                        await { !w.ai.state.value.active }
                        assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                        assertTrue(w.ai.state.value.events.any { it.tool == AiTool.RunCommand && it.exitCode == 0 })
                        assertEquals(source, withContext(Dispatchers.IO) { script.readText() })
                        val checkpoint = w.ai.state.value.checkpointId!!
                        val change = w.ai.checkpointChanges(checkpoint).single()
                        assertEquals(change.before, change.after); assertEquals("rw-r--r--", change.beforeMode); assertEquals("rwxr-xr-x", change.afterMode)
                        val permissionLabel = context.getString(R.string.ai_review_file_permissions, "rw-r--r--", "rwxr-xr-x")
                        click(context.getString(R.string.project_validation)); reveal("dev.sh"); click("dev.sh")
                        await { visibleText(permissionLabel) && visibleText(context.getString(R.string.ai_review_body_unchanged)) && reviewText() == "" }
                        screenshot("file-mode-validation-diff")
                        click(context.getString(R.string.ai_review_before)); await { reviewText() == source }
                        assertTrue(visibleText(permissionLabel)); assertFalse(visibleText(context.getString(R.string.ai_review_body_unchanged)))
                        click(context.getString(R.string.ai_review_after)); await { reviewText() == source }
                        click(context.getString(R.string.close))
                        // New controller and history-store objects read persisted checkpoint metadata.
                        main { w = Workbench(context, f.transport, f.config, aiPermissions = AiPermissionStore(isolated), aiHistory = AiHistoryStore(File(f.root, "history"))) }
                        await { !w.state.value.loading && w.ai.state.value.configured }
                        main { w.ai.select(id) }
                        await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && w.ai.state.value.checkpointId == checkpoint }
                        scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                        click(context.getString(R.string.nav_ai)); click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_checkpoints))
                        click(context.getString(R.string.ai_checkpoint_row, 1, 1)); click("dev.sh")
                        await { visibleText(permissionLabel) && visibleText(context.getString(R.string.ai_review_body_unchanged)) && reviewText() == "" }
                        screenshot("file-mode-reloaded-checkpoint")
                        click(context.getString(R.string.close))
                        click(context.getString(R.string.ai_checkpoint_restore)); click(context.getString(R.string.ai_checkpoint_restore))
                        await { !w.ai.state.value.restoring && visibleText(context.getString(R.string.ai_checkpoint_restored)) }
                        withContext(Dispatchers.IO) {
                            assertEquals(source, script.readText())
                            assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(script.toPath())))
                        }
                        assertEquals(2, f.server.requestCount)
                    } finally {
                        withContext(NonCancellable) {
                            main { w.ai.stop(); w.projectTasks.stop() }
                            await { !w.ai.state.value.active && !w.projectTasks.state.value.busy }
                        }
                    }
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 180000) fun configuredCommandFromValidationBelongsToSelectedHistoricalTask() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "validation-origin-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    val permissions = withContext(Dispatchers.IO) { File(f.root, "permissions").apply { mkdirs() } }
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir() = permissions }
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiPermissions = AiPermissionStore(isolated), aiHistory = f.history) }
                    try {
                        await { !w.state.value.loading && w.ai.state.value.configured }
                        main { w.ai.select(id) }
                        await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                        f.enqueue("第一轮尚未配置命令，没有执行验证。")
                        main { w.ai.send("CONFIG_ORIGIN_FIRST 请记录第一轮开发任务") }
                        await { !w.ai.state.value.active && w.ai.state.value.turn == 1 }
                        f.enqueue("第二轮不执行命令。")
                        main { w.ai.send("记录第二轮，稍后复核第一轮") }
                        await { !w.ai.state.value.active && w.ai.state.value.turn == 2 }
                        assertEquals("", w.ai.state.value.error)
                        val conversation = w.ai.state.value.conversationId
                        scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                        click(context.getString(R.string.nav_ai)); click(context.getString(R.string.project_validation))
                        click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                        click(context.getString(R.string.project_validation_commands))
                        assertTrue(visibleText(context.getString(R.string.project_validation_turn, 1)))
                        assertTrue(visibleText(context.getString(R.string.project_validation_user_review)))
                        click(context.getString(R.string.project_command_configure))
                        val script = "python3 -c \"import os; print('CONFIG_TASK_EVIDENCE'); raise SystemExit(int(os.path.exists('fail.flag')))\""
                        field(script, context.getString(R.string.project_command_script))
                        click(context.getString(R.string.editor_save))
                        await { !w.projectTasks.state.value.saving && w.projectTasks.state.value.commands.size == 1 }
                        assertTrue(w.projectTasks.state.value.runs.isEmpty())
                        click(context.getString(R.string.project_command_execute)); click(context.getString(R.string.project_command_execute))
                        await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 1 }
                        val passed = w.projectTasks.state.value.runs.single()
                        assertEquals(0, passed.exit); assertEquals("build", passed.kind)
                        assertEquals("user", passed.source); assertEquals(conversation, passed.conversation); assertEquals(1, passed.turn)
                        click(context.getString(R.string.close))
                        repeat(5) { if (!visibleText(context.getString(R.string.project_validation_user_review))) scrollProcess() }
                        assertTrue(visibleText(context.getString(R.string.project_validation_user_review)))
                        repeat(5) { if (!visibleText(context.getString(R.string.project_check_passed))) scrollProcess(false) }
                        assertTrue("Selected historical task shows actual pass status", visibleText(context.getString(R.string.project_check_passed)))
                        screenshot("validation-configured-passed")
                        repeat(5) { if (find(context.getString(R.string.project_validation_commands))?.isVisibleToUser != true) scrollProcess(false) }
                        click(context.getString(R.string.project_validation_commands))
                        withContext(Dispatchers.IO) { File(w.projects.directory(id), "fail.flag").writeText("fixture failure") }
                        click(context.getString(R.string.project_command_execute)); click(context.getString(R.string.project_command_execute))
                        await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 2 }
                        val failed = w.projectTasks.state.value.runs.first()
                        assertEquals(1, failed.exit); assertEquals(conversation, failed.conversation); assertEquals(1, failed.turn)
                        click(context.getString(R.string.close))
                        repeat(5) { if (!visibleText(context.getString(R.string.project_check_failed))) scrollProcess() }
                        assertTrue(visibleText(context.getString(R.string.project_check_failed)))
                        screenshot("validation-configured-failed")
                        click(context.getString(R.string.ai_new_conversation))
                        await { !w.ai.state.value.permissionsLoading && w.ai.state.value.conversationId != conversation }
                        click(context.getString(R.string.project_validation))
                        assertFalse("Other conversation must not inherit validation results", visibleText(context.getString(R.string.project_validation_user_review)))
                        assertEquals(2, w.projectTasks.state.value.runs.size)
                        click(context.getString(R.string.nav_workspace)); click("浏览文件"); click("项目操作"); click(context.getString(R.string.project_commands))
                        assertFalse("Project entry has no task origin", visibleText(context.getString(R.string.project_validation_turn, 1)))
                        click(context.getString(R.string.project_command_execute)); click(context.getString(R.string.project_command_execute))
                        await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 3 }
                        val independent = w.projectTasks.state.value.runs.first()
                        assertEquals("", independent.conversation); assertEquals(0, independent.turn); assertEquals(1, independent.exit)
                        val persisted = withContext(Dispatchers.IO) { w.projectTasks.runs(id) }
                        assertEquals(listOf(0, 1, 1), persisted.map { it.turn })
                        assertEquals(listOf("", conversation, conversation), persisted.map { it.conversation })
                        assertEquals(2, f.server.requestCount)
                    } finally {
                        withContext(NonCancellable) {
                            main { w.ai.stop(); w.projectTasks.stop() }
                            await { !w.ai.state.value.active && !w.projectTasks.state.value.busy }
                        }
                    }
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 180000) fun verificationShowsStaleSourceAndConfirmedRerunBelongsToSelectedTurn() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            try {
                val name = "verification-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiHistory = f.history) }
                    await { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    withContext(Dispatchers.IO) { File(w.projects.directory(id), "main.py").writeText("before\n") }
                    main { w.projectTasks.select(id) }; await { !w.projectTasks.state.value.loading }
                    val command = dev.zakocoder.app.workflow.ProjectCommand("test", "python3 -c \"print('SOURCE_EVIDENCE_OK')\"")
                    main { w.projectTasks.save(command) }; await { !w.projectTasks.state.value.saving }
                    assertEquals("", w.projectTasks.state.value.error)
                    f.enqueue(calls = listOf(AiCall("verify", "run_command", JSONObject().put("command", command.shell()).put("reason", "执行已配置测试").toString())))
                    f.enqueue("配置的测试已执行，退出码为零。")
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai)); main { w.ai.send("执行当前配置测试") }
                    await { w.ai.state.value.approval != null }; click(context.getString(R.string.ai_approve_once)); await { !w.ai.state.value.active }
                    assertEquals("", w.ai.state.value.error)
                    click(context.getString(R.string.project_validation))
                    await { !w.projectTasks.state.value.loading && !w.projectTasks.state.value.sourceChecking && w.projectTasks.state.value.currentSource != null }
                    click(context.getString(R.string.project_source_details)); await { visibleText(context.getString(R.string.project_source_scope)) }
                    screenshot("verification-scope"); click(context.getString(R.string.close))
                    reveal(context.getString(R.string.project_source_unchanged)); screenshot("verification-current")
                    withContext(Dispatchers.IO) { File(w.projects.directory(id), "main.py").writeText("user changed\n") }
                    repeat(5) { if (find(context.getString(R.string.project_source_refresh))?.isVisibleToUser != true) scrollProcess(false) }
                    click(context.getString(R.string.project_source_refresh)); await { !w.projectTasks.state.value.sourceChecking }
                    reveal(context.getString(R.string.project_source_changed)); assertTrue(visibleText(context.getString(R.string.project_source_changed))); screenshot("verification-stale")
                    val rerun = context.getString(R.string.project_validation_rerun, context.getString(R.string.project_test))
                    repeat(5) { if (find(rerun)?.isVisibleToUser != true) scrollProcess(false) }
                    click(rerun); screenshot("verification-rerun-confirm"); click(context.getString(R.string.cancel))
                    assertEquals(1, w.projectTasks.state.value.runs.size)
                    click(rerun); click(context.getString(R.string.project_command_execute))
                    await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.size == 2 }
                    val reviewed = w.projectTasks.state.value.runs.first()
                    assertEquals("user", reviewed.source); assertEquals(w.ai.state.value.conversationId, reviewed.conversation); assertEquals(w.ai.state.value.turn, reviewed.turn)
                    assertEquals(0, reviewed.exit)
                    reveal(context.getString(R.string.project_validation_user_review)); screenshot("verification-rerun-result")
                    assertTrue(visibleText(context.getString(R.string.project_source_unchanged)))
                    withContext(Dispatchers.IO) { assertEquals("user changed\n", File(w.projects.directory(id), "main.py").readText()) }
                    main { w.projectTasks.save(command.copy(script = "python3 -c \"print('NEW_CONFIG')\"")) }; await { !w.projectTasks.state.value.saving }
                    repeat(5) { if (!visibleText(context.getString(R.string.project_source_command_changed))) scrollProcess(false) }
                    reveal(context.getString(R.string.project_source_command_changed)); screenshot("verification-command-changed")
                    assertEquals(2, w.projectTasks.state.value.runs.size)
                    assertEquals(2, f.server.requestCount)
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 180000) fun projectWorkflowCommandsGitTabsReferencesAndCheckpointUi() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null; var injected: Workbench? = null
            try {
                val name = "workflow-ui-${System.currentTimeMillis()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { val root = workbench.projects.directory(id); File(root, "a.kt").writeText("fun main() = Unit"); File(root, "b.py").writeText("print('before')"); File(root, "AGENTS.md").writeText("Inspect first, then verify actual changes.") }
                AiDeviceTest.Fixture(context).use { f ->
                    main { injected = Workbench(context, f.transport, f.config, aiHistory = f.history) }
                    val w = injected!!
                    await { !w.state.value.loading && w.ai.state.value.configured && !w.editor.state.value.loading }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click("浏览文件"); click("项目操作"); click(context.getString(R.string.project_commands))
                    await { !w.projectTasks.state.value.loading }
                    main { w.projectTasks.save(dev.zakocoder.app.workflow.ProjectCommand("build", "printf 'verified build fixture\\n'", timeoutSeconds = 1800)) }
                    await { w.projectTasks.state.value.commands.size == 1 }
                    screenshot("workflow-commands")
                    main { w.projectTasks.run(w.projectTasks.state.value.commands.single()) }
                    await { !w.projectTasks.state.value.busy && w.projectTasks.state.value.runs.isNotEmpty() }
                    assertEquals("passed", w.projectTasks.state.value.runs.first().status)
                    screenshot("workflow-command-result")
                    click(context.getString(R.string.project_build) + " · " + context.getString(R.string.project_check_passed))
                    click(context.getString(R.string.project_log_reference_page))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading }
                    assertTrue(w.ai.state.value.references.single().text.endsWith("verified build fixture\n")); assertTrue(w.ai.state.value.references.single().text.contains("retained_offset=0"))
                    assertTrue("Log reference navigates to AI composer", find(context.getString(R.string.ai_send)) != null)
                    screenshot("ux-log-reference")
                    main { w.ai.removeReference(0) }
                    click(context.getString(R.string.nav_workspace))
                    click("项目操作"); click("Git"); await { !w.git.state.value.busy }; click(context.getString(R.string.git_initialize)); click(context.getString(R.string.git_initialize))
                    await { !w.git.state.value.busy && w.git.state.value.repository }; screenshot("workflow-git"); click("关闭")
                    click("a.kt"); await { w.editor.state.value.document != null && !w.editor.state.value.loading }
                    click(context.getString(R.string.editor_open_file)); click("b.py")
                    await { w.editor.state.value.path == "b.py" && !w.editor.state.value.loading }
                    assertEquals(2, w.editor.state.value.tabs.size)
                    main { w.editor.state.value.document!!.content.replace(0, w.editor.state.value.document!!.content.length, "print('draft')"); w.editor.changed() }
                    scenario.onActivity { activity ->
                        fun locate(view: android.view.View): io.github.rosemoe.sora.widget.CodeEditor? {
                            if (view is io.github.rosemoe.sora.widget.CodeEditor) return view
                            if (view is android.view.ViewGroup) for (n in 0 until view.childCount) locate(view.getChildAt(n))?.let { return it }
                            return null
                        }
                        locate(activity.window.decorView)!!.setSelectionRegion(0, 7, 0, 12)
                    }
                    screenshot("workflow-editor-tabs")
                    click(context.getString(R.string.editor_reference_ai))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    assertEquals("draft", w.ai.state.value.references.single().text)
                    assertEquals("selection", w.ai.state.value.references.single().kind)
                    assertTrue(w.ai.state.value.references.single().unsaved)
                    screenshot("workflow-ai-reference")
                    f.enqueue(calls = listOf(AiCall("create", "write_file", "{\"path\":\"c.py\",\"content\":\"print('new')\",\"reason\":\"create fixture file\"}")))
                    f.enqueue("Created c.py. No build or test command executed in this AI task.")
                    main { w.ai.send("Create c.py using the attached draft as reference") }
                    await { w.ai.state.value.approval != null }; main { w.ai.approve(true) }; await { !w.ai.state.value.active }
                    assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                    click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_checkpoints)); await { visibleText(context.getString(R.string.ai_checkpoint_row, 1, 1)) }; screenshot("workflow-checkpoints"); click("关闭")
                    click(context.getString(R.string.project_validation)); screenshot("verification-workflow-entry")
                    repeat(5) { if (!visibleText(context.getString(R.string.project_validation_changes))) scrollProcess() }
                    screenshot("verification-workflow-scrolled")
                    assertTrue(visibleText(context.getString(R.string.project_validation_changes)))
                    repeat(4) { if (!visibleText("c.py")) scrollProcess() }
                    await { visibleText("c.py") }; screenshot("workflow-validation")
                    assertTrue(visibleText("c.py")); assertEquals(2, f.server.requestCount)
                    click("c.py"); await { visibleText(context.getString(R.string.ai_review_diff)) }
                    screenshot("coding-validation-diff"); click(context.getString(R.string.close))
                    val command = w.projectTasks.state.value.commands.single()
                    f.enqueue(calls = listOf(AiCall("build", "run_command", JSONObject().put("command", command.shell()).put("timeout_seconds", 1800).put("reason", "Run configured build fixture").toString())))
                    f.enqueue("Configured build exited 0; tests and run were not executed.")
                    main { w.ai.send("Execute the configured build command") }
                    await { w.ai.state.value.approval != null }; main { w.ai.approve(true) }; await { !w.ai.state.value.active }
                    assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                    repeat(4) { if (!visibleText(context.getString(R.string.project_check_passed))) scrollProcess(forward = false) }
                    await { visibleText(context.getString(R.string.project_check_passed)) }
                    assertFalse(visibleText("c.py"))
                    screenshot("workflow-validation-passed")
                    assertEquals("build", w.projectTasks.state.value.runs.first { it.source == "ai" }.kind)
                    assertEquals(4, f.server.requestCount)
                    reveal(context.getString(R.string.project_validation_open_log)); click(context.getString(R.string.project_validation_open_log))
                    await { visibleText(context.getString(R.string.project_run_log)) }
                    screenshot("coding-validation-log-before-search")
                    field("verified", context.getString(R.string.project_log_search)); click(context.getString(R.string.editor_find))
                    screenshot("coding-validation-log"); click(context.getString(R.string.close))
                    repeat(4) { if (!visibleText(context.getString(R.string.project_validation_turn, 2))) scrollProcess(forward = false) }
                    click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                    repeat(4) { if (!visibleText("c.py")) scrollProcess() }
                    await { visibleText("c.py") }; screenshot("coding-validation-history")
                    val creationCheckpoint = w.ai.checkpointList().single { it.turn == 1 }
                    assertTrue(withContext(Dispatchers.Main) { w.ai.restoreCheckpoint(creationCheckpoint.id) })
                    repeat(4) { if (!visibleText(context.getString(R.string.project_validation_turn, 1))) scrollProcess(forward = false) }
                    click(context.getString(R.string.project_validation_turn, 1)); click(context.getString(R.string.project_validation_turn, 2))
                    repeat(5) { if (!visibleText(context.getString(R.string.project_validation_restored))) scrollProcess(forward = false) }
                    await { visibleText(context.getString(R.string.project_validation_restored)) }
                    screenshot("verification-restored-context")
                    repeat(5) { if (!visibleText(context.getString(R.string.project_validation_historical))) scrollProcess() }
                    await { visibleText(context.getString(R.string.project_validation_historical)) }
                    assertEquals(0, w.projectTasks.state.value.runs.first { it.source == "ai" }.exit)
                    screenshot("recovery-historical-verification")
                    assertFalse(withContext(Dispatchers.IO) { File(w.projects.directory(id), "c.py").exists() })
                    f.enqueue(calls = listOf(AiCall("terminal", "start_terminal", JSONObject().put("command", "printf 'UI_TERMINAL_LINK\\n'; sleep 30").toString())))
                    f.enqueue("持续进程保留在终端。")
                    click(context.getString(R.string.ai_conversation))
                    main { w.ai.send("启动项目终端") }
                    await { w.ai.state.value.approval != null }; main { w.ai.approve(true) }; await { !w.ai.state.value.active }
                    val session = w.state.value.sessions.single { it.projectId == id }
                    click(context.getString(R.string.ai_open_terminal, session.number))
                    await { find(context.getString(R.string.terminal_actions)) != null }
                    assertTrue(w.terminal.sessions.single { it.id == session.id }.session.isRunning)
                    screenshot("coding-terminal-direct")
                    withContext(Dispatchers.Main) { w.terminal.close(session.id) }
                }
            } finally {
                withContext(Dispatchers.Main) { injected?.ai?.stop(); injected?.projectTasks?.stop(); injected?.git?.stop(); injected?.terminal?.sessions?.filter { it.projectId == owned }?.toList()?.forEach { injected?.terminal?.close(it.id) } }
                await { injected?.ai?.state?.value?.active != true && injected?.state?.value?.busy != true }
                main { injected?.editor?.let { editor -> editor.state.value.tabs.toList().forEach { tab -> editor.open(tab.projectId, tab.path); editor.close() } } }; delay(600)
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 180000) fun longLogTailIsOneTapVisibleAndReferencesActualFinalError() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            var owned: String? = null
            var markerDiagnostic = "not checked"
            var stage = 0
            suspend fun awaitStage(condition: () -> Boolean) {
                stage++
                instrumentation.sendStatus(0, Bundle().apply { putString("tailStage", stage.toString()) })
                try { await(condition) } catch (error: TimeoutCancellationException) { throw AssertionError("Long log stage $stage timed out", error) }
            }
            fun body(): AccessibilityNodeInfo? {
                fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isVisibleToUser && (node.text?.length ?: 0) > 1000) return node
                    return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
                }
                return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::walk)
            }
            fun markerOnScreen(marker: String): Boolean {
                var visible = false
                // Check actual native text layout and clipping, independent of accessibility extra-data
                // coordinates (the device returns null entries for this scrolled selection container).
                instrumentation.runOnMainSync {
                    fun inspectRoot(root: ViewRootForTest) {
                        val nodes = mutableListOf<SemanticsNode>()
                        fun collect(node: SemanticsNode) { nodes += node; node.children.forEach(::collect) }
                        collect(root.semanticsOwner.unmergedRootSemanticsNode)
                        val controlBottom = nodes.filter { node ->
                            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == context.getString(R.string.project_log_latest) } == true
                        }.maxOfOrNull { it.boundsInWindow.bottom } ?: return
                        for (node in nodes) {
                            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                            if (node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) != true) continue
                            for (layout in results) {
                                val index = layout.layoutInput.text.text.indexOf(marker)
                                if (index < 0) continue
                                // LayoutInfo.coordinates is the inner text coordinator; semantics
                                // position points to the scroll viewport before padding/translation.
                                val coordinates = node.layoutInfo.coordinates
                                if (!coordinates.isAttached) continue
                                val origin = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                                val clip = node.boundsInWindow
                                val boxes = (index until index + marker.length).map { layout.getBoundingBox(it).translate(origin) }
                                markerDiagnostic = "layout length=${layout.layoutInput.text.length}; index=$index; origin=$origin; clip=$clip; controlsBottom=$controlBottom; first=${boxes.first()}; last=${boxes.last()}"
                                if (clip.top >= controlBottom && boxes.all { it.width > 0 && it.height > 0 && it.left >= clip.left && it.right <= clip.right && it.top >= clip.top && it.bottom <= clip.bottom }) visible = true
                            }
                        }
                    }
                    fun walk(view: android.view.View) {
                        if (!view.isShown) return
                        if (view is ViewRootForTest) inspectRoot(view)
                        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
                    }
                    android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::walk)
                }
                return visible
            }
            try {
                val name = "log-tail-${java.util.UUID.randomUUID()}"
                main { workbench.create(name) }; awaitStage { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    val permissions = withContext(Dispatchers.IO) { File(f.root, "permissions").apply { mkdirs() } }
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir() = permissions }
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, aiPermissions = AiPermissionStore(isolated), aiHistory = f.history) }
                    try {
                        awaitStage { !w.state.value.loading && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                        val root = w.projects.directory(id)
                        withContext(Dispatchers.IO) {
                            File(root, "long_log.py").writeText("""
                                from pathlib import Path
                                import time
                                print("TAIL_LOG_HEAD", flush=True)
                                print("log line 0123456789\n" * 40000, end="", flush=True)
                                print("TAIL_LOG_STAGE_ONE", flush=True)
                                Path(".log-ready").touch()
                                while not Path(".log-release").exists(): time.sleep(.05)
                                print("TAIL_LOG_STAGE_TWO", flush=True)
                                Path(".log-advanced").touch()
                                while not Path(".log-finish").exists(): time.sleep(.05)
                                print("TAIL_LOG_FINAL_ERROR", flush=True)
                                raise SystemExit(1)
                            """.trimIndent())
                        }
                        f.enqueue("尚未运行测试。")
                        scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                        click(context.getString(R.string.nav_ai)); field("建立本次任务，稍后由我执行测试。")
                        click(context.getString(R.string.ai_send)); awaitStage { !w.ai.state.value.active && w.ai.state.value.turn == 1 }
                        main { w.projectTasks.select(id) }; awaitStage { !w.projectTasks.state.value.loading }
                        val command = dev.zakocoder.app.workflow.ProjectCommand("test", "python3 long_log.py", timeoutSeconds = 150)
                        main { w.projectTasks.save(command) }; awaitStage { !w.projectTasks.state.value.saving }
                        click(context.getString(R.string.project_validation)); click(context.getString(R.string.project_validation_rerun, context.getString(R.string.project_test)))
                        click(context.getString(R.string.project_command_execute))
                        withContext(Dispatchers.IO) { awaitStage { File(root, ".log-ready").exists() } }
                        assertTrue(w.projectTasks.state.value.busy)
                        val run = w.projectTasks.state.value.runs.single()
                        click(context.getString(R.string.project_validation_commands))
                        screenshot("long-log-command-live-overview")
                        assertTrue("Running log must open directly without scrolling past burst output", visibleText(context.getString(R.string.project_validation_open_log)))
                        click(context.getString(R.string.project_validation_open_log))
                        awaitStage { body()?.text?.startsWith("TAIL_LOG_HEAD") == true }
                        // New live output must not pull a reader away from the retained beginning.
                        withContext(Dispatchers.IO) { File(root, ".log-release").writeText(""); awaitStage { File(root, ".log-advanced").exists() } }
                        withContext(Dispatchers.IO) { withTimeout(20000) { while (!w.projectTasks.log(id, run.id).contains("TAIL_LOG_STAGE_TWO")) delay(60) } }
                        delay(1600)
                        assertTrue(body()?.text?.startsWith("TAIL_LOG_HEAD") == true)
                        screenshot("long-log-live-head-preserved")
                        click(context.getString(R.string.project_log_latest))
                        awaitStage { markerOnScreen("TAIL_LOG_STAGE_TWO") }
                        screenshot("long-log-live-tail")
                        withContext(Dispatchers.IO) { File(root, ".log-finish").writeText("") }
                        awaitStage { !w.projectTasks.state.value.busy }
                        assertEquals(1, w.projectTasks.state.value.runs.single().exit)
                        assertTrue(w.projectTasks.state.value.runs.single().truncated)
                        val retained = withContext(Dispatchers.IO) { w.projectTasks.log(id, run.id) }
                        assertTrue(retained.length > 512000)
                        click(context.getString(R.string.project_log_latest))
                        awaitStage { markerOnScreen("TAIL_LOG_FINAL_ERROR") }
                        screenshot("long-log-final-tail")
                        // Search must clear tail mode in this same still-open log, not just on reopen.
                        field("TAIL_LOG_STAGE_ONE", context.getString(R.string.project_log_search)); click(context.getString(R.string.editor_find))
                        assertTrue(visibleText(context.getString(R.string.project_log_reference_page)))
                        assertFalse(visibleText(context.getString(R.string.project_log_reference_tail)))
                        screenshot("long-log-search-after-tail")
                        click(context.getString(R.string.project_log_latest)); awaitStage { markerOnScreen("TAIL_LOG_FINAL_ERROR") }
                        click(context.getString(R.string.project_log_reference_tail))
                        awaitStage { w.ai.state.value.references.size == 1 }
                        val ref = w.ai.state.value.references.single()
                        val excerpt = ref.text.substringAfter('\n')
                        assertTrue(ref.text.length <= 6000); assertTrue(ref.truncated)
                        assertTrue(excerpt.endsWith("TAIL_LOG_FINAL_ERROR\n")); assertFalse(excerpt.contains("TAIL_LOG_HEAD"))
                        assertEquals(retained.takeLast(excerpt.length), excerpt)
                        assertEquals("[run_id=${run.id}; retained_offset=${retained.length - excerpt.length}; retained_end=${retained.length}]", ref.text.substringBefore('\n'))
                        assertFalse(w.ai.state.value.active); assertEquals(1, f.server.requestCount)
                        // Search restores the usual match-first excerpt, not the old tail selection.
                        reveal(context.getString(R.string.project_validation_open_log)); click(context.getString(R.string.project_validation_open_log))
                        field("TAIL_LOG_STAGE_ONE", context.getString(R.string.project_log_search)); click(context.getString(R.string.editor_find))
                        awaitStage { body()?.text?.startsWith("TAIL_LOG_STAGE_ONE") == true || visibleText("TAIL_LOG_STAGE_ONE\nTAIL_LOG_STAGE_TWO\nTAIL_LOG_FINAL_ERROR\n") }
                        click(context.getString(R.string.project_log_reference_page))
                        awaitStage { w.ai.state.value.references.single().text.substringAfter('\n').startsWith("TAIL_LOG_STAGE_ONE") }
                        assertEquals(1, f.server.requestCount)
                        // A second real command verifies that the compact overview also keeps stop
                        // reachable during a very long single line, without waiting for an exit.
                        click(context.getString(R.string.project_validation)); reveal(context.getString(R.string.project_validation_commands))
                        click(context.getString(R.string.project_validation_commands))
                        val stoppable = dev.zakocoder.app.workflow.ProjectCommand("run", "python3 -c 'import time; print(\"x\"*40000); print(\"STOP_FROM_OVERVIEW\", flush=True); time.sleep(120)'", timeoutSeconds = 150)
                        main { w.projectTasks.save(stoppable) }; awaitStage { !w.projectTasks.state.value.saving }
                        main { w.projectTasks.run(stoppable) }; awaitStage { w.projectTasks.state.value.log.contains("STOP_FROM_OVERVIEW") }
                        assertTrue(visibleText(context.getString(R.string.project_validation_open_log)))
                        assertTrue(visibleText(context.getString(R.string.ai_stop)))
                        assertTrue("A long previous line must not hide the final progress line", visibleText("STOP_FROM_OVERVIEW"))
                        screenshot("long-log-command-stop-overview")
                        click(context.getString(R.string.ai_stop)); awaitStage { !w.projectTasks.state.value.busy }
                        assertEquals("cancelled", w.projectTasks.state.value.runs.first().status)
                        assertEquals("cancelled", withContext(Dispatchers.IO) { w.projectTasks.runs(id).first().status })
                        assertEquals(1, f.server.requestCount)
                    } catch (error: Throwable) {
                        instrumentation.sendStatus(0, Bundle().apply { putString("tailMarker", markerDiagnostic); putString("tailFailure", "stage=$stage; ai=${w.ai.state.value.phase}; busy=${w.projectTasks.state.value.busy}; runs=${w.projectTasks.state.value.runs.map { it.status }}") })
                        screenshot("long-log-failed-stage-$stage")
                        throw error
                    } finally {
                        withContext(NonCancellable) {
                            main { w.ai.stop(); w.projectTasks.stop() }
                            awaitStage { !w.ai.state.value.active && !w.projectTasks.state.value.busy }
                        }
                    }
                }
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; awaitStage { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { workbench.select(id) }; awaitStage { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 120000) fun longLogSearchNextWithKeyboardShowsAndReferencesActualHit() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val previousAi = workbench.ai.state.value.projectId
            var owned: String? = null
            fun bodyStarts(prefix: String): Boolean {
                fun walk(node: AccessibilityNodeInfo): Boolean {
                    val value = node.text?.toString().orEmpty()
                    if (node.isVisibleToUser && value.startsWith(prefix) && value.length > 200) return true
                    return (0 until node.childCount).any { node.getChild(it)?.let(::walk) == true }
                }
                return instrumentation.uiAutomation.windows.mapNotNull { it.root }.any(::walk)
            }
            try {
                val name = "long-log-ui-${java.util.UUID.randomUUID()}"
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                click("浏览文件"); click("项目操作"); click(context.getString(R.string.project_commands))
                await { !workbench.projectTasks.state.value.loading }
                val script = "python3 -c 'print(\"n\\n\"*12000);print(\"MOBILE_LOG_NEEDLE first\");print(\"n\\n\"*12000);print(\"MOBILE_LOG_NEEDLE second\");print(\"n\\n\"*1000)'"
                val command = dev.zakocoder.app.workflow.ProjectCommand("test", script)
                main { workbench.projectTasks.save(command) }; await { !workbench.projectTasks.state.value.saving && workbench.projectTasks.state.value.commands.isNotEmpty() }
                main { workbench.projectTasks.run(command) }; await { !workbench.projectTasks.state.value.busy && workbench.projectTasks.state.value.runs.isNotEmpty() }
                assertEquals("passed", workbench.projectTasks.state.value.runs.first().status)
                reveal(context.getString(R.string.project_test) + " · " + context.getString(R.string.project_check_passed))
                click(context.getString(R.string.project_test) + " · " + context.getString(R.string.project_check_passed))
                await { visibleText(context.getString(R.string.project_run_log)) }
                field("MOBILE_LOG_NEEDLE", context.getString(R.string.project_log_search), keyboard = true)
                await { instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                assertTrue(find(context.getString(R.string.editor_find))?.isVisibleToUser == true)
                click(context.getString(R.string.editor_find)); await { bodyStarts("MOBILE_LOG_NEEDLE first") }
                screenshot("coding-long-log-keyboard-first")
                click(context.getString(R.string.project_log_find_next)); await { bodyStarts("MOBILE_LOG_NEEDLE second") }
                screenshot("coding-long-log-keyboard-next")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); delay(300)
                click(context.getString(R.string.project_log_reference_page))
                await { workbench.ai.state.value.projectId == id && workbench.ai.state.value.references.isNotEmpty() }
                val reference = workbench.ai.state.value.references.single().text
                assertTrue(reference.contains("retained_offset=")); assertTrue(reference.contains("MOBILE_LOG_NEEDLE second"))
                assertFalse(reference.contains("MOBILE_LOG_NEEDLE first"))
                assertFalse(workbench.ai.state.value.active)
            } finally {
                main { workbench.projectTasks.stop() }; await { !workbench.projectTasks.state.value.busy }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                main { workbench.ai.select(previousAi); previous?.let(workbench::select) }
                await { !workbench.state.value.busy && !workbench.ai.state.value.permissionsLoading }
            }
        }
    }
    @Test(timeout = 120000) fun longPressRenameCopyDeleteAndCancel() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "files-check-${System.currentTimeMillis()}"
            var owned: String? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                val root = workbench.projects.directory(id)
                withContext(Dispatchers.IO) { File(root, "source.txt").writeText("owned text\n") }
                click("浏览文件"); click("source.txt", long = true)
                screenshot("files-long-press")
                click("重命名"); field("renamed.txt"); click("保存")
                await { !workbench.state.value.busy && File(root, "renamed.txt").exists() }
                assertFalse(File(root, "source.txt").exists())
                click("renamed.txt", long = true); click("复制副本"); field("copy.txt"); click("保存")
                await { !workbench.state.value.busy && File(root, "copy.txt").exists() }
                assertEquals("owned text\n", File(root, "copy.txt").readText())
                click("copy.txt", long = true); click("删除"); click("取消")
                assertTrue(File(root, "copy.txt").exists())
                click("copy.txt", long = true); click("删除"); screenshot("files-delete-confirm"); click("删除")
                await { !workbench.state.value.busy && !File(root, "copy.txt").exists() }
                assertTrue(File(root, "renamed.txt").exists())
                main { workbench.fileAction(id, "renamed.txt", "copy", "renamed.txt") }
                await { !workbench.state.value.busy }
                assertEquals("owned text\n", File(root, "renamed.txt").readText())
            } finally {
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { project -> project.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 150000) fun builtInSkillsSearchToggleDetailsAndRecommendedSelection() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "builtin-ui-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { fixture ->
                    main { injected = Workbench(context, fixture.transport, fixture.config, aiHistory = fixture.history) }
                    val w = injected!!
                    await { !w.state.value.loading && w.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }; click(context.getString(R.string.nav_ai))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    assertEquals(AiBuiltinSkills.defaults, w.ai.state.value.enabledSkills)
                    click("Skill"); await { visibleText("编码与质量") }; screenshot("ai-builtin-skills")
                    click("切换 frontend-design"); await { "builtin:frontend-design" in w.ai.state.value.enabledSkills }
                    click("查看 frontend-design"); await { visibleText(context.getString(R.string.ai_skill_builtin_source)) }; screenshot("ai-builtin-details")
                    click("关闭")
                    field("megacave", context.getString(R.string.ai_skills_search)); click("切换 megacave")
                    await { "builtin:megacave" in w.ai.state.value.enabledSkills && "builtin:caveman" !in w.ai.state.value.enabledSkills }
                    screenshot("ai-builtin-style")
                    click("使用推荐项"); await { w.ai.state.value.enabledSkills == AiBuiltinSkills.defaults }
                    field("", context.getString(R.string.ai_skills_search))
                    scenario.recreate()
                    await { !workbench.state.value.loading }
                    assertEquals(AiBuiltinSkills.defaults, fixture.history.load(id).state!!.enabledSkills)
                    assertEquals(0, fixture.server.requestCount)
                }
            } finally {
                main { injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 150000) fun requestDetailsSkillsAndFullScreenCodeReviewAreUsable() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "diagnostics-ui-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { fixture ->
                    val root = workbench.projects.directory(id)
                    withContext(Dispatchers.IO) {
                        File(root, "review.py").writeText("print('before')\n")
                        File(root, ".zakocoder/skills/python-care").mkdirs()
                        File(root, ".zakocoder/skills/python-care/SKILL.md").writeText("---\nname: Python care\ndescription: 精确修改并验证\n---\nInspect files, edit precisely and check actual results.")
                    }
                    main { injected = Workbench(context, fixture.transport, fixture.config, aiHistory = fixture.history) }
                    val w = injected!!
                    await { !w.state.value.loading && w.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }; click(context.getString(R.string.nav_ai))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    click("Skill"); field("Python care", context.getString(R.string.ai_skills_search)); await { visibleText("Python care") }; screenshot("ai-skills")
                    main { w.ai.enableSkill(w.ai.state.value.skills.single { !it.builtIn }.path, true) }
                    fixture.enqueue(calls = listOf(AiCall("read", "read_file", "{\"path\":\"review.py\"}")))
                    fixture.enqueue(calls = listOf(AiCall("write", "write_file", "{\"path\":\"review.py\",\"content\":\"print('after')\\n\",\"reason\":\"修改输出，交由用户检查\"}")))
                    fixture.enqueue("文件修改完成，请查看差异。")
                    click(context.getString(R.string.ai_conversation)); field("修改 review.py 的输出"); click("发送任务")
                    await { w.ai.state.value.approval != null }
                    click("只读代码变更审阅区"); screenshot("ai-review-proposal-diff")
                    click("原文"); screenshot("ai-review-proposal-before")
                    click("修改后"); screenshot("ai-review-proposal-after")
                    click("关闭"); click(context.getString(R.string.ai_approve_once))
                    await { !w.ai.state.value.active }
                    assertEquals(AiPhase.Completed, w.ai.state.value.phase)
                    click("请求"); screenshot("ai-request-list")
                    click("第 1 轮 · 请求 3"); screenshot("ai-request-details")
                    click("关闭"); click(context.getString(R.string.ai_changes_title)); click("review.py")
                    screenshot("ai-review-saved-diff"); click("原文"); screenshot("ai-review-saved-before")
                    click("修改后"); screenshot("ai-review-saved-after"); click("关闭")
                    assertEquals("print('after')\n", File(root, "review.py").readText())
                }
            } finally {
                main { injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 150000) fun markdownLongConversationScrollCopyAndHistory() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "chat-review-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context, timeoutSeconds = 45).use { fixture ->
                    main { injected = Workbench(context, fixture.transport, fixture.config, aiHistory = fixture.history) }
                    val w = injected!!
                    await { !w.state.value.loading && w.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }; click(context.getString(R.string.nav_ai))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading }
                    val markdown = "## 构建检查\n\n**完成** 原生 Markdown。支持 `inline` 与 [文档](https://example.org/docs)。\n\n> 先检查日志，再修改代码。\n\n1. 检查依赖\n2. 运行构建\n\n```kotlin\nfun main() {\n    println(\"native markdown with a horizontally scrollable long source line\")\n}\n```\n\n| 项目 | 结果 |\n|---|---|\n| 编译 | 通过 |\n\n" + (1..28).joinToString("\n\n") { "### 检查点 $it\n正文内容 $it：保留阅读位置，主动返回最新消息。" } + "\n\n最后一段验收结束。"
                    fun streamPart(text: String) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString() + "\n\n"
                    // MockWebServer throttles request reads too: keep one bucket larger than our request, then delay only the next response increment.
                    val firstEvent = streamPart(markdown) + ": fixture padding " + " ".repeat(131072) + "\n\n"
                    val stream = firstEvent + streamPart("\n\n后续增量结束。") + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
                        .throttleBody(firstEvent.toByteArray().size.toLong(), 8, java.util.concurrent.TimeUnit.SECONDS))
                    field("检查 Markdown 和长回复排版"); click("发送任务")
                    screenshot("chat-submitted")
                    try { await { w.ai.state.value.active && w.ai.state.value.timeline.any { it.text.contains("最后一段验收结束。") } } }
                    catch (timeout: TimeoutCancellationException) { screenshot("chat-fixture-failure"); throw AssertionError("Local fixture phase=${w.ai.state.value.phase}, error=${w.ai.state.value.error}, requests=${fixture.server.requestCount}", timeout) }
                    screenshot("chat-after-response")
                    await { visibleText("最后一段验收结束。") }
                    screenshot("chat-latest")
                    // A physical drag must disable following; new output must not pull the reader away.
                    instrumentation.uiAutomation.executeShellCommand("input swipe 500 750 500 1650 400").close()
                    delay(500)
                    await { find("回到最新") != null }; screenshot("chat-reading-position")
                    await { !w.ai.state.value.active }
                    assertEquals(w.ai.state.value.error, AiPhase.Completed, w.ai.state.value.phase)
                    assertTrue(w.ai.state.value.timeline.any { it.text.contains("后续增量结束。") })
                    assertFalse("Incoming output must preserve the manual reading position", visibleText("后续增量结束。"))
                    assertNotNull(find("回到最新"))
                    click(context.getString(R.string.nav_environment)); click(context.getString(R.string.nav_ai)); delay(500)
                    assertFalse("Navigation preserves reading position", visibleText("后续增量结束。"))
                    assertNotNull(find("回到最新"))
                    click(context.getString(R.string.ai_more)); click(context.getString(R.string.ai_settings)); click(context.getString(R.string.ai_models_back)); delay(500)
                    assertFalse("Model settings preserve reading position", visibleText("后续增量结束。"))
                    assertNotNull(find("回到最新"))
                    screenshot("ux-reading-returned")
                    click("回到最新"); screenshot("chat-returned-latest"); await { visibleText("后续增量结束。") }
                    click("会话记录"); screenshot("chat-history"); click("关闭")
                    click("新建会话"); await { w.ai.state.value.timeline.isEmpty() && !w.ai.state.value.permissionsLoading }
                    screenshot("chat-new")
                    click("会话记录"); click("检查 Markdown 和长回复排版")
                    await { w.ai.state.value.turn == 1 && !w.ai.state.value.permissionsLoading }
                    delay(600)
                    // Scroll to the beginning through accessibility, then inspect the Markdown surface.
                    repeat(6) {
                        fun scroll(node: AccessibilityNodeInfo): Boolean {
                            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                            return (0 until node.childCount).any { n -> node.getChild(n)?.let(::scroll) == true }
                        }
                        instrumentation.uiAutomation.rootInActiveWindow?.let(::scroll); delay(120)
                    }
                    screenshot("chat-after-reading-up")
                    await { visibleText("构建检查") }; screenshot("chat-markdown")
                    assertFalse(visibleText("你：检查 Markdown 和长回复排版"))
                    click("复制代码")
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    var copied = ""
                    main { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
                    assertTrue(copied.contains("fun main()"))
                }
            } finally {
                main { injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }
    @Test(timeout = 120000) fun nativeAiNavigationConfigReviewRejectAndSettings() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "ai-ui-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { fixture ->
                    main { injected = Workbench(context, fixture.transport, fixture.config, aiHistory = fixture.history) }
                    val testWorkbench = injected!!
                    await { !testWorkbench.state.value.loading && testWorkbench.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(testWorkbench) } }
                    click(context.getString(R.string.nav_ai))
                    await { testWorkbench.ai.state.value.projectId == id }
                    screenshot("ai-empty")
                    click("模型配置"); screenshot("ai-model-settings"); click("返回")
                    fixture.enqueue(calls = listOf(AiCall("ui-write", "write_file", JSONObject().put("path", "review.txt").put("content", "reviewed content\n").toString())))
                    fixture.enqueue("已停止此项修改。")
                    field("创建一个待审查文件"); click("发送任务")
                    await { testWorkbench.ai.state.value.approval != null }
                    screenshot("ai-diff-review")
                    click("拒绝")
                    await { !testWorkbench.ai.state.value.active }
                    assertFalse(File(testWorkbench.projects.directory(id), "review.txt").exists())
                    screenshot("ai-result")
                    click("设置"); click("模型配置"); screenshot("settings-ai-entry"); click("返回")
                }
            } finally {
                main { injected?.ai?.stop() }
                await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { project -> project.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }
    private fun fullReplyPage(): String? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): String? {
            if (node.contentDescription?.toString() == context.getString(R.string.ai_full_text_body)) return node.text?.toString()
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it)?.let { value -> return value } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.mapNotNull(::walk).firstOrNull()
    }

    @Test(timeout = 180000) fun longReplyFullTextIsLosslessAndAvailableAfterHistoryReload(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            AiDeviceTest.Fixture(context).use { fixture ->
                val isolated = object : ContextWrapper(context) {
                    override fun getFilesDir() = File(fixture.root, "workbench").apply { mkdirs() }
                    override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
                }
                var injected: Workbench? = null
                val application = context.applicationContext as ZakoApplication
                val originalWorkbench = application.workbench
                val workbenchField = ZakoApplication::class.java.getDeclaredField("workbench").apply { isAccessible = true }
                suspend fun expect(stage: String, condition: () -> Boolean) {
                    try { await(condition) }
                    catch (error: TimeoutCancellationException) {
                        screenshot("long-reply-failed-$stage")
                        val state = injected?.ai?.state?.value
                        throw AssertionError("$stage phase=${state?.phase} active=${state?.active} configured=${state?.configured} error=${state?.error} lengths=${state?.timeline?.map { it.text.length }} pageLength=${fullReplyPage()?.length} pageHash=${fullReplyPage()?.hashCode()} requests=${fixture.server.requestCount}", error)
                    }
                }
                suspend fun openFullText() {
                    val label = context.getString(R.string.ai_full_text_open)
                    find(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    // Long chat items may compose both ends; ensure the entry action is actually visible.
                    var attempts = 0
                    while (!visibleText(label) && attempts++ < 80) scrollProcess()
                    expect("step-1") { visibleText(label) }
                    screenshot("long-reply-partial-notice")
                    click(label)
                    expect("step-2") { visibleText(context.getString(R.string.ai_full_text_title)) && fullReplyPage() != null }
                }
                try {
                    main { injected = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
                    var w = injected!!
                    expect("step-3") { !w.state.value.loading && w.ai.state.value.configured }
                    main { w.create("long-reply-owned") }
                    expect("step-4") { !w.state.value.busy && w.state.value.project != null }
                    val project = w.state.value.selectedId!!
                    // Initial and restored compositions must use the same real onCreate call site/saveable keys.
                    main { workbenchField.set(application, w) }
                    scenario.recreate()
                    click(context.getString(R.string.nav_ai))
                    expect("step-5") { !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    fixture.enqueue("简短回复正常显示。")
                    main { assertTrue(w.ai.send("先给一个短回复")) }
                    expect("step-6") { !w.ai.state.value.active && visibleText("简短回复正常显示。") }
                    assertFalse(visibleText(context.getString(R.string.ai_full_text_open)))
                    val tail = "\n\n未验证构建；预览尚未启动。TAIL_SENTINEL"
                    val body = "word ".repeat(14400) + tail
                    val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", body))))
                    fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $delta\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
                    main { assertTrue(w.ai.send("给出完整交付说明")) }
                    expect("step-7") { !w.ai.state.value.active && w.ai.state.value.timeline.any { it.text == body } }
                    assertFalse(w.ai.state.value.historyError)
                    assertEquals("", w.ai.state.value.error)
                    openFullText()
                    val ranges = dev.zakocoder.app.ui.AiTextPages.ranges(body)
                    val observed = StringBuilder()
                    for ((index, range) in ranges.withIndex()) {
                        val expected = body.substring(range)
                        expect("step-9") { fullReplyPage() == expected }
                        observed.append(fullReplyPage())
                        assertTrue(visibleText(context.getString(R.string.ai_full_text_range, range.first + 1, range.last + 1, body.length)))
                        if (index < ranges.lastIndex) click(context.getString(R.string.ai_full_text_next))
                    }
                    assertEquals(body, observed.toString())
                    expect("step-10") { visibleText(tail) }
                    screenshot("long-reply-tail")
                    click(context.getString(R.string.ai_full_text_first))
                    expect("step-11") { fullReplyPage() == body.substring(ranges.first()) }
                    click(context.getString(R.string.ai_full_text_last))
                    expect("step-12") { fullReplyPage() == tail }
                    scenario.recreate()
                    expect("open-recreated") {
                        visibleText(context.getString(R.string.ai_full_text_title)) && fullReplyPage() == tail &&
                            visibleText(context.getString(R.string.ai_full_text_page, ranges.size, ranges.size))
                    }
                    screenshot("long-reply-open-recreated-tail")
                    assertEquals(2, fixture.server.requestCount)
                    click(context.getString(R.string.close))
                    // Fresh controller and Activity: history supplies the actual received body, without another request.
                    main { w.ai.stop() }
                    main { injected = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
                    w = injected!!
                    expect("step-13") { !w.state.value.loading && w.state.value.selectedId == project && w.ai.state.value.configured }
                    main { workbenchField.set(application, w) }
                    scenario.recreate()
                    screenshot("long-reply-history-recreated")
                    expect("history-reader-closed") { fullReplyPage() == null }
                    expect("history-ai-page") { visibleText(context.getString(R.string.nav_ai)) }
                    expect("step-14") { !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    assertTrue(w.ai.state.value.timeline.any { it.text == body })
                    openFullText(); click(context.getString(R.string.ai_full_text_last))
                    expect("step-15") { fullReplyPage() == tail && visibleText(tail) }
                    screenshot("long-reply-restored-tail")
                    assertEquals(2, fixture.server.requestCount)
                    click(context.getString(R.string.close))
                } finally {
                    main { workbenchField.set(application, originalWorkbench); injected?.ai?.stop() }
                    expect("step-16") { injected?.ai?.state?.value?.active != true }
                }
            }
        }
    }

    @Test(timeout = 180000) fun streamingFullTextKeepsSnapshotAndStoppedReplyRemainsReadable(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            AiDeviceTest.Fixture(context, timeoutSeconds = 30).use { fixture ->
                val isolated = object : ContextWrapper(context) {
                    override fun getFilesDir() = File(fixture.root, "workbench").apply { mkdirs() }
                    override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
                }
                lateinit var w: Workbench
                try {
                    main { w = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
                    await { !w.state.value.loading && w.ai.state.value.configured }
                    main { w.create("streaming-reply-owned") }
                    await { !w.state.value.busy && w.state.value.project != null }
                    val id = w.state.value.selectedId!!
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai))
                    await { !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    val tail = "\n\n已接收正文末尾 STREAM_TAIL"
                    val first = "word ".repeat(14400) + tail
                    val extra = "\n后续增量 NEW_STREAM_TAIL"
                    fun event(text: String) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))) + "\n\n"
                    // SSE comments pace the next event without adding unreceived model text to the UI.
                    val gap = ":" + " ".repeat(600000) + "\n\n"
                    fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody(event(first) + gap + event(extra) + gap + "data: [DONE]\n\n")
                        .throttleBody(8192, 100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    main { assertTrue(w.ai.send("生成长回复并继续补充")) }
                    await { w.ai.state.value.timeline.any { it.role == AiRole.Assistant && it.text == first } }
                    assertTrue(w.ai.state.value.active)
                    val label = context.getString(R.string.ai_full_text_open)
                    find(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    var attempts = 0
                    while (!visibleText(label) && attempts++ < 80) scrollProcess()
                    await { visibleText(label) }; click(label)
                    await { fullReplyPage() != null }
                    click(context.getString(R.string.ai_full_text_last))
                    await { fullReplyPage() == tail }
                    screenshot("long-reply-streaming-snapshot")
                    await { w.ai.state.value.timeline.any { it.role == AiRole.Assistant && it.text == first + extra } }
                    assertTrue(w.ai.state.value.active)
                    assertEquals(tail, fullReplyPage())
                    main { w.ai.stop() }; await { !w.ai.state.value.active }
                    assertEquals(AiFailure.Cancelled, w.ai.state.value.failureKind)
                    assertEquals(tail, fullReplyPage())
                    click(context.getString(R.string.close))
                    find(label)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    click(label); click(context.getString(R.string.ai_full_text_last))
                    await { fullReplyPage() == tail + extra && visibleText(tail + extra) }
                    screenshot("long-reply-stopped-tail")
                    val stored = withContext(Dispatchers.IO) { fixture.history.load(id).state!! }
                    assertEquals(first + extra, stored.timeline.single { it.role == AiRole.Assistant }.text)
                    assertEquals(AiFailure.Cancelled, stored.failureKind)
                    assertEquals(1, fixture.server.requestCount)
                    click(context.getString(R.string.close))
                } finally { main { w.ai.stop() } }
            }
        }
    }

    @Test(timeout = 120000) fun failedFileReasonIsVisibleBeforeExpandingAndFullDetailsRemainAvailable() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "file-failure-ui-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                val file = File(workbench.projects.directory(id), "review.txt")
                withContext(Dispatchers.IO) { file.writeText("user original\n") }
                AiDeviceTest.Fixture(context).use { fixture ->
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = fixture.root }
                    main { injected = Workbench(context, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
                    val w = injected!!
                    await { !w.state.value.loading && w.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
                    click(context.getString(R.string.nav_ai))
                    await { w.ai.state.value.projectId == id && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading }
                    fixture.enqueue(calls = listOf(AiCall("unread", "write_file", JSONObject().put("path", "review.txt")
                        .put("content", "replacement\n").put("reason", "更新说明文件").toString())))
                    fixture.enqueue("尚未修改文件，需要先读取实际内容。")
                    field("更新说明，保留已有内容"); click("发送任务")
                    await { !w.ai.state.value.active && w.ai.state.value.events.size == 1 }
                    val event = w.ai.state.value.events.single()
                    assertEquals(AiToolStatus.Failed, event.status)
                    assertTrue(event.output.startsWith("[READ_REQUIRED]")); assertNull(w.ai.state.value.approval)
                    assertEquals("user original\n", withContext(Dispatchers.IO) { file.readText() })
                    scenario.onActivity { activity ->
                        activity.currentFocus?.clearFocus()
                        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                            .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    }
                    delay(400); screenshot("file-failure-before-navigation")
                    // This task already uses the default process tab; assert its visible content.
                    await { visibleText(context.getString(R.string.ai_conversation)) }
                    repeat(5) { if (!visibleText(event.output)) scrollProcess() }
                    await { visibleText(event.output) }
                    assertFalse(visibleText(context.getString(R.string.ai_tool_parameters)))
                    screenshot("file-failure-collapsed")
                    click(context.getString(R.string.ai_tool_details))
                    fun outputWithinViewport(): Boolean {
                        var output: android.graphics.Rect? = null; var viewport: android.graphics.Rect? = null
                        var composerTop = Int.MAX_VALUE
                        fun walk(node: AccessibilityNodeInfo) {
                            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                            if (node.isVisibleToUser) {
                                if (node.text?.toString() == event.output) output = bounds
                                if (node.isEditable) composerTop = minOf(composerTop, bounds.top)
                                if (node.isScrollable && bounds.height() > (viewport?.height() ?: 0)) viewport = bounds
                            }
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
                        }
                        instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.forEach(::walk)
                        val textBounds = output ?: return false; val clip = viewport ?: return false
                        return textBounds.height() > 0 && textBounds.top >= clip.top && textBounds.bottom <= minOf(clip.bottom, composerTop)
                    }
                    repeat(8) { if (!outputWithinViewport()) scrollProcess() }
                    await { outputWithinViewport() && visibleText(context.getString(R.string.ai_tool_output)) }
                    screenshot("file-failure-expanded")
                    assertEquals("user original\n", withContext(Dispatchers.IO) { file.readText() })
                    assertEquals(2, fixture.server.requestCount)
                }
            } finally {
                main { injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 150000) fun projectPermissionsPlansAndExpandableExecutionAreVisible() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val previous = workbench.state.value.selectedId
            val name = "ai-execution-ui-${System.currentTimeMillis()}"; var owned: String? = null
            var injected: Workbench? = null
            try {
                main { workbench.create(name) }; await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val id = workbench.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { fixture ->
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = fixture.root }
                    main { injected = Workbench(context, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
                    val testWorkbench = injected!!
                    await { !testWorkbench.state.value.loading && testWorkbench.ai.state.value.configured }
                    scenario.onActivity { it.setContent { ZakoCoderApp(testWorkbench) } }
                    click(context.getString(R.string.nav_ai)); await { testWorkbench.ai.state.value.projectId == id && !testWorkbench.ai.state.value.permissionsLoading }
                    click(context.getString(R.string.ai_more)); click("工具权限"); screenshot("ai-project-permissions")
                    click("设置写入文件权限"); click("禁止")
                    await { !testWorkbench.ai.state.value.permissionsSaving && testWorkbench.ai.state.value.policy.permission(AiTool.WriteFile) == AiPermission.Deny }
                    screenshot("ai-write-forbidden"); click("关闭")
                    val plan = AiCall("plan", "update_plan", JSONObject().put("steps", JSONArray()
                        .put(JSONObject().put("title", "检查文件修改权限").put("status", "completed"))
                        .put(JSONObject().put("title", "创建项目说明文件").put("status", "pending"))).put("reason", "先展示本次任务的执行安排").toString())
                    val write = AiCall("blocked", "write_file", JSONObject().put("path", "review.txt").put("content", "reviewed content\n").put("reason", "添加项目说明，供你检查修改内容").toString())
                    fixture.enqueue(calls = listOf(plan, write)); fixture.enqueue("写入被项目权限禁止，没有修改文件。")
                    field("先检查权限，再创建项目说明"); click("发送任务")
                    await { !testWorkbench.ai.state.value.active && testWorkbench.ai.state.value.events.size == 2 }
                    assertEquals(AiToolStatus.Denied, testWorkbench.ai.state.value.events.last().status)
                    assertFalse(File(testWorkbench.projects.directory(id), "review.txt").exists())
                    screenshot("ai-plan-and-denied-tool")
                    click(context.getString(R.string.ai_more)); click("工具权限"); click("设置写入文件权限"); click("每次询问")
                    await { !testWorkbench.ai.state.value.permissionsSaving && testWorkbench.ai.state.value.policy.permission(AiTool.WriteFile) == AiPermission.Ask }; click("关闭")
                    fixture.enqueue(calls = listOf(write.copy(id = "approved"))); fixture.enqueue("文件已经创建，可以查看实际变更。")
                    field("现在创建项目说明，先让我审批"); click("发送任务")
                    await { testWorkbench.ai.state.value.approval != null }
                    screenshot("ai-detailed-approval"); click("本任务同一目标允许")
                    await { !testWorkbench.ai.state.value.active }
                    assertEquals("reviewed content\n", File(testWorkbench.projects.directory(id), "review.txt").readText())
                    screenshot("ai-execution-finished")
                    click("展开工具详情"); screenshot("ai-expanded-tool-result")
                    scrollProcess(); await { visibleText("已写入 review.txt") }; screenshot("ai-expanded-tool-output")
                    click(context.getString(R.string.ai_changes_title)); screenshot("ai-file-changes")
                    click(context.getString(R.string.ai_more)); click("工具权限"); click("设置写入文件权限"); click("允许")
                    screenshot("ai-auto-allow-confirm"); click("取消")
                    assertEquals(AiPermission.Ask, testWorkbench.ai.state.value.policy.permission(AiTool.WriteFile))
                    click("设置写入文件权限"); click("允许"); click("确认自动允许")
                    await { !testWorkbench.ai.state.value.permissionsSaving && testWorkbench.ai.state.value.policy.permission(AiTool.WriteFile) == AiPermission.Allow }; click("关闭")
                }
            } finally {
                main { injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true }
                owned?.let { id -> main { workbench.removeProject(id) }; await { !workbench.state.value.busy && workbench.state.value.projects.none { project -> project.id == id } } }
                if (previous != null) { main { workbench.select(previous) }; await { !workbench.state.value.busy } }
            }
        }
    }

    @Test(timeout = 150000) fun modelManagementCreateDiscoverTestSwitchDiscardAndDelete() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            AiDeviceTest.Fixture(context).use { fixture ->
                var injected: Workbench? = null
                try {
                    main { injected = Workbench(context, fixture.transport, fixture.config, aiHistory = fixture.history) }
                    val testWorkbench = injected!!
                    await { !testWorkbench.state.value.loading && !testWorkbench.ai.models.state.value.loading }
                    scenario.onActivity { it.setContent { ZakoCoderApp(testWorkbench) } }
                    click(context.getString(R.string.nav_ai)); screenshot("ai-workspace-redesign")
                    click(context.getString(R.string.ai_more)); click("模型配置"); screenshot("models-list")
                    click("新增配置"); screenshot("models-editor-empty")
                    field("设备验收", "配置名称")
                    click("选择服务"); screenshot("models-service-selection"); click("DeepSeek")
                    field(fixture.server.url("/v1").toString(), "HTTPS 服务地址")
                    screenshot("models-editor-connection")
                    val directory = JSONArray().put(JSONObject().put("id", "fixture-code")).put(JSONObject().put("id", "fixture-chat"))
                    repeat(1998) { directory.put(JSONObject().put("id", "fixture-extra-${it.toString().padStart(4, '0')}")) }
                    fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(JSONObject().put("data", directory).toString()))
                    reveal("获取模型"); click("获取模型")
                    await { testWorkbench.ai.models.state.value.available.size == 2000 }
                    screenshot("models-picker"); field("fixture-code", "搜索模型标识"); click("选择模型：fixture-code")
                    await { testWorkbench.ai.models.state.value.available.isEmpty() }
                    reveal("启用最大输出长度"); click("启用最大输出长度"); field("512", "最大输出长度", revealEditable = true)
                    reveal("测试流式工具调用")
                    fixture.enqueue(calls = listOf(AiCall("probe-ui", "connection_probe", "{\"ack\":\"ok\"}")))
                    screenshot("models-editor-parameters"); click("测试流式工具调用")
                    await { testWorkbench.ai.models.state.value.probe == AiProbe.Passed }
                    screenshot("models-test-passed")
                    click("保存")
                    await { testWorkbench.ai.models.state.value.catalog.profiles.size == 2 && !testWorkbench.ai.models.state.value.saving }
                    click("设为当前"); await { testWorkbench.ai.state.value.model == "fixture-code" }
                    assertEquals(AiParameters(512), testWorkbench.ai.models.state.value.catalog.active!!.parameters)
                    screenshot("models-selected")
                    click("编辑配置：设备验收"); field("未保存的名称", "配置名称"); click("返回")
                    screenshot("models-discard-confirm"); click("继续编辑"); click("返回"); click("放弃修改")
                    assertEquals("设备验收", testWorkbench.ai.models.state.value.catalog.active!!.name)
                    click("删除配置：设备验收"); click("取消")
                    assertEquals(2, testWorkbench.ai.models.state.value.catalog.profiles.size)
                    click("删除配置：设备验收"); click("删除")
                    await { testWorkbench.ai.models.state.value.catalog.profiles.size == 1 && !testWorkbench.ai.models.state.value.saving }
                    assertEquals("fixture-model", testWorkbench.ai.state.value.model)
                    click("删除配置：fixture-model"); click("删除")
                    await { testWorkbench.ai.models.state.value.catalog.profiles.isEmpty() && !testWorkbench.ai.models.state.value.saving }
                    assertFalse(testWorkbench.ai.state.value.configured); screenshot("models-empty")
                    val requests = fixture.server.requestCount
                    click("添加模型配置"); field("手动配置", "配置名称")
                    field(fixture.server.url("/v1").toString(), "HTTPS 服务地址")
                    reveal("模型标识"); field("fixture-manual", "模型标识"); click("保存")
                    await { testWorkbench.ai.models.state.value.catalog.profiles.size == 1 && !testWorkbench.ai.models.state.value.saving }
                    assertEquals("fixture-manual", testWorkbench.ai.state.value.model)
                    assertEquals(testWorkbench.ai.models.state.value.catalog, AiConfigStore(context, fixture.token, fixture.alias).catalog())
                    assertEquals(requests, fixture.server.requestCount)
                    click("返回"); screenshot("ai-workspace-after-model-selection")
                } finally { main { injected?.ai?.models?.cancelProbe(); injected?.ai?.stop() }; await { injected?.ai?.state?.value?.active != true } }
            }
        }
    }

}

package dev.zakocoder.app

import android.graphics.Bitmap
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorLanguages
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only UUID-owned cache data; no provider, permission, registered project or user history access. */
class AiChangesPanelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun click(label: String) {
        await("visible $label") { find(label) != null }
        var node = find(label)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(label, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(200)
    }
    private fun reviewText(): String? {
        var result: String? = null
        fun walk(view: android.view.View) {
            if (view is io.github.rosemoe.sora.widget.CodeEditor && !view.isEditable && view.isShown) result = view.text.toString()
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::walk) }
        return result
    }

    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "changes"
            File(context.cacheDir, "ui-review/$theme/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun reloadedCheckpointChangesSwitchIdentityAndDistinguishCorruptionEvictionAndEmpty() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val root = withContext(Dispatchers.IO) { File(context.cacheDir, "changes-panel-${UUID.randomUUID()}").apply { check(mkdirs()) } }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        try {
            val project = UUID.randomUUID().toString()
            val conversation = UUID.randomUUID().toString()
            val projectRoot = File(root, "source")
            val historyPath = File(root, "history")
            lateinit var first: String
            lateinit var second: String
            val loaded = withContext(Dispatchers.IO) {
                check(projectRoot.mkdirs())
                File(projectRoot, "a.py").writeText("A_BEFORE\n")
                File(projectRoot, "b.py").writeText("B_BEFORE\n")
                val history = AiHistoryStore(historyPath)
                val store = AiCheckpoints(history.checkpointDirectory) { check(it == project); projectRoot }
                first = store.begin(project, conversation, 1)
                File(projectRoot, "a.py").writeText("A_AFTER\n"); store.update(project, first, true)
                second = store.begin(project, conversation, 2)
                File(projectRoot, "b.py").writeText("B_AFTER\n"); store.update(project, second, true)
                history.save(project, AiState(projectId = project, conversationId = conversation, turn = 2, checkpointId = second,
                    timeline = listOf(AiEntry(UUID.randomUUID().toString(), "first", turn = 1), AiEntry(UUID.randomUUID().toString(), "second", turn = 2))))
                requireNotNull(AiHistoryStore(historyPath).load(project, conversation).state).copy(projectId = project)
            }
            assertTrue(loaded.changes.isEmpty())
            assertTrue(loaded.commandChanges.isEmpty())
            // A new controller loads saved history without any runtime undo cache.
            val files = withContext(Dispatchers.IO) { File(root, "controller-files").apply { check(mkdirs()) } }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = files
                override fun getNoBackupFilesDir() = files
            }
            lateinit var controller: AiController
            main {
                controller = AiController(isolated, { check(it == project); projectRoot }, { _, _ -> false },
                    { _, _ -> error("No command may run in this UI fixture") }, {}, {},
                    config = AiConfigStore(isolated, "empty-fixture-config", "unused-${UUID.randomUUID()}"),
                    permissionStore = AiPermissionStore(isolated), history = AiHistoryStore(historyPath))
                controller.select(project)
            }
            await("fresh controller history restored") {
                controller.state.value.projectId == project && !controller.state.value.permissionsLoading &&
                    !controller.state.value.skillsLoading && controller.state.value.turn == 2
            }
            assertEquals(conversation, controller.state.value.conversationId)
            assertTrue(controller.state.value.changes.isEmpty())
            val shown = mutableStateOf(controller.state.value)
            val store = AiCheckpoints(AiHistoryStore(historyPath).checkpointDirectory) { check(it == project); projectRoot }
            val languages = EditorLanguages(context)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val dark = isSystemInDarkTheme()
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface { AiChangesPanel(shown.value, languages, { error("Historical checkpoint must not expose undo") }, {},
                                loadCheckpoints = controller::checkpointList,
                                loadChanges = { key ->
                                    if (key == first && !release.isCompleted) {
                                        entered.complete(Unit)
                                        try { release.await() } catch (error: CancellationException) { cancelled.incrementAndGet(); throw error }
                                    }
                                    controller.checkpointChanges(key)
                                }, modifier = Modifier.fillMaxSize().safeDrawingPadding()) }
                        }
                    }
                } }
                await("persisted second path with empty volatile lists") { find("b.py") != null }
                assertNull(find(context.getString(R.string.ai_changes_snapshot_empty)))
                assertNull(find(context.getString(R.string.ai_undo_file, "b.py")))
                screenshot("changes-reloaded")
                click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                await("paused first checkpoint") { entered.isCompleted }
                assertNull(find("b.py")); assertNull(find("a.py"))
                assertNotNull(find(context.getString(R.string.loading)))
                screenshot("changes-turn-loading")
                click(context.getString(R.string.project_validation_turn, 1)); click(context.getString(R.string.project_validation_turn, 2))
                await("cancelled old reader") { cancelled.get() == 1 && find("b.py") != null }
                release.complete(Unit)
                click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                await("first checkpoint after retry") { find("a.py") != null }
                click("a.py")
                await("actual first diff") { reviewText()?.let { "A_BEFORE" in it && "A_AFTER" in it } == true }
                screenshot("changes-first-diff")
                click(context.getString(R.string.close))
                withContext(Dispatchers.IO) {
                    val dir = File(AiHistoryStore(historyPath).checkpointDirectory.listFiles()!!.single(), second)
                    val entries = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("entries")
                    val after = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "b.py" }.getJSONObject("after")
                    File(dir, after.getString("hash")).writeText("CORRUPTED_OWN_BLOB")
                }
                click(context.getString(R.string.project_validation_turn, 1)); click(context.getString(R.string.project_validation_turn, 2))
                await("corrupt is failed, never empty") { find(context.getString(R.string.ai_changes_read_failed)) != null }
                assertNull(find("a.py")); assertNull(find("b.py")); assertNull(find(context.getString(R.string.ai_changes_snapshot_empty)))
                screenshot("changes-corrupt")
                val otherConversation = UUID.randomUUID().toString()
                main { shown.value = loaded.copy(conversationId = otherConversation, turn = 1, checkpointId = null, timeline = emptyList()) }
                await("other conversation has no retained checkpoint") { find(context.getString(R.string.ai_changes_missing_checkpoint)) != null }
                assertNull(find("a.py")); assertNull(find("b.py"))
                screenshot("changes-missing")
                val emptyKey = withContext(Dispatchers.IO) {
                    store.begin(project, otherConversation, 1).also { store.update(project, it, true) }
                }
                main { shown.value = shown.value.copy(checkpointId = emptyKey, workspaceRevision = 1) }
                await("actual sealed empty checkpoint") { find(context.getString(R.string.ai_changes_snapshot_empty)) != null }
                screenshot("changes-empty")
                withContext(Dispatchers.IO) {
                    File(AiHistoryStore(historyPath).checkpointDirectory.listFiles()!!.single(), emptyKey).deleteRecursively()
                }
                main { shown.value = shown.value.copy(workspaceRevision = 2) }
                await("eviction is missing, not empty") { find(context.getString(R.string.ai_changes_missing_checkpoint)) != null }
                assertNull(find(context.getString(R.string.ai_changes_snapshot_empty)))
            }
        } finally {
            release.complete(Unit)
            withContext(NonCancellable + Dispatchers.IO) { check(root.deleteRecursively()) }
        }
    }
}

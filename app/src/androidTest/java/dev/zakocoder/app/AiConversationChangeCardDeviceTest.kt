package dev.zakocoder.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorLanguages
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual change-card component backed only by UUID-owned checkpoint files; no network or user history. */
class AiConversationChangeCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            // Clickable Surface merges its Text children into newline-separated accessibility text.
            // Retry buttons split that merge; both structures must retain the exact status wording.
            val textMatches = node.text?.toString()?.lineSequence()?.any { it == label } == true
            val descriptionMatches = node.contentDescription?.toString()?.lineSequence()?.any { it == label } == true
            if (node.isVisibleToUser && (textMatches || descriptionMatches)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }

    private suspend fun click(label: String) {
        await("visible $label") { find(label) != null }
        var node = requireNotNull(find(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(label, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(200)
    }

    private fun reviewText(): String? {
        val preview = find(context.getString(R.string.ai_review_content)) ?: return null
        fun collect(node: AccessibilityNodeInfo): String = buildString {
            node.text?.let { append(it); append('\n') }
            for (index in 0 until node.childCount) node.getChild(index)?.let { append(collect(it)) }
        }
        return collect(preview)
    }

    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "system"
            File(context.cacheDir, "ui-review/$theme/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 90000) fun checkpointNumbersOpenSortedFilesAndInlineDiffWithoutNavigation() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) {
            File(context.cacheDir, "change-card-${UUID.randomUUID()}").apply { check(mkdirs()) }
        }
        try {
            val project = UUID.randomUUID().toString()
            val conversation = UUID.randomUUID().toString()
            val source = File(fixture, "source")
            val store = AiCheckpoints(File(fixture, "checkpoints")) { check(it == project); source }
            val checkpoint = withContext(Dispatchers.IO) {
                check(File(source, "src").mkdirs())
                File(source, "src/main.py").writeText("before_main")
                File(source, "legacy.py").writeText("legacy_a\nlegacy_b")
                val key = store.begin(project, conversation, 1)
                File(source, "src/main.py").writeText("after_main\nadded_main")
                check(File(source, "legacy.py").delete())
                File(source, "README.md").writeText("new_document")
                store.update(project, key, true)
                key
            }
            val changes = withContext(Dispatchers.IO) { store.changes(project, checkpoint) }
            assertEquals(setOf("src/main.py", "legacy.py", "README.md"), changes.map { it.path }.toSet())
            // Explicit expected content edits, independent of the UI's summary implementation.
            val count = context.getString(R.string.ai_changes_files_count, 3)
            val added = context.getString(R.string.ai_file_changes_added, 3)
            val removed = context.getString(R.string.ai_file_changes_removed, 3)
            val state = AiState(projectId = project, conversationId = conversation, turn = 1, checkpointId = checkpoint)
            assertTrue(state.changes.isEmpty())
            assertTrue(state.commandChanges.isEmpty())
            val languages = EditorLanguages(context)
            warmDeviceActivity()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val dark = isSystemInDarkTheme()
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(context.getString(R.string.ai_file_changes_title), style = MaterialTheme.typography.titleLarge)
                                    Text(context.getString(R.string.project_validation_turn, 1), style = MaterialTheme.typography.bodySmall)
                                    AiConversationChangeCard(state, 1, languages,
                                        onUndo = { error("Persisted-only change must not expose volatile undo") },
                                        loadCheckpoints = { withContext(Dispatchers.IO) { store.list(project) } },
                                        loadChanges = { key -> withContext(Dispatchers.IO) { store.changes(project, key) } })
                                }
                            }
                        }
                    }
                } }
                await("durable file count and +3/-3 total") { find(count) != null && find(added) != null && find(removed) != null }
                assertNull(find("main.py"))
                screenshot("conversation-change-card")
                click(count)
                await("all changed file rows") { find("main.py") != null && find("legacy.py") != null && find("README.md") != null }
                val mainBounds = Rect().also { requireNotNull(find("main.py")).getBoundsInScreen(it) }
                val legacyBounds = Rect().also { requireNotNull(find("legacy.py")).getBoundsInScreen(it) }
                val readmeBounds = Rect().also { requireNotNull(find("README.md")).getBoundsInScreen(it) }
                assertTrue("3 edits precede 2 edits", mainBounds.top < legacyBounds.top)
                assertTrue("2 edits precede 1 edit", legacyBounds.top < readmeBounds.top)
                assertNull(reviewText())
                screenshot("conversation-change-sheet")
                click("main.py")
                await("actual inline checkpoint diff") { reviewText()?.let { "-before_main" in it && "+after_main" in it && "+added_main" in it } == true }
                assertNotNull(find(context.getString(R.string.close)))
                assertNull(find(context.getString(R.string.ai_file_changes_undo_last)))
                screenshot("conversation-change-inline-diff")
                click("main.py")
                await("collapsed inline diff keeps file list") { reviewText() == null && find("legacy.py") != null }
                click(context.getString(R.string.close))
                await("dismissed sheet leaves original card") { find("main.py") == null && find(count) != null }
                assertNotNull(find(added))
                assertNotNull(find(removed))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { check(fixture.deleteRecursively()) }
        }
    }

    @Test(timeout = 90000) fun observedChangesKeepFailureAndTaskStatusVisibleAndEmptyFilesRemainReviewable() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) {
            File(context.cacheDir, "empty-change-card-${UUID.randomUUID()}").apply { check(mkdirs()) }
        }
        try {
            val project = UUID.randomUUID().toString()
            val conversation = UUID.randomUUID().toString()
            val source = File(fixture, "source")
            val store = AiCheckpoints(File(fixture, "checkpoints")) { check(it == project); source }
            val checkpoint = withContext(Dispatchers.IO) {
                check(source.mkdirs())
                File(source, "deleted-empty.txt").writeText("")
                val key = store.begin(project, conversation, 1)
                check(File(source, "deleted-empty.txt").delete())
                File(source, "created-empty.txt").writeText("")
                store.update(project, key, true)
                key
            }
            val failRead = java.util.concurrent.atomic.AtomicBoolean(true)
            val markUnsealed = java.util.concurrent.atomic.AtomicBoolean(false)
            val observedEventId = UUID.randomUUID().toString()
            val shown = mutableStateOf(AiState(projectId = project, conversationId = conversation, turn = 1,
                checkpointId = checkpoint, commandReviewLimited = true,
                commandChanges = listOf(AiCommandChange("$observedEventId:observed.py", "observed.py", "old", "new")),
                events = listOf(AiToolEvent(observedEventId, "fixture-call", AiTool.RunCommand, "run_command", "{}", "", "fixture",
                    status = AiToolStatus.Succeeded, turn = 1))))
            val languages = EditorLanguages(context)
            warmDeviceActivity()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val dark = isSystemInDarkTheme()
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                                    AiConversationChangeCard(shown.value, 1, languages,
                                        onUndo = { error("No volatile editor undo exists") },
                                        loadCheckpoints = { withContext(Dispatchers.IO) {
                                            store.list(project).map { if (markUnsealed.get()) it.copy(sealed = false) else it }
                                        } },
                                        loadChanges = { key -> withContext(Dispatchers.IO) {
                                            if (failRead.get()) error("UUID-owned fixture read failure")
                                            store.changes(project, key)
                                        } })
                                }
                            }
                        }
                    }
                } }
                await("failure remains visible beside observed row") {
                    find(context.getString(R.string.ai_changes_read_failed)) != null &&
                        find(context.getString(R.string.ai_changes_files_count, 1)) != null
                }
                assertNotNull(find(context.getString(R.string.editor_retry)))
                assertNotNull(find(context.getString(R.string.ai_changes_observed_summary)))
                assertNotNull(find(context.getString(R.string.ai_command_review_limited)))
                screenshot("conversation-change-observed-failure")
                withContext(Dispatchers.Main) { shown.value = shown.value.copy(active = true) }
                await("running state still visible with observed changes") { find(context.getString(R.string.ai_changes_running)) != null }
                withContext(Dispatchers.Main) { shown.value = shown.value.copy(active = false, restoring = true) }
                await("restoring state still visible with observed changes") { find(context.getString(R.string.ai_changes_restoring)) != null }
                markUnsealed.set(true)
                withContext(Dispatchers.Main) { shown.value = shown.value.copy(restoring = false) }
                await("unsealed state still visible with observed changes") { find(context.getString(R.string.ai_checkpoint_unsealed)) != null }
                markUnsealed.set(false)
                withContext(Dispatchers.Main) { shown.value = shown.value.copy(workspaceRevision = shown.value.workspaceRevision + 1) }
                await("retryable failure returns") { find(context.getString(R.string.editor_retry)) != null }
                failRead.set(false)
                click(context.getString(R.string.editor_retry))
                val count = context.getString(R.string.ai_changes_files_count, 2)
                await("checkpoint retry uses only the sealed checkpoint scope") {
                    find(count) != null && find(context.getString(R.string.ai_changes_read_failed)) == null
                }
                assertNull(find(context.getString(R.string.ai_changes_observed_summary)))
                click(count)
                await("empty files listed") { find("created-empty.txt") != null && find("deleted-empty.txt") != null }
                click("created-empty.txt")
                await("empty creation has explicit presence label") { find(context.getString(R.string.ai_changes_file_created)) != null }
                assertNotNull(find(context.getString(R.string.ai_changes_full_review)))
                screenshot("conversation-change-empty-created")
                click("created-empty.txt")
                click("deleted-empty.txt")
                await("empty deletion has explicit presence label") { find(context.getString(R.string.ai_changes_file_deleted)) != null }
                screenshot("conversation-change-empty-deleted")
                click(context.getString(R.string.ai_changes_full_review))
                await("full review retains before after and diff tabs") {
                    find(context.getString(R.string.ai_review_before)) != null &&
                        find(context.getString(R.string.ai_review_after)) != null &&
                        find(context.getString(R.string.ai_review_diff)) != null
                }
                click(context.getString(R.string.ai_review_after))
                await("deleted file has no after version") { find(context.getString(R.string.ai_review_missing)) != null }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { check(fixture.deleteRecursively()) }
        }
    }

}

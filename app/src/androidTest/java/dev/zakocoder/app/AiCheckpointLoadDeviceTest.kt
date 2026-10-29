package dev.zakocoder.app

import android.content.ContextWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.AiCheckpointSheet
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Corrupt only our own checkpoint body; retry must distinguish failure from a successful empty diff. */
class AiCheckpointLoadDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(70) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun click(text: String) {
        await("visible $text") { find(text) != null }
        var node = find(text)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(text, node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test(timeout = 120000) fun corruptBodyShowsOnlyFailureAndRetryLoadsActualChanges() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AiDeviceTest.Fixture(context).use { f ->
            val project = UUID.randomUUID().toString()
            val conversation = UUID.randomUUID().toString()
            val storage = File(f.root, "isolated-files").apply { mkdirs() }
            val privateContext = object : ContextWrapper(context) {
                override fun getFilesDir() = storage
                override fun getNoBackupFilesDir() = File(storage, "no-backup").apply { mkdirs() }
                override fun stopService(service: android.content.Intent) = false
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${f.token}-$name", mode)
            }
            val root = File(storage, "workspaces/$project").apply { mkdirs() }
            lateinit var body: File
            lateinit var original: ByteArray
            withContext(Dispatchers.IO) {
                File(root, "actual.py").writeText("before\n")
                val checkpoints = AiCheckpoints(f.history.checkpointDirectory) { root }
                val key = checkpoints.begin(project, conversation, 1)
                File(root, "actual.py").writeText("after\n")
                checkpoints.update(project, key, true)
                f.history.save(project, AiState(conversationId = conversation, turn = 1))
                val folder = f.history.checkpointDirectory.listFiles()!!.single()
                val dir = File(folder, key)
                val entry = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("entries").getJSONObject(0)
                body = File(dir, entry.getJSONObject("after").getString("hash"))
                original = body.readBytes(); body.writeText("OWN_CORRUPT_BODY")
            }
            lateinit var workbench: Workbench
            withContext(Dispatchers.Main) {
                workbench = Workbench(privateContext, f.transport, f.config, AiPermissionStore(privateContext), f.history)
                workbench.ai.select(project)
            }
            await("fixture history loaded") { !workbench.ai.state.value.permissionsLoading && workbench.ai.state.value.turn == 1 }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { MaterialTheme { Surface { AiCheckpointSheet(workbench) {} } } } }
                click(context.getString(R.string.ai_checkpoint_row, 1, 1))
                await("corrupt body reports failure") { find(context.getString(R.string.ai_checkpoint_load_error)) != null }
                assertNull(find(context.getString(R.string.ai_no_changes)))
                assertNull(find(context.getString(R.string.ai_checkpoint_restore)))
                assertNull(find("actual.py"))
                withContext(Dispatchers.IO) { body.writeBytes(original) }
                click(context.getString(R.string.editor_retry))
                await("retry reads actual body") { find("actual.py") != null }
                assertNull(find(context.getString(R.string.ai_checkpoint_load_error)))
                assertNotNull(find(context.getString(R.string.ai_checkpoint_restore)))
                assertEquals(0, f.server.requestCount)
            }
            // Only namespaced preferences created by this fixture are removed.
            withContext(Dispatchers.Main) { workbench.stopAll() }
            listOf("projects", "development-settings").forEach { context.deleteSharedPreferences("${f.token}-$it") }
        }
    }
}

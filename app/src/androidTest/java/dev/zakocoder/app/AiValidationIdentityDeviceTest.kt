package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual checkpoint bodies and validation panel; delayed IO is controlled without touching user data. */
class AiValidationIdentityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(50) } }
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
        assertTrue(label, node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); delay(250)
    }
    private fun reviewText(): String? {
        var text: String? = null
        fun walk(view: android.view.View) {
            if (view is io.github.rosemoe.sora.widget.CodeEditor && !view.isEditable && view.isShown) text = view.text.toString()
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::walk) }
        return text
    }
    private suspend fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "identity"
            File(context.cacheDir, "ui-review/$theme/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun switchingTurnsDropsPreviousPathsBeforeReadAndCorruptBodiesStayUnknown() = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val host = (context.applicationContext as ZakoApplication).workbench
            await("host ready") { !host.state.value.loading && !host.state.value.busy }
            val previous = host.state.value.selectedId
            var owned: String? = null
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val cancelledReads = java.util.concurrent.atomic.AtomicInteger()
            val startedReads = java.util.concurrent.atomic.AtomicInteger()
            try {
                val name = "validation-identity-${UUID.randomUUID()}"
                main { host.create(name) }; await("own project") { !host.state.value.busy && host.state.value.project?.name == name }
                val id = host.state.value.selectedId!!; owned = id
                AiDeviceTest.Fixture(context).use { f ->
                    val conversation = UUID.randomUUID().toString()
                    val root = host.projects.directory(id)
                    lateinit var first: String
                    lateinit var second: String
                    withContext(Dispatchers.IO) {
                        File(root, "a.py").writeText("A_BEFORE\n"); File(root, "b.py").writeText("B_BEFORE\n")
                        val store = AiCheckpoints(f.history.checkpointDirectory, host.projects::directory)
                        first = store.begin(id, conversation, 1)
                        File(root, "a.py").writeText("A_AFTER\n"); store.update(id, first, true)
                        second = store.begin(id, conversation, 2)
                        File(root, "b.py").writeText("B_AFTER\n"); store.update(id, second, true)
                        f.history.save(id, AiState(projectId = id, conversationId = conversation, turn = 2, phase = AiPhase.Completed))
                    }
                    val privateFiles = withContext(Dispatchers.IO) { File(f.root, "permissions").apply { mkdirs() } }
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir() = privateFiles }
                    lateinit var w: Workbench
                    main { w = Workbench(context, f.transport, f.config, AiPermissionStore(isolated), f.history); w.ai.select(id) }
                    await("isolated history") { !w.state.value.loading && !w.ai.state.value.permissionsLoading && w.ai.state.value.turn == 2 }
                    scenario.onActivity { it.setContent {
                        val dark = isSystemInDarkTheme()
                        CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface { AiValidationPanel(w, Modifier.fillMaxSize().safeDrawingPadding(), {}, {}, loadCheckpointChanges = { key ->
                                    if (key == first && !release.isCompleted) {
                                        startedReads.incrementAndGet(); entered.complete(Unit)
                                        try { release.await() } catch (cancelled: CancellationException) { cancelledReads.incrementAndGet(); throw cancelled }
                                    }
                                    w.ai.checkpointChanges(key)
                                }) }
                            }
                        }
                    } }
                    await("second turn b.py") { find("b.py") != null }
                    click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                    await("first read paused") { entered.isCompleted }
                    screenshot("turn-read-paused")
                    assertNull("Previous turn path must disappear while the first checkpoint read is paused", find("b.py"))
                    assertNull(find("a.py"))
                    assertNotNull(find(context.getString(R.string.loading)))
                    click(context.getString(R.string.project_validation_turn, 1)); click(context.getString(R.string.project_validation_turn, 2))
                    await("return to second turn cancels paused read") { cancelledReads.get() == 1 && find("b.py") != null }
                    assertNull(find("a.py"))
                    click(context.getString(R.string.project_validation_turn, 2)); click(context.getString(R.string.project_validation_turn, 1))
                    await("first turn starts fresh read") { startedReads.get() == 2 }
                    assertNull(find("b.py")); assertNull(find("a.py"))
                    release.complete(Unit)
                    await("first turn a.py") { find("a.py") != null }
                    assertNull(find("b.py")); screenshot("first-turn-ready")
                    click("a.py")
                    await("actual a.py review") { reviewText()?.let { it.contains("A_BEFORE") && it.contains("A_AFTER") } == true }
                    assertNull(find("b.py")); screenshot("first-turn-review")
                    click(context.getString(R.string.close))
                    // Corrupt only the generated second checkpoint's after-body, keeping metadata intact.
                    withContext(Dispatchers.IO) {
                        val folder = f.history.checkpointDirectory.listFiles()!!.single()
                        val dir = File(folder, second)
                        val entries = JSONObject(File(dir, "manifest.json").readText()).getJSONArray("entries")
                        val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "b.py" }
                        File(dir, entry.getJSONObject("after").getString("hash")).writeText("CORRUPTED_OWN_BLOB")
                    }
                    click(context.getString(R.string.project_validation_turn, 1)); click(context.getString(R.string.project_validation_turn, 2))
                    await("corrupt checkpoint reports failure") { find(context.getString(R.string.project_validation_changes_unavailable)) != null }
                    assertNull(find(context.getString(R.string.project_changes_empty)))
                    assertNull(find("a.py")); assertNull(find("b.py"))
                    screenshot("second-turn-read-failed")
                    assertEquals(0, f.server.requestCount)
                }
            } finally {
                release.complete(Unit)
                owned?.let { id -> main { host.removeProject(id) }; await("remove own project") { !host.state.value.busy && host.state.value.projects.none { it.id == id } } }
                previous?.let { id -> main { host.select(id) }; await("restore project") { !host.state.value.busy } }
            }
        }
    }
}

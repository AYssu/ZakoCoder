package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run prepare, terminate app process, then verify. Owns only this reserved cache subtree. */
class WorkflowRestartDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = File(context.cacheDir, "workflow-process-proof")
    private val wrapper get() = object : ContextWrapper(context) {
        override fun getFilesDir() = File(base, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("workflow-process-proof-$name", mode)
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(30) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    @Test(timeout = 60000) fun prepareDurableStateForNewProcess() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            withContext(Dispatchers.IO) { check(!base.exists()) { "Verify/clean previous owned fixture first" }; base.mkdirs() }
            val repo = ProjectRepository(wrapper, File(base, "workspaces").apply { mkdirs() })
            val project = withContext(Dispatchers.IO) { repo.create("process proof").also { File(repo.directory(it.id), "a.kt").writeText("original source") } }
            lateinit var editor: EditorController
            main { editor = EditorController(wrapper, repo, {}) }; await { !editor.state.value.loading }
            main { editor.open(project.id, "a.kt") }; await { !editor.state.value.loading && editor.state.value.document != null }
            main { editor.state.value.document!!.content.replace(0, editor.state.value.document!!.content.length, "draft across real process"); editor.changed() }
            delay(1000)
            val history = AiHistoryStore(File(base, "history"))
            val key = withContext(Dispatchers.IO) {
                val cp = AiCheckpoints(File(base, "history-checkpoints"), repo::directory)
                val key = cp.begin(project.id, "process-proof", 1)
                File(repo.directory(project.id), "a.kt").writeText("task result")
                cp.update(project.id, key, true)
                history.save(project.id, AiState(checkpointId = key, turn = 1, phase = AiPhase.Completed))
                key
            }
            lateinit var tasks: ProjectTasks
            main { tasks = ProjectTasks(wrapper, repo::directory, { _, _, _, _ -> error("No execution in prepare") }, { true }, {}); tasks.select(project.id) }
            await { !tasks.state.value.loading }
            main { tasks.save(ProjectCommand("build", "printf 'process proof'", timeoutSeconds = 3600)) }; await { tasks.state.value.commands.size == 1 }
            withContext(Dispatchers.IO) { File(base, "proof.json").writeText(JSONObject().put("pid", Process.myPid()).put("project", project.id).put("checkpoint", key).toString()) }
            // Intentionally do not close/discard draft; the next test runs in a different app process.
        }
    }
    @Test(timeout = 60000) fun verifyDurableStateInNewProcessAndClean() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val proof = withContext(Dispatchers.IO) { JSONObject(File(base, "proof.json").readText()) }
                assertNotEquals("Must be a new app process", proof.getInt("pid"), Process.myPid())
                val id = proof.getString("project"); val key = proof.getString("checkpoint")
                val repo = ProjectRepository(wrapper, File(base, "workspaces"))
                val saved = withContext(Dispatchers.IO) { AiHistoryStore(File(base, "history")).load(id).state!! }
                assertEquals(key, saved.checkpointId); assertEquals(1, saved.turn)
                lateinit var editor: EditorController
                main { editor = EditorController(wrapper, repo, {}) }; await { !editor.state.value.loading }
                assertEquals(1, editor.state.value.tabs.size)
                main { editor.open(id, "a.kt") }; assertEquals("draft across real process", editor.state.value.document!!.content.toString())
                main { editor.save() }; await { !editor.state.value.saving }; assertTrue(editor.state.value.conflict)
                withContext(Dispatchers.IO) {
                    val cp = AiCheckpoints(File(base, "history-checkpoints"), repo::directory)
                    assertEquals("task result", cp.changes(id, key).single().after)
                    assertTrue(runCatching { cp.restore(id, key) { path -> editor.isOpen(id, path) } }.isFailure)
                    assertEquals("task result", File(repo.directory(id), "a.kt").readText())
                }
                main { editor.close() }; delay(600)
                withContext(Dispatchers.IO) {
                    val cp = AiCheckpoints(File(base, "history-checkpoints"), repo::directory)
                    cp.restore(id, key) { false }
                    assertEquals("original source", File(repo.directory(id), "a.kt").readText())
                }
                lateinit var tasks: ProjectTasks
                main { tasks = ProjectTasks(wrapper, repo::directory, { _, _, _, _ -> CommandResult(0, "unused") }, { true }, {}); tasks.select(id) }
                await { !tasks.state.value.loading }
                assertEquals(3600, tasks.state.value.commands.single().timeoutSeconds)
            } finally { withContext(NonCancellable + Dispatchers.IO) { base.deleteRecursively() } }
        }
    }
}

package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.ProjectCommand
import dev.zakocoder.app.workflow.ProjectTasks
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual persistence with isolated files and a controlled executor; no guest or network access. */
class DependencyRunDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: Context) : AutoCloseable {
        val base = File(context.cacheDir, "dependency-record-${UUID.randomUUID()}").apply { mkdirs() }
        val project = File(base, "project").apply { mkdirs(); File(this, "source.py").writeText("print('fixture')\n") }
        val wrapper = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
        }
        fun tasks() = ProjectTasks(wrapper, { project }, { _, _, _, _ -> error("Default executor must not run") }, { true }, {})
        override fun close() { base.deleteRecursively() }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10000) { while (!condition()) delay(20) }

    @Test(timeout = 60000) fun dependencyFailureRetainsHeadTailAndReloadsWithoutBecomingValidation() = runBlocking {
        val f = withContext(Dispatchers.IO) { Fixture(context) }
        try {
            val tasks = f.tasks()
            withContext(Dispatchers.Main) { tasks.select("fixture") }
            await { !tasks.state.value.loading }
            // Identical text must still remain a dependency run, rather than a passing/failed test.
            withContext(Dispatchers.Main) { tasks.save(ProjectCommand("test", "npm install")) }
            await { !tasks.state.value.saving }
            assertEquals("test", tasks.state.value.commands.single().kind)
            val head = "DEPENDENCY_HEAD\n"
            val middle = "m".repeat(600000)
            val tail = "\nDEPENDENCY_FAILURE_TAIL\n"
            var forwarded = 0L
            val result = tasks.observed("fixture", "npm install", 30000, { forwarded += it.length }, source = "dependency",
                executeCommand = { id, script, timeout, output ->
                    assertEquals("fixture", id); assertEquals("npm install", script); assertEquals(30000L, timeout)
                    output(head); output(middle); output(tail)
                    CommandResult(23, "executor return is not substituted for streamed log")
                })
            assertEquals(23, result.exitCode)
            assertNotNull(result.runId)
            val loaded = f.tasks()
            val run = withContext(Dispatchers.IO) { loaded.runs("fixture").single() }
            assertEquals(result.runId, run.id)
            assertEquals("dependency", run.kind); assertEquals("dependency", run.source)
            assertEquals("failed", run.status); assertEquals(23, run.exit)
            assertEquals(30000L, run.timeoutMillis)
            assertTrue(run.truncated)
            assertEquals((head.length + middle.length + tail.length).toLong(), run.outputChars)
            assertEquals(run.outputChars, forwarded)
            val log = loaded.log("fixture", run.id)
            val original = head + middle + tail
            assertTrue(log.startsWith(original.take(64000))); assertTrue(log.endsWith(original.takeLast(448000)))
            assertTrue(log.contains("middle omitted")); assertTrue(log.length < 513000)
            val page = JSONObject(loaded.logPage("fixture", run.id, limit = 1000, query = "DEPENDENCY_FAILURE_TAIL"))
            assertTrue(page.getBoolean("query_found")); assertTrue(page.getString("text").contains("DEPENDENCY_FAILURE_TAIL"))
            assertEquals(23, page.getInt("exit_code")); assertTrue(page.getBoolean("log_truncated"))
            val installed = tasks.observed("fixture", "npm install", 30000, {}, source = "dependency",
                executeCommand = { _, _, _, output -> output("DEPENDENCY_SUCCESS\n"); CommandResult(0, "") })
            val success = withContext(Dispatchers.IO) { loaded.runs("fixture").single { it.id == installed.runId } }
            assertEquals("dependency", success.kind); assertEquals("dependency", success.source)
            assertEquals("passed", success.status); assertEquals(0, success.exit)
            // The existing default classification remains unchanged for unrelated callers.
            val ordinary = tasks.observed("fixture", "npm install", 30000, {},
                executeCommand = { _, _, _, output -> output("TEST_RESULT\n"); CommandResult(0, "") })
            val normal = withContext(Dispatchers.IO) { loaded.runs("fixture").single { it.id == ordinary.runId } }
            assertEquals("test", normal.kind); assertEquals("user", normal.source); assertEquals("passed", normal.status)
        } finally { withContext(NonCancellable + Dispatchers.IO) { f.close() } }
    }

    @Test(timeout = 60000) fun cancelledDependencyPersistsActualPartialOutputAndNullExitOnReload() = runBlocking {
        val f = withContext(Dispatchers.IO) { Fixture(context) }
        try {
            val tasks = f.tasks()
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                tasks.observed("fixture", "cargo fetch", 30000, {}, source = "dependency", executeCommand = { _, _, _, output ->
                    output("DEPENDENCY_PARTIAL_BEFORE_CANCEL\n")
                    entered.complete(Unit)
                    awaitCancellation()
                })
            }
            withTimeout(10000) { entered.await() }
            val running = withContext(Dispatchers.IO) { tasks.runs("fixture").single() }
            assertEquals("running", running.status)
            assertEquals("interrupted", withContext(Dispatchers.IO) { f.tasks().runs("fixture").single().status })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            val loaded = f.tasks()
            val run = withContext(Dispatchers.IO) { loaded.runs("fixture").single() }
            assertEquals("dependency", run.kind); assertEquals("dependency", run.source)
            assertEquals("cancelled", run.status); assertNull(run.exit)
            assertEquals("DEPENDENCY_PARTIAL_BEFORE_CANCEL\n", loaded.log("fixture", run.id))
            val page = JSONObject(loaded.logPage("fixture", run.id, query = "PARTIAL_BEFORE_CANCEL"))
            assertTrue(page.getBoolean("query_found")); assertEquals("cancelled", page.getString("status")); assertTrue(page.isNull("exit_code"))
        } finally { withContext(NonCancellable + Dispatchers.IO) { f.close() } }
    }
    @Test(timeout = 60000) fun activeDependencyBelongsOnlyToItsExecutingInstanceUntilCompletion() = runBlocking {
        val f = withContext(Dispatchers.IO) { Fixture(context) }
        try {
            val tasks = f.tasks()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val execution = async {
                tasks.observed("fixture", "go mod download", 30000, {}, source = "dependency", executeCommand = { _, _, _, output ->
                    output("DEPENDENCY_WAITING_FOR_COMPLETION\n")
                    entered.complete(Unit)
                    finish.await()
                    output("DEPENDENCY_DONE\n")
                    CommandResult(0, "")
                })
            }
            try {
                withTimeout(10000) { entered.await() }
                val running = withContext(Dispatchers.IO) { tasks.runs("fixture").single() }
                assertEquals("running", running.status); assertNull(running.exit)
                // A reconstructed instance has no live executor; do not resurrect an old process.
                val fresh = withContext(Dispatchers.IO) { f.tasks().runs("fixture").single() }
                assertEquals(running.id, fresh.id); assertEquals("interrupted", fresh.status)
                finish.complete(Unit)
                val result = withTimeout(10000) { execution.await() }
                assertEquals(running.id, result.runId)
                for (reader in listOf(tasks, f.tasks())) {
                    val done = withContext(Dispatchers.IO) { reader.runs("fixture").single() }
                    assertEquals("passed", done.status); assertEquals(0, done.exit)
                    assertEquals("dependency", done.kind)
                }
            } finally { execution.cancelAndJoin() }
        } finally { withContext(NonCancellable + Dispatchers.IO) { f.close() } }
    }

}

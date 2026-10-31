package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.ProjectGit
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.workflow.ProjectCommand
import dev.zakocoder.app.workflow.ProjectSourceStamp
import dev.zakocoder.app.workflow.ProjectTasks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real controllers with simulated process boundaries; never invokes a guest or reads credentials. */
class GitTaskOwnershipDeviceTest {
    @Test fun projectRunCannotEnterBetweenGitCommandsAndResumesAfterGitFinishes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "git-task-ownership-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir() = directory }
        val gap = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var processBusy = false
        var projectExecutions = 0
        val git = ProjectGit(context, { _, script ->
            if ("for-each-ref" in script) {
                assertFalse(processBusy)
                gap.complete(Unit)
                resume.await() // Previous process exited; next command has not started.
            }
            processBusy = true
            try {
                when {
                    "status --porcelain" in script -> CommandResult(0, "# branch.head main\u0000")
                    "for-each-ref" in script -> CommandResult(0, "main\n")
                    script.endsWith(" remote") -> CommandResult(0, "")
                    "MERGE_HEAD" in script -> CommandResult(1, "")
                    else -> error("Unexpected simulated Git command")
                }
            } finally { processBusy = false }
        }, { true })
        val tasks = ProjectTasks(context, { directory }, { _, _, _, _ ->
            projectExecutions++
            CommandResult(0, "fixture completed")
        }, { !processBusy && !git.state.value.busy }, {},
            captureSource = { ProjectSourceStamp("a".repeat(64), false, 0, 0) })
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            main { tasks.select("fixture") }
            withTimeout(10000) { tasks.state.first { !it.loading } }
            main { git.select("fixture") }
            withTimeout(10000) { gap.await() }
            assertTrue(git.state.value.busy)
            assertFalse(processBusy)
            main { tasks.run(ProjectCommand("test", "fixture-test")) }
            assertFalse(tasks.state.value.busy)
            assertEquals(0, projectExecutions)
            assertTrue(tasks.state.value.runs.isEmpty())
            resume.complete(Unit)
            withTimeout(10000) { git.state.first { !it.busy && it.statusKnown } }
            main { tasks.run(ProjectCommand("test", "fixture-test")) }
            val result = withTimeout(10000) { tasks.state.first { !it.busy && it.runs.isNotEmpty() } }
            assertEquals(1, projectExecutions)
            assertEquals(0, result.runs.single().exit)
            assertEquals("passed", result.runs.single().status)
        } finally {
            resume.complete(Unit)
            main { git.stop(); tasks.stop() }
            directory.deleteRecursively()
        }
    }
}

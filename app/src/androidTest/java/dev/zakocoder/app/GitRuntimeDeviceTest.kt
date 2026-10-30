package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.git.ProjectGit
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises production Git commands in an isolated real guest repository, never a user repository. */
class GitRuntimeDeviceTest {
    @Test fun realBranchesConflictResolutionAndIndex() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZakoApplication
        val workbench = app.workbench
        withTimeout(15000) { workbench.state.first { !it.loading } }
        assumeTrue("Requires installed Ubuntu and Git", workbench.state.value.installed)
        val project = withContext(Dispatchers.IO) { workbench.projects.create("git-device-verification") }
        val directory = withContext(Dispatchers.IO) { workbench.projects.directory(project.id) }
        val git = ProjectGit(app, { id, script -> workbench.runtime.execute(script, id, workbench.state.value.settings) }, { true })
        suspend fun action(block: () -> Unit) {
            withContext(Dispatchers.Main) { block() }
            withTimeout(60000) { git.state.first { !it.busy } }
        }
        suspend fun write(value: String) = withContext(Dispatchers.IO) { File(directory, "file with spaces.txt").writeText(value) }
        fun success() { assertEquals(git.state.value.output, "", git.state.value.error); assertTrue(git.state.value.repository) }
        try {
            action { git.select(project.id) }
            action { git.initialize() }; success()
            write("base\n"); action { git.refresh() }
            action { git.stage(setOf("file with spaces.txt")) }; success()
            action { git.commit("initial", "Device fixture", "fixture@example.invalid") }; success()
            val initial = git.state.value.branch
            action { git.createBranch("feature/test") }; success()
            write("feature\n"); action { git.refresh() }; action { git.stage(setOf("file with spaces.txt")) }
            action { git.commit("feature", "Device fixture", "fixture@example.invalid") }; success()
            action { git.switchBranch(initial) }; success()
            write("main\n"); action { git.refresh() }; action { git.stage(setOf("file with spaces.txt")) }
            action { git.commit("main", "Device fixture", "fixture@example.invalid") }; success()
            action { git.mergeBranch("feature/test", "Device fixture", "fixture@example.invalid") }
            assertTrue(git.state.value.merging)
            assertEquals(listOf("file with spaces.txt"), git.state.value.conflicts)
            write("resolved\n"); action { git.resolve(setOf("file with spaces.txt")) }; success()
            assertTrue(git.state.value.conflicts.isEmpty())
            action { git.commit("resolve", "Device fixture", "fixture@example.invalid") }; success()
            assertFalse(git.state.value.merging)
            write("more\n"); action { git.refresh() }; action { git.stage(setOf("file with spaces.txt")) }
            action { git.unstage(setOf("file with spaces.txt")) }; success()
            assertEquals(' ', git.state.value.entries.single().index)
            assertEquals('M', git.state.value.entries.single().working)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { git.stop() }
            withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(directory) }
        }
    }
}

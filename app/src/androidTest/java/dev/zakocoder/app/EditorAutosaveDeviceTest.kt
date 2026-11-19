package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.editor.EditorDiskState
import dev.zakocoder.app.projects.ProjectRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real private files, isolated controllers; never touches a user's registered workspace. */
class EditorAutosaveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun await(block: () -> Boolean) = withTimeout(15000) { while (!block()) delay(40) }

    @Test(timeout = 60000) fun periodicSaveKeepsTypingAndOtherTabsAndRestoresLastFile() = runBlocking {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "editor-autosave-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val repo = ProjectRepository(isolated, File(root, "workspaces").apply { mkdirs() })
        val a = withContext(Dispatchers.IO) { repo.create("A", mapOf("main.py" to "start\n", "helper.py" to "helper\n")) }
        val b = withContext(Dispatchers.IO) { repo.create("B", mapOf("other.py" to "other\n")) }
        val source = File(repo.directory(a.id), "main.py")
        val controllers = mutableListOf<EditorController>()
        fun make(): EditorController {
            lateinit var value: EditorController
            main { value = EditorController(isolated, repo, {}) }
            controllers += value
            return value
        }
        try {
            val editor = make()
            await { !editor.state.value.loading }
            main { editor.open(a.id, "main.py") }; await { editor.state.value.document != null && !editor.state.value.loading }
            // Continuous input must not postpone every disk write indefinitely.
            repeat(18) {
                main { val doc = editor.state.value.document!!; doc.content.replace(doc.content.length, doc.content.length, "x"); editor.changed() }
                delay(220)
            }
            assertTrue("Periodic save occurs while typing", withContext(Dispatchers.IO) { source.readText().length > "start\n".length })
            main { editor.open(a.id, "helper.py") }; await { !editor.state.value.loading && !editor.state.value.saving }
            main {
                val doc = editor.state.value.document!!
                doc.content.replace(doc.content.length, doc.content.length, "changed"); doc.line = 0; doc.column = 3; editor.changed(); editor.cursorChanged()
                editor.open(b.id, "other.py")
            }
            await { editor.state.value.projectId == b.id && !editor.state.value.loading }
            await { File(repo.directory(a.id), "helper.py").readText() == "helper\nchanged" }
            await { source.readText().length == "start\n".length + 18 }
            withContext(Dispatchers.Main) { editor.restoreWorkspace(a.id) }
            await { editor.state.value.projectId == a.id && !editor.state.value.loading }
            assertEquals("helper.py", editor.state.value.path)
            assertEquals(3, editor.state.value.document!!.column)
            delay(800)
            main { editor.dispose() }
            val restarted = make(); await { !restarted.state.value.loading }
            withContext(Dispatchers.Main) { restarted.restoreWorkspace(a.id) }
            await { !restarted.state.value.loading && restarted.state.value.document != null }
            assertEquals("helper.py", restarted.state.value.path)
            assertEquals(3, restarted.state.value.document!!.column)
            assertEquals("helper\nchanged", restarted.state.value.document!!.content.toString())
        } finally {
            main { controllers.forEach(EditorController::dispose) }
            withContext(Dispatchers.IO) { root.deleteRecursively() }
        }
    }

    @Test(timeout = 45000) fun autosaveProtectsExternalEditsAndRefreshesOnlyCleanBuffers() = runBlocking {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "editor-conflict-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val repo = ProjectRepository(isolated, File(root, "workspaces").apply { mkdirs() })
        val project = withContext(Dispatchers.IO) { repo.create("Conflict", mapOf("main.py" to "original")) }
        val source = File(repo.directory(project.id), "main.py")
        lateinit var editor: EditorController
        var initialized = false
        try {
            main { editor = EditorController(isolated, repo, {}) }; initialized = true; await { !editor.state.value.loading }
            main { editor.open(project.id, "main.py") }; await { editor.state.value.document != null && !editor.state.value.loading }
            main { val doc = editor.state.value.document!!; doc.content.replace(doc.content.length, doc.content.length, " local"); editor.changed() }
            withContext(Dispatchers.IO) { source.writeText("external") }
            await { editor.state.value.conflict && !editor.state.value.saving }
            assertEquals("original local", editor.state.value.document!!.content.toString())
            delay(3300)
            assertEquals("Failed autosave must not rebase and overwrite external changes", "external", source.readText())
            main { editor.reload() }; await { !editor.state.value.loading && !editor.state.value.dirty }
            withContext(Dispatchers.IO) { source.writeText("new clean disk") }
            main { editor.checkDisk() }; await { !editor.state.value.diskChecking }
            assertEquals(EditorDiskState.Unchanged, editor.state.value.diskState)
            assertEquals("new clean disk", editor.state.value.document!!.content.toString())
        } finally {
            if (initialized) main { editor.dispose() }
            withContext(Dispatchers.IO) { root.deleteRecursively() }
        }
    }
}

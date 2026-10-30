package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.editor.EditorFileStore
import dev.zakocoder.app.ai.AiProjectFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses only an owned external fixture, never an existing user project. Requires file access. */
class LocalProjectBindingDeviceTest {
    @Test(timeout = 45000) fun originalFilesSurviveReloadAndDetach() = runBlocking {
        withContext(Dispatchers.IO) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val token = "zako binding 测试 ${UUID.randomUUID()}"
            val own = File(base.cacheDir, token).apply { mkdirs() }
            val external = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), token)
            val preferences = mutableSetOf<String>()
            val context = object : ContextWrapper(base) {
                override fun getFilesDir() = File(own, "files").apply { mkdirs() }
                override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name".also { preferences += it }, mode)
            }
            try {
                assertTrue("Grant file access before running the binding device test", external.mkdirs())
                val original = File(external, "README.md").apply { writeText("before") }
                val managed = File(own, "workspaces").apply { mkdirs() }
                val interrupted = ProjectRepository(context, managed)
                assertTrue(interrupted.load().isEmpty())
                val orphan = interrupted.bindLocal(external.absolutePath)
                // Simulate death before the project registry is written: only stale metadata is removed.
                val first = ProjectRepository(context, managed)
                assertTrue(first.load().isEmpty())
                assertFalse(first.isBound(orphan.id))
                assertEquals("before", original.readText())
                val project = first.bindLocal(external.absolutePath)
                first.save(listOf(project))
                assertEquals(external.canonicalFile, first.directory(project.id))
                val editor = EditorFileStore(first::directory)
                editor.save(editor.open(project.id, "README.md"), "editor saved")
                assertEquals("editor saved", original.readText())
                val aiFiles = AiProjectFiles(first::directory)
                aiFiles.write(project.id, "README.md", aiFiles.read(project.id, "README.md"), "after")
                assertEquals("after", original.readText())
                val runtime = LinuxRuntime(base).apply { projectDirectory = first::directory }
                assertTrue("Install Ubuntu before running the full binding fixture", runtime.isInstalled())
                val executed = runtime.execute("printf '%s' terminal > terminal.txt; pwd", project.id, AppSettings(), timeoutMillis = 30000)
                assertEquals(executed.output, 0, executed.exitCode)
                assertTrue(executed.output.contains("/workspace/${project.id}"))
                assertEquals("terminal", File(external, "terminal.txt").readText())
                assertFalse("Binding must not make a private working copy", File(managed, project.id).exists())
                val second = ProjectRepository(context, managed)
                assertEquals(listOf(project), second.load())
                assertTrue(second.isBound(project.id))
                assertEquals(external.canonicalPath, second.bindingPath(project.id))
                assertEquals("after", File(second.directory(project.id), "README.md").readText())
                val registry = File(context.filesDir, "projects-v1.json")
                val registryBytes = registry.readBytes()
                try {
                    registry.writeText("{invalid")
                    val failedRead = ProjectRepository(context, managed)
                    assertThrows(Exception::class.java) { failedRead.load() }
                    assertTrue("A registry parse failure must not detach a known binding", failedRead.isBound(project.id))
                } finally { registry.writeBytes(registryBytes) }
                val unavailable = File(external.parentFile, "$token-moved")
                assertTrue(external.renameTo(unavailable))
                try {
                    val reloaded = ProjectRepository(context, managed)
                    assertEquals("Temporarily missing bindings remain discoverable", listOf(project), reloaded.load())
                    assertThrows(IllegalArgumentException::class.java) { reloaded.directory(project.id) }
                } finally { assertTrue(unavailable.renameTo(external)) }
                second.save(emptyList())
                second.removeBinding(project.id)
                val detached = ProjectRepository(context, managed)
                assertTrue(detached.load().isEmpty())
                assertFalse(detached.isBound(project.id))
                assertTrue(original.isFile)
                assertEquals("after", original.readText())
            } finally {
                if (external.exists()) PrivateFiles.deleteTree(external)
                PrivateFiles.deleteTree(own)
                preferences.forEach { base.deleteSharedPreferences(it) }
            }
        }
    }
}

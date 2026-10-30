package dev.zakocoder.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProjectFileDeviceTest {
    @Test(timeout = 45000) fun importDoesNotOverwriteAndCancellationCleansTemporaryFile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val token = "file-check-${UUID.randomUUID()}"
        val own = File(base.cacheDir, token)
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files")
            override fun getCacheDir() = File(own, "cache")
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name".also { preferences += it }, mode)
        }
        val workbench = withContext(Dispatchers.Main) { Workbench(context) }
        try {
            await { !workbench.state.value.loading }
            instrumentation.runOnMainSync { workbench.create(token) }
            await { !workbench.state.value.busy && workbench.state.value.project != null }
            val id = workbench.state.value.project!!.id
            val root = withContext(Dispatchers.IO) { workbench.projects.directory(id) }
            val imported = Uri.parse("content://${instrumentation.context.packageName}.import-fixture/normal")
            instrumentation.runOnMainSync { workbench.importFile(id, "", imported) }
            await { !workbench.state.value.busy }
            assertTrue(workbench.state.value.message, workbench.state.value.message.isEmpty())
            assertEquals("ZAKO_FILE_IMPORT_OK\n", withContext(Dispatchers.IO) { File(root, "imported.txt").readText() })
            withContext(Dispatchers.IO) { File(root, "imported.txt").writeText("KEEP") }
            instrumentation.runOnMainSync { workbench.importFile(id, "", imported) }
            await { !workbench.state.value.busy }
            assertTrue(workbench.state.value.message.contains(base.getString(R.string.file_already_exists)))
            assertEquals("KEEP", withContext(Dispatchers.IO) { File(root, "imported.txt").readText() })
            instrumentation.runOnMainSync { workbench.importFile(id, "", Uri.parse("content://${instrumentation.context.packageName}.import-fixture/invalid")) }
            await { !workbench.state.value.busy }
            assertFalse(withContext(Dispatchers.IO) { File(root.parentFile, "outside.txt").exists() })
            assertTrue(workbench.state.value.message.contains(base.getString(R.string.file_name_invalid)))
            instrumentation.runOnMainSync { workbench.importFile(id, "", Uri.parse("content://${instrumentation.context.packageName}.import-fixture/slow")) }
            await { withContext(Dispatchers.IO) { root.listFiles()!!.any { it.name.startsWith(".zakocoder-import-") } } }
            instrumentation.runOnMainSync { workbench.cancel() }
            await { !workbench.state.value.busy }
            assertFalse(withContext(Dispatchers.IO) { File(root, "slow.txt").exists() })
            assertFalse(withContext(Dispatchers.IO) { root.listFiles()!!.any { it.name.startsWith(".zakocoder-import-") } })
        } finally {
            withContext(NonCancellable) {
                instrumentation.runOnMainSync { workbench.cancel() }
                await { !workbench.state.value.busy }
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(own); preferences.forEach { base.deleteSharedPreferences(it) } }
            }
        }
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(15000) { while (!condition()) delay(50) }
}

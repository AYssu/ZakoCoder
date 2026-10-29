package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import android.content.ContextWrapper
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class AiDirectoryCheckpointDeviceTest {
    private class Fixture : AutoCloseable {
        val base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "directory-proof-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(base, "project").apply { mkdir() }
        val store = File(base, "checkpoints")
        fun checkpoints() = AiCheckpoints(store) { root }
        fun manifest(key: String) = File(store.listFiles()!!.single(), "$key/manifest.json")
        fun journal() = AiWorkspaceHistory(store.listFiles()!!.single())
        override fun close() {
            root.walkTopDown().filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
            base.deleteRecursively()
        }
    }
    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS))
    private fun chmod(file: File, mode: String) = Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
    @Test(timeout = 60000) fun deletedDirectoryTreeRestoresEmptyDirectoriesBytesAndRestrictiveModes() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val tree = File(f.root, "src/feature").apply { mkdirs() }
            File(tree, "empty").mkdir(); File(tree, "a.py").writeText("original\r\n")
            chmod(tree, "r-x------"); chmod(File(tree, "empty"), "rwxr-x---")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            chmod(tree, "rwx------"); File(f.root, "src").deleteRecursively()
            cp.update("p", key, true)
            val restarted = f.checkpoints()
            assertEquals(4, restarted.changes("p", key).size)
            assertEquals(3, restarted.changes("p", key).count { it.directory })
            assertEquals(4, restarted.restore("p", key) { false })
            assertEquals("original\r\n", File(tree, "a.py").readText())
            assertTrue(File(tree, "empty").isDirectory)
            assertEquals("r-x------", mode(tree)); assertEquals("rwxr-x---", mode(File(tree, "empty")))
            assertTrue(restarted.list("p").single().restored)
        }
    }
    @Test(timeout = 60000) fun renamedTreeRestoresOriginalAndOnlyRemovesRecordedEmptyTargets() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val source = File(f.root, "src/feature").apply { mkdirs() }
            File(source, "nested").mkdir(); File(source, "nested/a.txt").writeText("original")
            File(source, "empty").mkdir(); chmod(source, "r-x------")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            val target = File(f.root, "moved")
            assertTrue(File(f.root, "src").renameTo(target))
            cp.update("p", key, true)
            val restarted = f.checkpoints(); val changes = restarted.changes("p", key)
            assertEquals(10, changes.size)
            assertEquals(10, restarted.restore("p", key) { false })
            assertEquals("original", File(source, "nested/a.txt").readText())
            assertTrue(File(source, "empty").isDirectory); assertFalse(target.exists())
            assertEquals("r-x------", mode(source))
        }
    }
    @Test(timeout = 60000) fun laterNewContentBinaryOrSymlinkPreventsWholeSetRemoval() = runBlocking(Dispatchers.IO) {
        for (kind in listOf("text", "binary", "link")) Fixture().use { f ->
            val a = File(f.root, "a.txt").apply { writeText("before") }
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            a.writeText("after")
            val added = File(f.root, "added").apply { mkdir() }; File(added, "recorded.txt").writeText("recorded")
            cp.update("p", key, true)
            when (kind) {
                "text" -> File(added, "user.txt").writeText("user")
                "binary" -> File(added, "user.bin").writeBytes(byteArrayOf(0, 1, 2))
                else -> Files.createSymbolicLink(File(added, "link").toPath(), a.toPath())
            }
            assertTrue(kind, runCatching { cp.restore("p", key) { false } }.isFailure)
            assertEquals("after", a.readText()); assertEquals("recorded", File(added, "recorded.txt").readText())
            assertTrue(added.listFiles()!!.size == 2); assertTrue(cp.workspaceRestorations("p").isEmpty())
        }
    }
    @Test(timeout = 60000) fun directoryTypeLinkAndModeConflictsAreRejectedBeforeOtherWrites() = runBlocking(Dispatchers.IO) {
        for (kind in listOf("file", "link", "mode")) Fixture().use { f ->
            val source = File(f.root, "src").apply { mkdir() }; File(source, "a.txt").writeText("before")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            source.deleteRecursively(); val added = File(f.root, "added").apply { mkdir() }; chmod(added, "rwxr-xr-x"); File(added, "new.txt").writeText("new")
            cp.update("p", key, true)
            when (kind) {
                "file" -> source.writeText("user replacement")
                "link" -> Files.createSymbolicLink(source.toPath(), added.toPath())
                else -> chmod(added, "rwx------")
            }
            assertTrue(kind, runCatching { cp.restore("p", key) { false } }.isFailure)
            assertEquals("new", File(added, "new.txt").readText())
            assertTrue(cp.workspaceRestorations("p").isEmpty())
        }
    }
    @Test(timeout = 60000) fun stagedDirectoryCreationContinuesAfterRestartAndProtectsChangedMode() = runBlocking(Dispatchers.IO) {
        for (sealed in listOf(false, true)) Fixture().use { f ->
            val source = File(f.root, "src").apply { mkdir() }; File(source, "a.txt").writeText("original"); chmod(source, "r-x------")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            chmod(source, "rwx------"); source.deleteRecursively(); cp.update("p", key, sealed)
            val manifest = JSONObject(f.manifest(key).readText())
            val entries = manifest.getJSONArray("entries"); val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "src" }
            val stage = JSONObject().put("hash", "directory").put("mode", "rwx------")
            manifest.put("directoryStages", JSONObject().put("src", stage)); f.manifest(key).writeText(manifest.toString())
            f.journal().save(f.journal().begin(key, "source", 1, 2, 0).copy(pendingPath = "src", pendingBefore = entry.get("before").toString(), pendingAfter = entry.get("after").toString()))
            source.mkdir(); chmod(source, "rwx------") // mkdir/chmod succeeded, final original permissions and children not yet restored.
            chmod(source, "rwxr-x---")
            val restarted = f.checkpoints()
            val review = if (sealed) null else restarted.reviewInterrupted("p", key)
            assertTrue(runCatching { restarted.restore("p", key, review) { false } }.isFailure)
            assertFalse(File(source, "a.txt").exists())
            chmod(source, "rwx------")
            assertEquals(2, restarted.restore("p", key, review) { false })
            assertEquals("original", File(source, "a.txt").readText()); assertEquals("r-x------", mode(source))
            assertEquals(2, restarted.workspaceRestorations("p").first().confirmed)
        }
    }
    @Test(timeout = 60000) fun unchangedReadOnlyParentIsTemporarilyWritableAndReturnsToOriginalMode() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val source = File(f.root, "src").apply { mkdir() }; val a = File(source, "a.txt").apply { writeText("before") }
            chmod(source, "r-x------")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            chmod(source, "rwx------"); a.writeText("after"); chmod(source, "r-x------"); cp.update("p", key, true)
            assertEquals(listOf("src/a.txt"), cp.changes("p", key).map { it.path })
            assertEquals(2, f.checkpoints().restore("p", key) { false })
            assertEquals("before", a.readText()); assertEquals("r-x------", mode(source))
            assertEquals(2, f.checkpoints().workspaceRestorations("p").first().confirmed)
        }
    }
    @Test(timeout = 60000) fun deletedEmptyDirectoryIntentIsRecognizedWithoutDeletingUserReplacement() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val a = File(f.root, "a.txt").apply { writeText("before") }; val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            a.writeText("after"); val added = File(f.root, "added").apply { mkdir() }; cp.update("p", key, true)
            val entries = JSONObject(f.manifest(key).readText()).getJSONArray("entries")
            val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "added" }
            f.journal().save(f.journal().begin(key, "source", 1, 2, 0).copy(pendingPath = "added", pendingBefore = entry.get("before").toString(), pendingAfter = entry.get("after").toString()))
            added.delete() // rmdir succeeded before progress record.
            added.mkdir()
            val replacement = File(added, "user.txt").apply { writeText("user content") }
            assertTrue(runCatching { f.checkpoints().restore("p", key) { false } }.isFailure)
            assertEquals("after", a.readText()); assertEquals("user content", replacement.readText())
            replacement.delete(); added.delete() // Remove only this fixture's replacement before retrying.
            assertEquals(1, f.checkpoints().restore("p", key) { false })
            assertEquals("before", a.readText()); assertFalse(added.exists())
            assertEquals(2, f.checkpoints().workspaceRestorations("p").first().confirmed)
        }
    }
    @Test(timeout = 60000) fun legacySnapshotDoesNotInventMissingDirectoryHistory() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val source = File(f.root, "src").apply { mkdir() }; File(source, "a.txt").writeText("before")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            val json = JSONObject(f.manifest(key).readText()); val entries = json.getJSONArray("entries")
            json.remove("directorySnapshots")
            json.put("entries", JSONArray((0 until entries.length()).map(entries::getJSONObject).filter { it.getString("path") != "src" }))
            json.put("initial", JSONArray(listOf("src/a.txt"))); f.manifest(key).writeText(json.toString())
            source.deleteRecursively(); cp.update("p", key, true)
            assertTrue(runCatching { cp.restore("p", key) { false } }.isFailure)
            assertFalse(source.exists()); assertTrue(cp.workspaceRestorations("p").isEmpty())
        }
    }
    @Test(timeout = 60000) fun finalDirectoryPermissionsSurviveProgressRecordInterruption() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val source = File(f.root, "src").apply { mkdir() }; val a = File(source, "a.txt").apply { writeText("before") }
            chmod(source, "r-x------")
            val cp = f.checkpoints(); val key = cp.begin("p", "source", 1)
            chmod(source, "rwx------"); a.writeText("after"); cp.update("p", key, true)
            val manifest = JSONObject(f.manifest(key).readText()); val entries = manifest.getJSONArray("entries")
            val entry = (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "src" }
            manifest.put("directoryStages", JSONObject().put("src", JSONObject().put("hash", "directory").put("mode", "rwx------")))
            manifest.put("restoredPaths", JSONArray(listOf("src/a.txt"))); f.manifest(key).writeText(manifest.toString())
            a.writeText("before"); chmod(source, "r-x------") // Final chmod succeeded, but directory progress is still pending.
            f.journal().save(f.journal().begin(key, "source", 1, 2, 1).copy(pendingPath = "src", pendingBefore = entry.get("before").toString(), pendingAfter = entry.get("after").toString()))
            val restarted = f.checkpoints()
            assertEquals(0, restarted.restore("p", key) { false })
            assertEquals("before", a.readText()); assertEquals("r-x------", mode(source))
            val records = restarted.workspaceRestorations("p")
            val record = records.first()
            assertEquals("completed", record.status); assertEquals(2, record.confirmed)
            assertEquals(2, records.size); assertTrue(records.all { it.pendingPath.isEmpty() })
        }
    }
    @Test(timeout = 120000) fun aiGuestRenameAndRestartRestoreOriginalTreeWithoutModelCredentials() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        suspend fun await(block: () -> Boolean) = withTimeout(25000) { while (!block()) delay(40) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }; assertTrue(w.state.value.installed)
            val id = UUID.randomUUID().toString()
            val root = File(w.runtime.workspaces, id)
            AiDeviceTest.Fixture(context).use { f ->
                val owned = object : ContextWrapper(context) {
                    override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                    override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
                }
                var ai: AiController? = null
                fun controller() = AiController(owned, { root }, { _, _ -> false },
                    { project, command -> w.runtime.execute(command, project, w.state.value.settings) }, {}, {}, f.transport, f.config, history = f.history)
                try {
                    withContext(Dispatchers.IO) {
                        check(root.mkdir()); val tree = File(root, "src/feature").apply { mkdirs() }
                        File(tree, "empty").mkdir(); File(tree, "nested").mkdir(); File(tree, "nested/a.py").writeText("print('original')\r\n")
                        chmod(tree, "r-x------")
                    }
                    f.enqueue(calls = listOf(AiCall("rename", "run_command", JSONObject().put("command", "mv src moved").toString())))
                    f.enqueue("目录已移动，工具执行完成。")
                    withContext(Dispatchers.Main) { ai = controller().also { it.select(id) } }
                    await { ai!!.state.value.configured && !ai!!.state.value.permissionsLoading && !ai!!.state.value.skillsLoading }
                    withContext(Dispatchers.Main) { assertTrue(ai!!.send("移动 src 到 moved")) }
                    await { ai!!.state.value.approval != null }
                    withContext(Dispatchers.Main) { ai!!.approve(true) }
                    await { !ai!!.state.value.active }
                    assertEquals("", ai!!.state.value.error)
                    assertEquals(AiToolStatus.Succeeded, ai!!.state.value.events.single().status)
                    assertTrue(File(root, "moved/feature/nested/a.py").exists()); assertFalse(File(root, "src").exists())
                    val cp = ai!!.checkpointList().single()
                    assertTrue(cp.sealed); assertEquals(10, cp.changed)
                    withContext(Dispatchers.Main) { ai = controller().also { it.select(id) } }
                    await { ai!!.state.value.configured && !ai!!.state.value.permissionsLoading && !ai!!.state.value.skillsLoading }
                    withContext(Dispatchers.Main) { ai!!.restoreCheckpoint(cp.id) }
                    await { !ai!!.state.value.restoring }
                    assertEquals("", ai!!.state.value.error)
                    withContext(Dispatchers.IO) {
                        assertEquals("print('original')\r\n", File(root, "src/feature/nested/a.py").readText())
                        assertTrue(File(root, "src/feature/empty").isDirectory); assertFalse(File(root, "moved").exists())
                        assertEquals("r-x------", mode(File(root, "src/feature")))
                        assertTrue(ai!!.checkpointList().single().restored)
                        assertEquals("completed", ai!!.workspaceRestorations().single().status)
                    }
                    assertEquals(2, f.server.requestCount)
                } finally {
                    withContext(Dispatchers.Main) { ai?.stop() }; await { ai?.state?.value?.active != true }
                    withContext(Dispatchers.IO) {
                        root.walkTopDown().filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }.forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
                        root.deleteRecursively()
                    }
                }
            }
        }
    }
}

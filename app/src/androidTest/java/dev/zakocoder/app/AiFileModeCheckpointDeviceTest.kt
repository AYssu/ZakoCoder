package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiCheckpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Real private filesystem metadata, independent stores; no model or user project access. */
class AiFileModeCheckpointDeviceTest {
    private class Fixture : AutoCloseable {
        val base = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "file-mode-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(base, "project").apply { mkdir() }
        val store = File(base, "checkpoints")
        fun checkpoints() = AiCheckpoints(store) { root }
        fun manifest(key: String) = File(store.listFiles()!!.single(), "$key/manifest.json")
        override fun close() { base.deleteRecursively() }
    }
    private fun chmod(file: File, value: String) = Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(value))
    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test(timeout = 60000) fun modesAndBodiesSurviveReloadAndRestoreWithoutInventedText() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val script = File(f.root, "dev.sh").apply { writeBytes("\uFEFF#!/bin/sh\r\necho original\r\n".toByteArray()) }
            val mixed = File(f.root, "mixed.sh").apply { writeText("before\n") }
            val deleted = File(f.root, "deleted.sh").apply { writeText("deleted body\n") }
            for (file in listOf(script, mixed, deleted)) chmod(file, "rw-r--r--")
            val originalBytes = script.readBytes()
            val cp = f.checkpoints(); val key = cp.begin("p", "mode-review", 1)
            chmod(script, "rwxr-xr-x")
            mixed.writeText("after\n"); chmod(mixed, "rwx------")
            assertTrue(deleted.delete())
            val added = File(f.root, "added.sh").apply { writeText("") }; chmod(added, "rw-r-----")
            cp.update("p", key, true)
            val reloaded = f.checkpoints()
            val changes = reloaded.changes("p", key).associateBy { it.path }
            assertEquals(4, changes.size)
            changes.getValue("dev.sh").let {
                assertFalse(it.directory); assertEquals(it.before, it.after)
                assertEquals("#!/bin/sh\r\necho original\r\n", it.after)
                assertEquals("rw-r--r--", it.beforeMode); assertEquals("rwxr-xr-x", it.afterMode)
            }
            changes.getValue("mixed.sh").let {
                assertEquals("before\n", it.before); assertEquals("after\n", it.after)
                assertEquals("rw-r--r--", it.beforeMode); assertEquals("rwx------", it.afterMode)
            }
            changes.getValue("deleted.sh").let { assertEquals("rw-r--r--", it.beforeMode); assertNull(it.afterMode); assertNull(it.after) }
            changes.getValue("added.sh").let { assertNull(it.beforeMode); assertNull(it.before); assertEquals("rw-r-----", it.afterMode); assertEquals("", it.after) }
            assertEquals(4, reloaded.restore("p", key) { false })
            assertArrayEquals(originalBytes, script.readBytes()); assertEquals("rw-r--r--", mode(script))
            assertEquals("before\n", mixed.readText()); assertEquals("rw-r--r--", mode(mixed))
            assertEquals("deleted body\n", deleted.readText()); assertEquals("rw-r--r--", mode(deleted)); assertFalse(added.exists())
        }
    }

    @Test(timeout = 60000) fun laterModeConflictBlocksRestoreAndUnknownModesAreNotPresentedAsKnown() = runBlocking(Dispatchers.IO) {
        Fixture().use { f ->
            val a = File(f.root, "a.sh").apply { writeText("original") }; chmod(a, "rw-r--r--")
            val cp = f.checkpoints(); val key = cp.begin("p", "mode-conflict", 1)
            chmod(a, "rwxr-xr-x"); cp.update("p", key, true)
            chmod(a, "rw-------")
            assertTrue(runCatching { f.checkpoints().restore("p", key) { false } }.isFailure)
            assertEquals("rw-------", mode(a)); assertEquals("original", a.readText())
            val manifest = JSONObject(f.manifest(key).readText())
            val after = manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("after")
            for (invalid in listOf("unknown", "rwxrwxrwxINVALID", "xxxxxxxxx")) {
                after.put("mode", invalid); f.manifest(key).writeText(manifest.toString())
                val change = f.checkpoints().changes("p", key).single()
                assertEquals("rw-r--r--", change.beforeMode); assertNull(change.afterMode)
                assertEquals("original", change.after)
            }
            after.remove("mode"); f.manifest(key).writeText(manifest.toString())
            assertNull(f.checkpoints().changes("p", key).single().afterMode)
        }
    }
}

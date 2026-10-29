package dev.zakocoder.app.projects

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ApkInstallCopyTest {
    @get:Rule val folder = TemporaryFolder()
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid APK to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun copiesImmutableSnapshotWithoutExposingProjectFiles() {
        val root = folder.newFolder("project")
        val source = File(root, "app-debug.apk").apply { writeText("first") }
        val cache = folder.newFolder("cache")
        val snapshot = ApkInstallCopy(cache, 16).prepare(root, source.name)
        source.writeText("second")
        assertEquals("first", snapshot.readText())
        assertEquals(cache.canonicalFile, requireNotNull(snapshot.parentFile).canonicalFile)
        assertTrue(snapshot.name.endsWith(".apk"))
    }

    @Test fun rejectsTraversalDirectoriesLinksAndNonApks() {
        val root = folder.newFolder("project")
        File(root, "valid.apk").writeText("apk")
        File(root, "directory.apk").mkdir()
        val outside = folder.newFolder("outside")
        File(outside, "other.apk").writeText("outside")
        Files.createSymbolicLink(File(root, "leaf.apk").toPath(), File(outside, "other.apk").toPath())
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        val copier = ApkInstallCopy(folder.newFolder("cache"), 16)
        listOf("../outside/other.apk", "/other.apk", "linked/other.apk", "leaf.apk", "directory.apk", "valid.txt", "./valid.apk", "a//valid.apk")
            .forEach { relative -> rejects { copier.prepare(root, relative) } }
    }

    @Test fun cancelledAndOversizedCopiesDoNotLeavePartialFiles() {
        val root = folder.newFolder("project")
        File(root, "large.apk").writeText("12345")
        val cache = folder.newFolder("cache")
        val copier = ApkInstallCopy(cache, 4)
        rejects { copier.prepare(root, "large.apk") }
        File(root, "small.apk").writeText("1234")
        var checks = 0
        try {
            copier.prepare(root, "small.apk") { if (++checks == 2) throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test fun boundsCacheAndRetainsRecentInstallerSnapshots() {
        val root = folder.newFolder("project")
        File(root, "app.apk").writeText("1234")
        val cache = folder.newFolder("cache")
        val copier = ApkInstallCopy(cache, 4)
        val first = copier.prepare(root, "app.apk")
        copier.prepare(root, "app.apk")
        rejects { copier.prepare(root, "app.apk") }
        assertTrue(first.exists())
        assertTrue(first.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000))
        copier.prepare(root, "app.apk")
        assertFalse(first.exists())
        assertEquals(2, cache.listFiles()!!.size)
    }
}

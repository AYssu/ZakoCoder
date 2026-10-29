package dev.zakocoder.app

import dev.zakocoder.app.runtime.StorageUsage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class StorageUsageTest {
    @Test fun unitsSwitchAtOneGigabyte() {
        assertEquals("0.0 MB", StorageUsage.format(0))
        assertEquals("25.5 MB", StorageUsage.format(25_500_000))
        assertEquals("1.0 GB", StorageUsage.format(1_000_000_000))
        assertEquals("2.3 GB", StorageUsage.format(2_300_000_000))
    }
    @Test fun countsOnlyRegularFilesWithoutFollowingLinks(): Unit = runBlocking {
        val root = Files.createTempDirectory("usage-test").toFile()
        val external = Files.createTempDirectory("usage-external").toFile()
        try {
            root.resolve("file").writeBytes(ByteArray(125))
            root.resolve("nested").mkdir()
            root.resolve("nested/data").writeBytes(ByteArray(25))
            external.resolve("excluded").writeBytes(ByteArray(500))
            Files.createSymbolicLink(root.resolve("link").toPath(), external.toPath())
            Files.createSymbolicLink(root.resolve("loop").toPath(), root.toPath())
            assertEquals(150L, StorageUsage.measure(root))
        } finally { root.deleteRecursively(); external.deleteRecursively() }
    }
    @Test(expected = CancellationException::class) fun scanPropagatesCancellation(): Unit = runBlocking {
        val root = Files.createTempDirectory("usage-cancel").toFile()
        try { cancel(); StorageUsage.measure(root) } finally { root.deleteRecursively() }
    }
}

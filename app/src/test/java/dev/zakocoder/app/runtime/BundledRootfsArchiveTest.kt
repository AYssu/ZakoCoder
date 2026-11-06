package dev.zakocoder.app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class BundledRootfsArchiveTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archiveTest(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("bundled-rootfs-test").toFile()
        try { block(File(directory, "rootfs.tar.xz")) } finally { directory.deleteRecursively() }
    }

    @Test fun validArchiveIsPublishedWithMonotonicProgress() = archiveTest { archive ->
        val bytes = ByteArray(180000) { (it % 251).toByte() }
        val progress = mutableListOf<Int>()
        BundledRootfsArchive.copyVerified(ByteArrayInputStream(bytes), archive, bytes.size.toLong(), sha(bytes), "invalid", {}, progress::add)
        assertArrayEquals(bytes, archive.readBytes())
        assertEquals(100, progress.last())
        assertEquals(progress.sorted().distinct(), progress)
        assertFalse(File(archive.parentFile, "bundled.partial").exists())
    }

    @Test fun truncatedOversizedAndWrongDigestNeverReplaceVerifiedCache() = archiveTest { archive ->
        val expected = "verified rootfs".toByteArray()
        for (bytes in listOf(expected.dropLast(1).toByteArray(), expected + byteArrayOf(0), "modified rootfs".toByteArray())) {
            archive.writeText("original")
            try {
                BundledRootfsArchive.copyVerified(ByteArrayInputStream(bytes), archive, expected.size.toLong(), sha(expected), "invalid", {}, {})
                fail("Invalid image was published")
            } catch (error: IllegalStateException) { assertEquals("invalid", error.message) }
            assertEquals("original", archive.readText())
            assertFalse(File(archive.parentFile, "bundled.partial").exists())
        }
    }

    @Test fun cancellationRemovesPartialAndAllowsFreshRetry() = archiveTest { archive ->
        val bytes = ByteArray(180000) { 42 }
        var checks = 0
        try {
            BundledRootfsArchive.copyVerified(ByteArrayInputStream(bytes), archive, bytes.size.toLong(), sha(bytes), "invalid",
                { if (++checks == 2) throw CancellationException("cancel") }, {})
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertFalse(archive.exists())
        assertFalse(File(archive.parentFile, "bundled.partial").exists())
        BundledRootfsArchive.copyVerified(ByteArrayInputStream(bytes), archive, bytes.size.toLong(), sha(bytes), "invalid", {}, {})
        assertArrayEquals(bytes, archive.readBytes())
    }
}

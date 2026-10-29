package dev.zakocoder.app

import dev.zakocoder.app.runtime.ArchiveExtractor
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class ArchiveExtractorTest {
    @Test fun rejectsTraversalAndUnexpectedPrefix() {
        val root = Files.createTempDirectory("zako-root")
        try {
            assertThrows(IllegalArgumentException::class.java) { ArchiveExtractor.entryPath(root, "image/../../outside", "image/") }
            assertThrows(IllegalArgumentException::class.java) { ArchiveExtractor.entryPath(root, "other/file", "image/") }
            assertEquals(root.resolve("usr/bin/bash"), ArchiveExtractor.entryPath(root, "image/usr/bin/bash", "image/"))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rewritesAbsoluteGuestLinksAndPreservesExecutableFiles() {
        val temp = Files.createTempDirectory("zako-extract").toFile()
        try {
            val archive = File(temp, "root.tar.xz")
            TarArchiveOutputStream(XZCompressorOutputStream(archive.outputStream())).use { tar ->
                val data = "hello".toByteArray()
                val file = TarArchiveEntry("image/usr/bin/tool").apply { size = data.size.toLong(); mode = 493 }
                tar.putArchiveEntry(file); tar.write(data); tar.closeArchiveEntry()
                val link = TarArchiveEntry("image/tool", TarConstants.LF_SYMLINK).apply { linkName = "/usr/bin/tool" }
                tar.putArchiveEntry(link); tar.closeArchiveEntry()
            }
            val root = File(temp, "out")
            ArchiveExtractor.extract(archive, root, "image/", {}, {})
            assertEquals("hello", File(root, "tool").readText())
            assertEquals("usr/bin/tool", Files.readSymbolicLink(File(root, "tool").toPath()).toString())
            assertTrue(File(root, "usr/bin/tool").canExecute())
        } finally { temp.deleteRecursively() }
    }
    @Test fun rejectsEscapingLink() {
        val temp = Files.createTempDirectory("zako-links").toFile()
        try {
            val archive = File(temp, "root.tar.xz")
            TarArchiveOutputStream(XZCompressorOutputStream(archive.outputStream())).use { tar ->
                val link = TarArchiveEntry("image/link", TarConstants.LF_SYMLINK).apply { linkName = "../../outside" }
                tar.putArchiveEntry(link); tar.closeArchiveEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { ArchiveExtractor.extract(archive, File(temp, "out"), "image/", {}, {}) }
        } finally { temp.deleteRecursively() }
    }
    @Test fun canonicalAliasAndLinkedParentCannotRedirectOutsideRoot() {
        val temp = Files.createTempDirectory("zako-alias").toFile()
        try {
            val real = File(temp, "real").apply { mkdir() }
            val alias = File(temp, "alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val archive = File(temp, "root.tar.xz")
            TarArchiveOutputStream(XZCompressorOutputStream(archive.outputStream())).use { tar ->
                val a = TarArchiveEntry("image/a", TarConstants.LF_SYMLINK).apply { linkName = "/" }
                tar.putArchiveEntry(a); tar.closeArchiveEntry()
                val b = TarArchiveEntry("image/a/link", TarConstants.LF_SYMLINK).apply { linkName = "../file" }
                tar.putArchiveEntry(b); tar.closeArchiveEntry()
            }
            ArchiveExtractor.extract(archive, File(alias, "out"), "image/", {}, {})
            val root = File(real, "out")
            assertEquals("file", Files.readSymbolicLink(File(root, "link").toPath()).toString())
            assertTrue(Files.isSymbolicLink(File(root, "a").toPath()))
        } finally { dev.zakocoder.app.runtime.PrivateFiles.deleteTree(temp) }
    }
    @Test fun cleanupDoesNotFollowLinks() {
        val temp = Files.createTempDirectory("zako-delete").toFile()
        try {
            val keep = File(temp, "keep").apply { mkdir() }
            File(keep, "proof").writeText("keep")
            val remove = File(temp, "remove").apply { mkdir() }
            Files.createSymbolicLink(File(remove, "link").toPath(), keep.toPath())
            dev.zakocoder.app.runtime.PrivateFiles.deleteTree(remove)
            assertFalse(remove.exists())
            assertEquals("keep", File(keep, "proof").readText())
        } finally { dev.zakocoder.app.runtime.PrivateFiles.deleteTree(temp) }
    }
    @Test fun managedConfigurationReplacesLeafLinksAndRejectsEscapingParents() {
        val temp = Files.createTempDirectory("zako-config").toFile()
        try {
            val root = File(temp, "root").apply { mkdir() }
            val outside = File(temp, "outside").apply { mkdir() }
            val keep = File(outside, "keep").apply { writeText("unchanged") }
            Files.createSymbolicLink(File(root, "leaf").toPath(), keep.toPath())
            dev.zakocoder.app.runtime.PrivateFiles.writeConfined(root, "leaf", "local")
            assertEquals("local", File(root, "leaf").readText())
            assertEquals("unchanged", keep.readText())
            Files.createSymbolicLink(File(root, "redirect").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                dev.zakocoder.app.runtime.PrivateFiles.writeConfined(root, "redirect/keep", "overwrite")
            }
            assertEquals("unchanged", keep.readText())
        } finally { dev.zakocoder.app.runtime.PrivateFiles.deleteTree(temp) }
    }
}

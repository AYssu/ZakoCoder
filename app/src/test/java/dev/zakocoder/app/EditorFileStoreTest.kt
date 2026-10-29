package dev.zakocoder.app

import dev.zakocoder.app.editor.EditorFileException
import dev.zakocoder.app.editor.EditorFileException.Reason
import dev.zakocoder.app.editor.EditorFileStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class EditorFileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = EditorFileStore { folder.root }
    private fun expect(reason: Reason, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: EditorFileException) { assertEquals(reason, e.reason) }
    }
    @Test fun savesBomChineseCrLfAndExecutableModeWithoutTemporaryFiles() {
        val file = folder.newFile("run.sh")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + "echo 中文\r\n".toByteArray())
        file.setExecutable(true)
        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE))
        val store = store(); val original = store.open("p", file.name)
        assertEquals("echo 中文\r\n", original.text)
        val saved = store.save(original, "echo 修改\r\n")
        assertArrayEquals(bom + "echo 修改\r\n".toByteArray(), file.readBytes())
        assertEquals(permissions, Files.getPosixFilePermissions(file.toPath()))
        assertEquals(saved, store.open("p", file.name))
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }
    @Test fun conflictChecksContentEvenWithIdenticalLengthAndTimestamp() {
        val file = folder.newFile("a.txt"); file.writeText("BASE")
        val store = store(); val baseline = store.open("p", file.name); val time = file.lastModified()
        file.writeText("USER"); file.setLastModified(time)
        expect(Reason.CONFLICT) { store.save(baseline, "AI") }
        assertEquals("USER", file.readText())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }
    @Test fun rejectsDeletedFileWithoutRecreatingIt() {
        val file = folder.newFile("a.txt"); file.writeText("BASE")
        val store = store(); val baseline = store.open("p", file.name); file.delete()
        expect(Reason.PATH) { store.save(baseline, "NEW") }
        assertFalse(file.exists())
    }
    @Test fun rejectsTraversalAndInternalAndExternalSymlinks() {
        val file = folder.newFile("a.txt"); file.writeText("BASE")
        val store = store()
        for (path in listOf("../a.txt", "/a.txt", "./a.txt", "a.txt/../a.txt", "a\\b", "")) expect(Reason.PATH) { store.open("p", path) }
        Files.createSymbolicLink(File(folder.root, "link").toPath(), file.toPath())
        expect(Reason.PATH) { store.open("p", "link") }
        val dir = folder.newFolder("dir"); File(dir, "inside").writeText("KEEP")
        Files.createSymbolicLink(File(folder.root, "linked-dir").toPath(), dir.toPath())
        expect(Reason.PATH) { store.open("p", "linked-dir/inside") }
    }
    @Test fun replacedTargetSymlinkCannotOverwriteLinkedFile() {
        val file = folder.newFile("a.txt"); file.writeText("BASE")
        val other = folder.newFile("other.txt"); other.writeText("KEEP")
        val store = store(); val baseline = store.open("p", file.name)
        file.delete(); Files.createSymbolicLink(file.toPath(), other.toPath())
        expect(Reason.PATH) { store.save(baseline, "NEW") }
        assertEquals("KEEP", other.readText())
    }
    @Test fun refusesInvalidUtf8AndNulWithoutLossyDecoding() {
        val file = folder.newFile("a.bin"); val store = store()
        for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0, 1, 2))) {
            file.writeBytes(bytes)
            expect(Reason.UNSUPPORTED) { store.open("p", file.name) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun refusesOversizeAndOverlongLinesAndCrOnlyLineOverflow() {
        val file = folder.newFile("a.txt"); val store = store()
        for (text in listOf("x".repeat(EditorFileStore.MAX_BYTES + 1), "x".repeat(EditorFileStore.MAX_COLUMN + 1), "\n".repeat(EditorFileStore.MAX_LINES), "\r".repeat(EditorFileStore.MAX_LINES))) {
            file.writeText(text); expect(Reason.LIMIT) { store.open("p", file.name) }
        }
    }
    @Test fun refusesInvalidSaveAndKeepsOriginal() {
        val file = folder.newFile("a.txt"); file.writeText("KEEP")
        val store = store(); val baseline = store.open("p", file.name)
        expect(Reason.UNSUPPORTED) { store.save(baseline, "bad\u0000") }
        expect(Reason.UNSUPPORTED) { store.save(baseline, "\uD800") }
        expect(Reason.LIMIT) { store.save(baseline, "a".repeat(EditorFileStore.MAX_COLUMN + 1)) }
        assertEquals("KEEP", file.readText())
    }
    @Test fun supportsEmptyNestedAndNoFinalNewline() {
        folder.newFolder("src"); val file = File(folder.root, "src/new.py"); file.writeText("")
        val store = store(); val baseline = store.open("p", "src/new.py")
        val saved = store.save(baseline, "print('中文')")
        assertEquals("print('中文')", file.readText())
        assertEquals(saved, store.open("p", "src/new.py"))
    }
}

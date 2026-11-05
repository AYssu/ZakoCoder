package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import dev.zakocoder.app.editor.EditorFileException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiTextEditsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun edit(old: String, new: String) = AiTextEdit(old, new)
    private fun rejects(reason: AiFileFailure.Reason, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: AiFileFailure) { assertEquals(reason, error.reason) }
    }

    @Test fun appliesUnorderedEditsAgainstOriginalInsteadOfInsertedText() {
        val original = "first α\r\nsecond β\r\nlast γ\r\n"
        assertEquals("first β\r\nsecond α\r\nlast\r\n", AiTextEdits.apply(original,
            listOf(edit(" γ", ""), edit("β", "α"), edit("α", "β"))))
        assertEquals("AB", AiTextEdits.apply("ab", listOf(edit("b", "B"), edit("a", "A"))))
    }

    @Test fun rejectsMissingRepeatedAndOverlappingMatchesWithoutPartialSave() {
        val files = AiProjectFiles { folder.root }
        val file = folder.newFile("main.txt").apply { writeText("abcdef\nrepeat repeat\naaa") }
        val before = files.read("p", "main.txt")
        val cases = listOf(
            AiFileFailure.Reason.EDIT_NOT_FOUND to listOf(edit("abcdef", "ok"), edit("missing", "x")),
            AiFileFailure.Reason.EDIT_AMBIGUOUS to listOf(edit("abcdef", "ok"), edit("repeat", "x")),
            AiFileFailure.Reason.EDIT_AMBIGUOUS to listOf(edit("aa", "x")),
            AiFileFailure.Reason.EDIT_OVERLAP to listOf(edit("abc", "x"), edit("cde", "y")),
            AiFileFailure.Reason.EDIT_OVERLAP to listOf(edit("abcdef", "x"), edit("bc", "y")),
            AiFileFailure.Reason.EDIT_OVERLAP to listOf(edit("abcdef", "x"), edit("abcdef", "y")))
        cases.forEach { (reason, edits) ->
            rejects(reason) { files.write("p", "main.txt", before, files.prepareEdit(before, edits)) }
            assertEquals(before.text, file.readText())
        }
        assertEquals(listOf("main.txt"), folder.root.list()!!.toList())
    }

    @Test fun rejectsEmptyNoOpOversizedAndInvalidUtf8Edits() {
        rejects(AiFileFailure.Reason.INVALID_EDITS) { AiTextEdits.apply("a", emptyList()) }
        rejects(AiFileFailure.Reason.INVALID_EDITS) { AiTextEdits.apply("a", List(65) { edit("a", "b") }) }
        rejects(AiFileFailure.Reason.INVALID_EDITS) { AiTextEdits.apply("a", listOf(edit("", "b"))) }
        rejects(AiFileFailure.Reason.INVALID_EDITS) { AiTextEdits.apply("a", listOf(edit("a", "a"))) }
        val files = AiProjectFiles { folder.root }
        folder.newFile("main.txt").writeText("before")
        val before = files.read("p", "main.txt")
        rejects(AiFileFailure.Reason.FILE_UNSUPPORTED) { files.prepareEdit(before, listOf(edit("before", "a\u0000b"))) }
        rejects(AiFileFailure.Reason.FILE_LIMIT) { files.prepareEdit(before, listOf(edit("before", "x".repeat(20001)))) }
        rejects(AiFileFailure.Reason.FILE_LIMIT) { files.prepareEdit(before, listOf(edit("before", "x".repeat(1024 * 1024 + 1)))) }
        rejects(AiFileFailure.Reason.FILE_LIMIT) { files.prepareEdit(before, listOf(edit("before", ("中".repeat(300) + "\n").repeat(1200)))) }
        try { files.write("p", "main.txt", before, files.prepareEdit(before, listOf(edit("before", "\uD800")))); fail("Invalid UTF-8") }
        catch (error: EditorFileException) { assertEquals(EditorFileException.Reason.UNSUPPORTED, error.reason) }
        assertEquals("before", File(folder.root, "main.txt").readText())
    }

    @Test fun detectsUserChangeAfterPreviewAndRetainsBomAndPermissions() {
        val file = folder.newFile("main.txt")
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\nb\n".toByteArray())
        val files = AiProjectFiles { folder.root }
        val baseline = files.read("p", "main.txt")
        val proposed = files.prepareEdit(baseline, listOf(edit("a", "A"), edit("b", "B")))
        file.writeText("user changes\n")
        try { files.write("p", "main.txt", baseline, proposed); fail("Expected stale baseline") }
        catch (error: EditorFileException) { assertEquals(EditorFileException.Reason.CONFLICT, error.reason) }
        assertEquals("user changes\n", file.readText())
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\nb\n".toByteArray())
        val permissions = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        val saved = files.write("p", "main.txt", baseline, proposed)
        assertTrue(saved.bom); assertEquals("A\nB\n", saved.text)
        assertEquals(permissions, java.nio.file.Files.getPosixFilePermissions(file.toPath()))
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), file.readBytes().take(3).toByteArray())
    }

    @Test fun editPermissionInheritsExistingWriteRestrictionUnlessExplicitlyConfigured() {
        assertEquals(AiPermission.Ask, AiPolicy().permission(AiTool.EditFile))
        assertEquals(AiPermission.Deny, AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Deny)).permission(AiTool.EditFile))
        assertEquals(AiPermission.Allow, AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Allow)).permission(AiTool.EditFile))
        assertEquals(R.string.ai_edit_inherited_permission, AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Deny)).source(AiTool.EditFile))
        assertEquals(AiPermission.Ask, AiPolicy(overrides = mapOf(AiTool.WriteFile to AiPermission.Deny, AiTool.EditFile to AiPermission.Ask)).permission(AiTool.EditFile))
    }
}

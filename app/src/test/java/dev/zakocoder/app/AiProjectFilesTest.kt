package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AiProjectFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun files() = AiProjectFiles { folder.root }
    private fun denied(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
    @Test fun rejectsTraversalSecretsAndEverySymlinkSegment() {
        val files = files()
        listOf("../other", "/etc/passwd", "a//b", "a/./b", "a\\b", ".env", ".env.local", ".ssh/id_rsa", "credentials.json", "a/key.pem", ".git/config", "local.properties").forEach { denied { files.resolve("p", it) } }
        val outside = folder.newFolder("outside"); File(outside, "text").writeText("secret")
        Files.createSymbolicLink(File(folder.root, "link").toPath(), outside.toPath())
        denied { files.resolve("p", "link/text") }
    }
    @Test fun writesOnlyAgainstReadBaselineAndPreservesLaterManualEdit() {
        val file = folder.newFile("a.txt").apply { writeText("before\n") }; val files = files()
        val before = files.read("p", "a.txt")
        val after = files.write("p", "a.txt", before, "after\n")
        assertEquals("after\n", file.readText()); assertNotEquals(before.digest, after.digest)
        file.writeText("manual\n")
        try { files.write("p", "a.txt", after, "overwrite"); fail("Conflict expected") } catch (_: dev.zakocoder.app.editor.EditorFileException) { }
        assertEquals("manual\n", file.readText())
        denied { files.write("p", "a.txt", null, "overwrite") }
    }
    @Test fun createsNewFileWithoutOverwriteOrInvalidPartialFile() {
        val files = files(); files.write("p", "new.py", null, "print('中文')\n")
        assertEquals("print('中文')\n", File(folder.root, "new.py").readText())
        denied { files.write("p", "invalid", null, "x".repeat(20001)) }
        assertFalse(File(folder.root, "invalid").exists())
        assertFalse(folder.root.list()!!.any { it.startsWith(".zakocoder-ai-") })
    }
    @Test fun largeUtf8FilesUseEditorByteLimitAndNeverLeaveInvalidNewFiles() {
        val files = files()
        val text = "中文 code line\n".repeat(12000)
        assertTrue(text.length > 128000)
        val baseline = files.write("p", "large.py", null, text)
        assertEquals(text, files.read("p", "large.py").text)
        val edited = text.replaceFirst("code", "edited")
        assertEquals(edited, files.write("p", "large.py", baseline, edited).text)
        val tooBig = "中文".repeat(200) + "\n"
        denied { files.write("p", "oversized.py", null, tooBig.repeat(1000)) }
        assertFalse(File(folder.root, "oversized.py").exists())
        assertFalse(folder.root.list()!!.any { it.startsWith(".zakocoder-ai-") })
        assertEquals(edited, File(folder.root, "large.py").readText())
    }
    @Test fun acceptsExactlyOneMiBAndRejectsOneMoreByteBeforeCreatingFile() {
        val files = files()
        val line = "x".repeat(1023) + "\n"
        val text = line.repeat(1024)
        assertEquals(1024 * 1024, files.write("p", "boundary.txt", null, text).text.length)
        denied { files.write("p", "over.txt", null, text + "x") }
        assertFalse(File(folder.root, "over.txt").exists())
    }
    @Test fun listingAndLiteralSearchExcludeSecretsAndCache() {
        folder.newFile(".env").writeText("needle secret")
        folder.newFile("a.py").writeText("needle line\nother")
        File(folder.newFolder("node_modules"), "dep.js").writeText("needle vendor")
        val files = files()
        assertFalse(files.list("p", "").contains(".env"))
        assertEquals("a.py:1:needle line", files.search("p", "needle"))
    }
    @Test fun diffShowsEveryChangedLineWithContext() {
        val diff = fileDiff("a\nb\nc\nd", "a\nB\nC\nd")
        assertEquals("@@ -1,4 +1,4 @@\n a\n-b\n-c\n+B\n+C\n d\n", diff)
    }
    @Test fun endpointRequiresHttpsAndRejectsCredentialAndQueryUrls() {
        assertEquals("https://example.org/v1", AiConfigStore.endpoint(" https://example.org/v1/chat/completions/ "))
        listOf("http://example.org", "https://key@example.org", "https://example.org?k=x", "https://example.org#key", "https:///v1").forEach { denied { AiConfigStore.endpoint(it) } }
    }
}

package dev.zakocoder.app.projects

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Pure JVM coverage for [ProjectTemplateWriter]: what each scaffold produces on disk, which paths
 * are rejected, the file-count/size limits, and the all-or-nothing rollback contract.
 */
class ProjectTemplateWriterTest {
    private lateinit var sandbox: File
    private lateinit var root: File

    @Before fun setUp() {
        sandbox = Files.createTempDirectory("project-template-writer-").toFile()
        root = File(sandbox, "project")
        assertTrue("could not create test project root", root.mkdirs())
    }

    @After fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun write(files: Map<String, String>) = ProjectTemplateWriter.write(root, files)

    private fun resetRoot() {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
    }

    private fun text(path: String) = File(root, path).readText(StandardCharsets.UTF_8)

    @Test fun webTemplateWritesEveryFileAsUtf8() {
        write(ProjectTemplates.files(ProjectTemplate.Web, "Demo Web"))

        assertEquals(setOf("index.html", "styles.css", "app.js", "README.md"), root.list()!!.toSet())
        assertTrue(File(root, "index.html").isFile)
        assertEquals(ProjectTemplates.files(ProjectTemplate.Web, "Demo Web").getValue("index.html"), text("index.html"))
        assertEquals(ProjectTemplates.files(ProjectTemplate.Web, "Demo Web").getValue("styles.css"), text("styles.css"))
        assertEquals(ProjectTemplates.files(ProjectTemplate.Web, "Demo Web").getValue("app.js"), text("app.js"))
        assertTrue("README should keep non-ASCII text intact", text("README.md").contains("由 ZakoCoder 新建的静态网页项目"))
    }

    @Test fun nodeTemplateWritesDotFiles() {
        write(ProjectTemplates.files(ProjectTemplate.Node, "My Node"))

        assertEquals(setOf("package.json", "index.js", "README.md", ".gitignore"), root.list()!!.toSet())
        assertTrue(File(root, ".gitignore").isFile)
        assertTrue(text("package.json").contains("\"name\": \"my-node\""))
        assertTrue(text("index.js").contains("My Node"))
        assertTrue(text("README.md").startsWith("# My Node"))
    }

    @Test fun pythonTemplateWritesEntryAndManifest() {
        write(ProjectTemplates.files(ProjectTemplate.Python, "My Python"))

        assertEquals(setOf("main.py", "requirements.txt", "README.md", ".gitignore"), root.list()!!.toSet())
        assertTrue(text("main.py").contains("PROJECT_IDENTIFIER = \"my-python\""))
        assertTrue(File(root, "requirements.txt").isFile)
        assertTrue(File(root, ".gitignore").isFile)
    }

    @Test fun otherTemplateWritesNothing() {
        write(ProjectTemplates.files(ProjectTemplate.Other, "Empty"))

        assertTrue(root.isDirectory)
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test fun exactlyAtTheLimitsIsAccepted() {
        val files = LinkedHashMap<String, String>()
        for (index in 1..ProjectTemplates.MAX_FILES) files["file$index.txt"] = "x"
        files["file1.txt"] = "a".repeat(ProjectTemplates.MAX_FILE_BYTES)

        write(files)

        assertEquals(ProjectTemplates.MAX_FILES, root.list()!!.size)
        assertEquals(ProjectTemplates.MAX_FILE_BYTES.toLong(), File(root, "file1.txt").length())
    }

    @Test fun tooManyFilesIsRejectedAndRolledBack() {
        val files = LinkedHashMap<String, String>()
        for (index in 1..ProjectTemplates.MAX_FILES + 1) files["file$index.txt"] = "x"

        assertThrows(Exception::class.java) { write(files) }
        assertFalse("root must be removed after a rejected write", root.exists())
    }

    @Test fun oversizedFileIsRejectedAndRolledBack() {
        assertThrows(Exception::class.java) {
            write(mapOf("big.txt" to "a".repeat(ProjectTemplates.MAX_FILE_BYTES + 1)))
        }
        assertFalse("root must be removed after a rejected write", root.exists())
    }

    @Test fun pathAttacksAreRejectedAndRolledBack() {
        val attacks = listOf(
            "/etc/passwd",
            "..",
            "../outside.txt",
            "a/../../outside.txt",
            "a\\b.txt",
            "C:/windows.txt",
            "a//b.txt",
            "a/./b.txt",
            "a/\u0000b.txt",
            "a/\u0001b.txt",
            "",
            "a/",
        )
        attacks.forEachIndexed { index, path ->
            resetRoot()
            assertThrows("should reject: $path", Exception::class.java) { write(mapOf(path to "x")) }
            assertFalse("root must be removed after rejecting '$path'", root.exists())
        }
        // Each attack starts from a fresh root, so the sandbox itself is untouched.
        assertTrue(sandbox.isDirectory)
        assertTrue("no file may escape the sandbox", sandbox.list()!!.none { it == "outside.txt" })
    }

    @Test fun writeFailureRollsBackTheEntireRoot() {
        // A regular file where a directory is needed makes Files.createDirectories fail mid-write.
        File(root, "blocker").writeText("occupied", StandardCharsets.UTF_8)
        File(root, "already-written.txt").writeText("partial", StandardCharsets.UTF_8)

        assertThrows(Exception::class.java) { write(mapOf("blocker/nested.txt" to "nested")) }

        assertFalse("rollback must remove the whole project root, not just the failed file", root.exists())
    }

    @Test fun symlinkedDirectoryEscapeIsRejectedAndRolledBack() {
        val outside = File(sandbox, "outside")
        assertTrue(outside.mkdirs())
        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        } catch (unavailable: Exception) {
            Assume.assumeNoException("symlinks are unavailable on this platform", unavailable)
        }

        assertThrows(Exception::class.java) { write(mapOf("link/pwned.txt" to "x")) }

        assertFalse(root.exists())
        assertFalse("write must not escape the project root through a symlink", File(outside, "pwned.txt").exists())
    }
}

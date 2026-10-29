package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.DirectoryStream
import java.nio.file.DirectoryIteratorException
import java.io.IOException

class AiProjectIndexTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun put(path: String, text: String = "needle") = File(temporary.root, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun index() = AiProjectIndex(AiProjectFiles { temporary.root }, "p")
    @Test fun scopedSearchFindsLargeFilesAndSkipsSecretsDependenciesAndLinks() {
        put("src/view.ts", "line one\nNeedle found\nlast")
        put("src/large.ts", "long line\n".repeat(10000) + "needle tail")
        put("src/.env.local", "needle secret")
        put("node_modules/dependency.js")
        put("dist/bundle.js")
        Files.createSymbolicLink(File(temporary.root, "src/linked.ts").toPath(), put("outside.txt").toPath())
        val result = index().search("needle", "src", "**/*.ts", caseSensitive = false)
        assertEquals(setOf("src/view.ts", "src/large.ts"), result.matches.map { it.path }.toSet())
        assertEquals(2, result.matches.single { it.path == "src/view.ts" }.line)
        assertEquals(10001, result.matches.single { it.path == "src/large.ts" }.line)
        assertFalse(result.truncated)
        assertEquals(listOf("src/large.ts", "src/view.ts"), index().search("", "src", "*.ts", filenames = true).matches.map { it.path })
    }
    @Test fun truncationIsExplicitAndListingsAreStable() {
        put("z.txt"); put("a.txt"); put("src/b.txt"); put(".npmrc", "credential")
        val first = index().list(depth = 2, limit = 2)
        assertEquals(listOf("src/", "src/b.txt"), first.paths)
        assertTrue(first.truncated)
        val search = index().search("needle", limit = 1)
        assertEquals(1, search.matches.size); assertTrue(search.truncated)
        assertFalse(index().list(depth = 2).paths.contains(".npmrc"))
    }
    @Test fun lineRangesAreNumberedAndBoundedWithoutChangingContent() {
        val text = "first\nsecond\nthird\n"
        val range = fileLineRange(text, 2, 3)
        assertTrue(range.contains("2: second\n3: third")); assertFalse(range.contains("1: first"))
        assertTrue(fileLineRange("x".repeat(20000) + "\n".repeat(1000), 1, 1000).length < 65000)
        try { fileLineRange(text, 0, 1); fail() } catch (_: IllegalArgumentException) { }
        try { fileLineRange(text, 1, 1001); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(text, "first\nsecond\nthird\n")
    }
    @Test fun pathsGlobsAndCancellationCannotEscapeTheirScope() {
        put("a.kt")
        try { index().search("needle", "../"); fail() } catch (_: IllegalArgumentException) { }
        try { index().search("needle", glob = "../**"); fail() } catch (_: IllegalArgumentException) { }
        val job = Job().apply { cancel() }
        try { AiProjectIndex(AiProjectFiles { temporary.root }, "p", job).search("needle"); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(AiProjectIndex.globPattern("**/*.kt").matches("a.kt"))
        assertTrue(AiProjectIndex.globPattern("**/*.kt").matches("src/a.kt"))
        assertFalse(AiProjectIndex.globPattern("*.kt").matches("src/a.kt"))
    }
    @Test fun globMatrixPreservesRecursiveSingleSegmentLiteralAndUnicodeSemantics() {
        val examples = listOf(
            Triple("**/*.kt", "a.kt", true), Triple("**/*.kt", "a/b/c.kt", true),
            Triple("**/*.kt", "a/b/c.java", false), Triple("a/**/b", "a/b", true),
            Triple("a/**/b", "a/x/y/b", true), Triple("a/**/b", "a/xb", false),
            Triple("**", "a/b/c", true), Triple("*", "a/b", false),
            Triple("a?c", "abc", true), Triple("a?c", "a/c", false),
            Triple("a?c", "ac", false), Triple("[x].(kt)+", "[x].(kt)+", true),
            Triple("[x].(kt)+", "x.kt", false), Triple("*.kt", "你好.kt", true),
            Triple("?.kt", "😀.kt", true), Triple("??.kt", "😀.kt", false),
            Triple("😀?.kt", "😀好.kt", true), Triple("😀?.kt", "😀.kt", false),
            Triple("**/needle", "needle", true), Triple("**/needle", "a/needle", true),
            Triple("**/needle", "aneedle", false), Triple("***", "a/b", true),
            Triple("**", "a\u2028b", false), Triple("*", "a\u2028b", true),
            Triple("**/b", "a\u2028/b", false), Triple("**/b", "a/\u2028/b", false),
        )
        for ((glob, path, expected) in examples) {
            assertEquals("$glob matching $path", expected, AiProjectIndex.globPattern(glob).matches(path))
        }
    }

    @Test(timeout = 2000) fun adversarialRecursiveWildcardsFinishWithoutBacktracking() {
        val pattern = AiProjectIndex.globPattern("**/".repeat(60) + "needle")
        repeat(50) {
            assertFalse(pattern.matches("a/".repeat(500) + "other.kt"))
        }
        assertTrue(pattern.matches("a/".repeat(500) + "needle"))
    }

    @Test fun globChecksCancellationDuringMatchingRatherThanOnlyBeforeIt() {
        val job = Job()
        var checks = 0
        val pattern = AiProjectIndex.GlobPattern("**/".repeat(60) + "needle") {
            if (++checks == 2000) job.cancel()
            job.ensureActive()
        }
        try { pattern.matches("a/".repeat(500) + "other.kt"); fail("Expected cancellation within matching") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(2000, checks)
    }

    private class CountingDirectory(
        private val directory: File,
        private val count: Int,
        private val cancelAfter: Int? = null,
        private val job: kotlinx.coroutines.CompletableJob? = null,
        private val failAfter: Int? = null,
    ) : DirectoryStream<Path> {
        var consumed = 0
        var probes = 0
        var closed = false
        override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
            override fun hasNext(): Boolean {
                probes++
                if (consumed == failAfter) throw DirectoryIteratorException(IOException("fixture read failure"))
                return consumed < count
            }
            override fun next(): Path {
                consumed++
                if (consumed == cancelAfter) job!!.cancel()
                // Names need not exist: filename search must not read bodies.
                return File(directory, "entry-${consumed.toString().padStart(6, '0')}.kt").toPath()
            }
            override fun remove(): Unit = throw UnsupportedOperationException()
        }
        override fun close() { closed = true }
    }

    @Test fun veryWideDirectorySamplingIsBoundedClosedAndExplicitlyIncomplete() {
        for (search in listOf(false, true)) {
            val stream = CountingDirectory(temporary.root, Int.MAX_VALUE)
            val index = AiProjectIndex(AiProjectFiles { temporary.root }, "p", directoryEntries = { stream })
            if (search) {
                val result = index.search("no-match", filenames = true)
                assertTrue(result.matches.isEmpty()); assertTrue(result.truncated)
                assertEquals(AiProjectIndex.MAX_VISITED, result.visited)
            } else {
                val result = index.list(limit = 500)
                assertEquals(500, result.paths.size); assertTrue(result.truncated)
            }
            assertEquals(AiProjectIndex.MAX_VISITED, stream.consumed)
            assertTrue(stream.probes <= AiProjectIndex.MAX_VISITED + 1)
            assertTrue(stream.closed)
        }
    }

    @Test fun enumerationBudgetIsSharedAcrossDirectoriesNotResetPerDirectory() {
        File(temporary.root, "child").mkdir()
        val child = CountingDirectory(File(temporary.root, "child"), Int.MAX_VALUE)
        var rootClosed = false
        val root = object : DirectoryStream<Path> {
            override fun iterator() = mutableListOf(File(temporary.root, "child").toPath()).iterator()
            override fun close() { rootClosed = true }
        }
        val index = AiProjectIndex(AiProjectFiles { temporary.root }, "p", directoryEntries = {
            if (it == temporary.root) root else child
        })
        assertTrue(index.search("no-match", filenames = true).truncated)
        assertEquals(AiProjectIndex.MAX_VISITED - 1, child.consumed)
        assertTrue(rootClosed && child.closed)
    }

    @Test fun cancellationDuringEnumerationClosesStreamAndDoesNotReturnPartialSuccess() {
        for (search in listOf(false, true)) {
            val job = Job()
            val stream = CountingDirectory(temporary.root, Int.MAX_VALUE, cancelAfter = 37, job = job)
            val index = AiProjectIndex(AiProjectFiles { temporary.root }, "p", job, directoryEntries = { stream })
            try {
                if (search) index.search("no-match", filenames = true) else index.list()
                fail("Expected cancellation during enumeration")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(37, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    @Test fun directoryOpenIterationAndCloseFailuresAreNeverReportedAsCompleteEmptyResults() {
        val files = AiProjectFiles { temporary.root }
        val denied = AiProjectIndex(files, "p", directoryEntries = { throw IOException("fixture open failure") })
        assertTrue(denied.list().truncated)
        assertTrue(denied.search("no-match", filenames = true).truncated)
        for (failAfter in listOf(0, 3)) {
            val stream = CountingDirectory(temporary.root, 10, failAfter = failAfter)
            val index = AiProjectIndex(files, "p", directoryEntries = { stream })
            val result = index.list()
            assertTrue(result.truncated)
            assertEquals(failAfter, result.paths.size)
            assertTrue(stream.closed)
        }
        val badClose = AiProjectIndex(files, "p", directoryEntries = {
            object : DirectoryStream<Path> {
                override fun iterator() = mutableListOf<Path>().iterator()
                override fun close(): Unit = throw IOException("fixture close failure")
            }
        })
        assertTrue(badClose.list().truncated)
    }

    @Test fun smallDirectoriesRetainDirectoryFirstListingAndLexicalSearchOrder() {
        put("z.kt"); put("b.kt"); put("a/z.kt"); put("a/b.kt")
        assertEquals(listOf("a/", "a/b.kt", "a/z.kt", "b.kt", "z.kt"), index().list().paths)
        assertEquals(listOf("a/b.kt", "a/z.kt", "b.kt", "z.kt"), index().search("needle").matches.map { it.path })
        assertFalse(index().list().truncated)
        assertFalse(index().search("needle").truncated)
    }

}

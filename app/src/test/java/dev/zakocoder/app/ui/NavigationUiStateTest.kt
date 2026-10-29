package dev.zakocoder.app.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NavigationUiStateTest {
    @Test fun directoryIdentityStartsEmptyAndLateCompletionCannotPopulateItsReplacement() = runBlocking {
        val old = DirectoryListing<String>()
        old.load { listOf("old/file") }
        val next = DirectoryListing<String>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { next.load { gate.await(); listOf("next/file") } }
        assertTrue(next.loading)
        assertTrue(next.entries.isEmpty())
        assertEquals(listOf("old/file"), old.entries)
        val replacement = DirectoryListing<String>()
        replacement.load { listOf("replacement/file") }
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("replacement/file"), replacement.entries)
        assertEquals(listOf("next/file"), next.entries)
    }

    @Test fun cancelledNonCooperativeLoaderDoesNotPublishEntriesOrFailure() = runBlocking {
        for (fail in listOf(false, true)) {
            val listing = DirectoryListing<String>()
            val gate = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                listing.load {
                    withContext(NonCancellable) {
                        gate.await()
                        if (fail) error("late failure")
                        listOf("late/file")
                    }
                }
            }
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertTrue(listing.entries.isEmpty())
            assertNull(listing.failure)
            assertTrue(job.isCancelled)
        }
    }

    @Test fun failedDirectoryHasNoOldClickableEntries() = runBlocking {
        val listing = DirectoryListing<String>()
        listing.load { error("unavailable") }
        assertFalse(listing.loading)
        assertTrue(listing.entries.isEmpty())
        assertEquals("unavailable", listing.failure?.message)
    }

    @Test fun tabsUseShortestDistinguishableSuffixIncludingRootAndChinesePaths() {
        assertEquals(mapOf("src/index.ts" to "src/index.ts", "test/index.ts" to "test/index.ts", "README.md" to "README.md"),
            distinguishEditorPaths(listOf("src/index.ts", "test/index.ts", "README.md")))
        assertEquals(mapOf("app/src/main.kt" to "app/src/main.kt", "lib/src/main.kt" to "lib/src/main.kt"),
            distinguishEditorPaths(listOf("app/src/main.kt", "lib/src/main.kt")))
        assertEquals(mapOf("main.py" to "main.py", "工具/main.py" to "工具/main.py"),
            distinguishEditorPaths(listOf("main.py", "工具/main.py", "main.py")))
        assertTrue(distinguishEditorPaths(emptyList()).isEmpty())
    }

    @Test fun commandSummaryBoundsLinesAndCharactersWithoutChangingOriginal() {
        val script = (1..100).joinToString("\n") { "command-$it" }
        assertEquals("command-1\ncommand-2\ncommand-3\ncommand-4", commandScriptPreview(script))
        assertEquals(100, script.lineSequence().count())
        assertEquals(1600, commandScriptPreview("x".repeat(16000)).length)
        assertEquals("short", commandScriptPreview("short"))
        assertEquals("a\nb", commandScriptPreview("a\r\nb"))
    }
}

package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.system.Os
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiFileAttachmentException
import dev.zakocoder.app.ai.AiFileAttachmentStore
import dev.zakocoder.app.ai.AiFileAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class AiFileAttachmentStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fullOriginalLargerThanOldLimitSurvivesReconstructionAndTailReadAndSearch() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val content = "row\n".repeat(300_000) + "tail unique-archive-needle 你好😀\n"
            val source = File(sourceRoot, "source.kt").apply { writeText(content) }
            val original = source.readBytes()
            val progress = mutableListOf<Long>()
            val attachment = store.import(uri(source)) { copied, _ -> progress += copied }
            assertTrue(attachment.bytes > 1024 * 1024)
            assertEquals(source.length(), attachment.bytes)
            assertEquals(hash(original), attachment.sha256)
            assertEquals("source.kt", attachment.displayName)
            assertTrue(original.contentEquals(File(archive, "${attachment.id}.bin").readBytes()))
            assertTrue(original.contentEquals(source.readBytes()))
            assertEquals(0L, progress.first())
            assertEquals(attachment.bytes, progress.last())
            assertTrue(progress.zipWithNext().all { it.first <= it.second })
            val reconstructed = AiFileAttachmentStore(StoreContext(context, archive.parentFile!!))
            val tail = reconstructed.read(attachment, content.indexOf("tail"), 100)
            assertEquals(content.substring(content.indexOf("tail")), tail.getString("content"))
            assertFalse(tail.getBoolean("has_more"))
            val search = reconstructed.search(attachment, "unique-archive-needle")
            val match = search.getJSONArray("matches").getJSONObject(0)
            assertEquals(300_001, match.getInt("line"))
            assertTrue(match.getString("text").contains("unique-archive-needle"))
        }
    }

    @Test fun paginationKeepsSurrogatePairsAndNeverLoadsTheEntireFileIntoAResponse() = runBlocking {
        fixture { store, _, sourceRoot ->
            val text = "x".repeat(500) + "😀".repeat(10_000) + "tail"
            val attachment = store.import(uri(File(sourceRoot, "unicode.txt").apply { writeText(text) }))
            val first = store.read(attachment, limit = 501)
            assertEquals("x".repeat(500), first.getString("content"))
            assertEquals(500, first.getInt("next_offset"))
            assertTrue(first.getBoolean("has_more"))
            var offset = 0
            val restored = StringBuilder()
            do {
                val page = store.read(attachment, offset, 500)
                val content = page.getString("content")
                assertTrue(content.length <= 500)
                assertFalse(content.firstOrNull()?.isLowSurrogate() == true)
                assertFalse(content.lastOrNull()?.isHighSurrogate() == true)
                restored.append(content)
                offset = page.getInt("next_offset")
            } while (page.getBoolean("has_more"))
            assertEquals(text, restored.toString())
        }
    }

    @Test fun enormousSingleLineUsesBoundedLiteralSearchSnippetsAndResultLimit() = runBlocking {
        fixture { store, _, sourceRoot ->
            val text = "a".repeat(1024 * 1024) + "[needle.*]".repeat(20)
            val attachment = store.import(uri(File(sourceRoot, "large.log").apply { writeText(text) }))
            val response = store.search(attachment, "[needle.*]", limit = 3)
            val matches = response.getJSONArray("matches")
            assertEquals(3, matches.length())
            assertTrue(response.getBoolean("truncated"))
            for (index in 0 until matches.length()) {
                val match = matches.getJSONObject(index)
                assertEquals(1, match.getInt("line"))
                assertTrue(match.getString("text").length <= 241)
                assertTrue(match.getString("text").contains("[needle.*]"))
            }
            assertEquals(0, store.search(attachment, "not-here").getJSONArray("matches").length())
            assertEquals(0, store.search(attachment, "NEEDLE").getJSONArray("matches").length())
            assertTrue(store.search(attachment, "NEEDLE", caseSensitive = false).getJSONArray("matches").length() > 0)
        }
    }

    @Test fun searchOffsetsExcludeBomAndCanReadUnicodeHitsAcrossLinesDirectly() = runBlocking {
        fixture { store, _, sourceRoot ->
            val text = "\uFEFF前言😀\r\n另一行é🙂needle😀结尾\n结束"
            val attachment = store.import(uri(File(sourceRoot, "unicode-offsets.txt").apply { writeText(text) }))
            val query = "🙂needle😀"
            val match = store.search(attachment, query).getJSONArray("matches").getJSONObject(0)
            val expectedOffset = text.removePrefix("\uFEFF").indexOf(query)
            assertEquals(expectedOffset, match.getInt("offset"))
            assertEquals(2, match.getInt("line"))
            assertEquals(5, match.getInt("column"))
            assertTrue(store.read(attachment, match.getInt("offset"), 100).getString("content").startsWith(query))
            val insensitive = store.search(attachment, "🙂NEEDLE😀", caseSensitive = false).getJSONArray("matches").getJSONObject(0)
            assertEquals(expectedOffset, insensitive.getInt("offset"))
            expect(AiFileAttachmentException.Reason.Unsupported) { store.search(attachment, "\uD83D") }
        }
    }

    @Test fun knownBinaryMimeAndMagicRejectEvenWhenItsInitialBytesAreAscii() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val fixtures = listOf(
                "document.pdf" to "ASCII header without valid pdf magic",
                "archive.zip" to "ASCII header without valid zip magic",
                "picture.gif" to "GIF89aASCII only",
                "renamed-pdf.txt" to "%PDF-1.7\nASCII PDF body",
                "renamed-gif.txt" to "GIF89aASCII only",
                "renamed-webp.txt" to "RIFFabcdWEBP",
            )
            for ((name, bytes) in fixtures) {
                val source = File(sourceRoot, name).apply { writeText(bytes) }
                val attachment = store.import(uri(source))
                assertEquals(bytes, File(archive, "${attachment.id}.bin").readText())
                expect(AiFileAttachmentException.Reason.Unsupported) { store.read(attachment) }
                expect(AiFileAttachmentException.Reason.Unsupported) { store.search(attachment, "ASCII") }
                assertEquals(bytes, source.readText())
            }
            val svg = store.import(uri(File(sourceRoot, "drawing.svg").apply { writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>") }))
            assertTrue(store.read(svg).getString("content").startsWith("<svg"))
        }
    }

    @Test fun binariesAreCopiedIntactButNeverPretendToBeUtf8Text() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val bytes = byteArrayOf(0, 1, 2, -1, 10)
            val source = File(sourceRoot, "binary.bin").apply { writeBytes(bytes) }
            val attachment = store.import(uri(source))
            assertTrue(bytes.contentEquals(File(archive, "${attachment.id}.bin").readBytes()))
            expect(AiFileAttachmentException.Reason.Unsupported) { store.read(attachment) }
            expect(AiFileAttachmentException.Reason.Unsupported) { store.search(attachment, "x") }
            assertTrue(bytes.contentEquals(source.readBytes()))
        }
    }

    @Test fun oversizeAndCapacityFailuresLeaveNoNewArchiveFilesAndNeverChangeSources() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val oversize = File(sourceRoot, "oversize.bin")
            RandomAccessFile(oversize, "rw").use { it.setLength(AiFileAttachments.MAX_FILE_BYTES + 1) }
            expect(AiFileAttachmentException.Reason.TooLarge) { store.import(uri(oversize)) }
            assertEquals(AiFileAttachments.MAX_FILE_BYTES + 1, oversize.length())
            assertTrue(archive.listFiles().orEmpty().isEmpty())
            val source = File(sourceRoot, "source.txt").apply { writeText("source stays unchanged") }
            val unknown = File(archive, "unknown-retained")
            RandomAccessFile(unknown, "rw").use { it.setLength(AiFileAttachments.MAX_STORED_BYTES) }
            expect(AiFileAttachmentException.Reason.Capacity) { store.import(uri(source)) }
            assertEquals(listOf(unknown.name), archive.listFiles().orEmpty().map { it.name })
            assertEquals("source stays unchanged", source.readText())
            unknown.delete()
            repeat(AiFileAttachments.MAX_STORED_FILES) { File(archive, "unknown-$it").writeBytes(byteArrayOf()) }
            expect(AiFileAttachmentException.Reason.Capacity) { store.import(uri(source)) }
            assertEquals(AiFileAttachments.MAX_STORED_FILES, archive.listFiles().orEmpty().size)
        }
    }

    @Test fun actualUnknownSizeStreamHardLimitLeavesNoPartialArchive() = runBlocking {
        fixture { store, archive, _ ->
            val source = Uri.parse("content://${InstrumentationRegistry.getInstrumentation().context.packageName}.shared-file-fixture/archive-undeclared-large")
            expect(AiFileAttachmentException.Reason.TooLarge) { store.import(source) }
            assertTrue(archive.listFiles().orEmpty().isEmpty())
            val declared = Uri.parse("content://${InstrumentationRegistry.getInstrumentation().context.packageName}.shared-file-fixture/archive-declared-large")
            expect(AiFileAttachmentException.Reason.TooLarge) { store.import(declared) }
            assertTrue(archive.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancelledImportRemovesItsTemporaryFileAndPreservesOriginal() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val source = File(sourceRoot, "cancelled.txt").apply { writeText("z".repeat(1024 * 1024)) }
            try {
                store.import(uri(source)) { copied, _ -> if (copied >= 256 * 1024) throw CancellationException("test cancel") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(archive.listFiles().orEmpty().isEmpty())
            assertEquals(1024L * 1024, source.length())
        }
    }

    @Test fun sharedReferencePruningRetainsNeededCopiesUnknownEntriesAndLinks() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val source = File(sourceRoot, "source.txt").apply { writeText("not modified") }
            val kept = store.import(uri(source))
            val removed = store.import(uri(source))
            val unknown = File(archive, "unrecognized-file").apply { writeText("keep") }
            val abandoned = File(archive, "${UUID.randomUUID()}.part").apply { writeText("interrupted import") }
            val linked = File(archive, "${UUID.randomUUID()}.bin")
            Os.symlink(source.path, linked.path)
            store.pruneUnreferenced(setOf(kept.id))
            assertTrue(File(archive, "${kept.id}.bin").exists())
            assertFalse(File(archive, "${removed.id}.bin").exists())
            assertTrue(unknown.exists())
            assertFalse(abandoned.exists())
            assertTrue(linked.exists())
            store.deleteAll(listOf(kept))
            assertFalse(File(archive, "${kept.id}.bin").exists())
            assertEquals("not modified", source.readText())
        }
    }

    @Test fun repeatedImportsReadsSearchesAndFailuresDoNotLeakOwnedDescriptors() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val source = File(sourceRoot, "descriptor-source.txt").apply { writeText("descriptor needle 你好\n") }
            val binary = File(sourceRoot, "renamed-binary.txt").apply { writeText("%PDF-1.7\nASCII binary header") }
            // Warm the provider/channel classes before counting process descriptors.
            val warm = store.import(uri(source))
            store.read(warm)
            store.search(warm, "needle")
            store.deleteAll(listOf(warm))
            val before = requireNotNull(File("/proc/self/fd").list()).size
            repeat(32) {
                val attachment = store.import(uri(source))
                assertTrue(store.read(attachment).getString("content").contains("needle"))
                assertEquals(1, store.search(attachment, "needle").getJSONArray("matches").length())
                // Failed fstat preflight must release its original Os.open descriptor too.
                expect(AiFileAttachmentException.Reason.Unavailable) { store.read(attachment.copy(bytes = attachment.bytes + 1)) }
                val unsupported = store.import(uri(binary))
                expect(AiFileAttachmentException.Reason.Unsupported) { store.read(unsupported) }
                expect(AiFileAttachmentException.Reason.Unsupported) { store.search(unsupported, "ASCII") }
                try {
                    store.import(uri(source)) { _, _ -> throw CancellationException("cancel after streams open") }
                    fail("Expected cancellation")
                } catch (_: CancellationException) { }
                store.deleteAll(listOf(attachment, unsupported))
            }
            val after = requireNotNull(File("/proc/self/fd").list()).size
            // Allow a few unrelated runtime descriptors, never one retained per archive operation.
            assertTrue("Open descriptors grew from $before to $after", after <= before + 4)
            assertTrue(archive.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun missingFilesAndSymlinksDoNotExposeHostFilesOrProviderUris() = runBlocking {
        fixture { store, archive, sourceRoot ->
            val source = File(sourceRoot, "source.txt").apply { writeText("private original") }
            val attachment = store.import(uri(source))
            File(archive, "${attachment.id}.bin").delete()
            expect(AiFileAttachmentException.Reason.Unavailable) { store.read(attachment) }
            Os.symlink(source.path, File(archive, "${attachment.id}.bin").path)
            expect(AiFileAttachmentException.Reason.Unavailable) { store.read(attachment) }
            store.deleteAll(listOf(attachment))
            assertEquals("private original", source.readText())
            assertTrue(File(archive, "${attachment.id}.bin").exists())
            expect(AiFileAttachmentException.Reason.Unsupported) { store.import(Uri.parse("file:///private/source")) }
        }
    }

    private fun uri(source: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.apk-files", source)

    private suspend fun fixture(block: suspend (AiFileAttachmentStore, File, File) -> Unit) {
        val root = File(context.cacheDir, "apk-install/archive-store-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val private = File(root, "private").apply { check(mkdir()) }
        val archive = File(private, "ai-files-v1").apply { check(mkdir()) }
        val source = File(root, "source").apply { check(mkdir()) }
        try { block(AiFileAttachmentStore(StoreContext(context, private)), archive, source) }
        finally { root.walkBottomUp().forEach { entry -> entry.delete() } }
    }

    private class StoreContext(base: Context, private val privateRoot: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = privateRoot
    }

    private suspend fun expect(reason: AiFileAttachmentException.Reason, block: suspend () -> Any?) {
        try { block(); fail("Expected ${reason.name}") }
        catch (failure: AiFileAttachmentException) {
            assertEquals(reason, failure.reason)
            assertFalse(failure.message.orEmpty().contains("content://"))
            assertEquals(null, failure.cause)
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

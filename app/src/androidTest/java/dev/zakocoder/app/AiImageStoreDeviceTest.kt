package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Real provider and bitmap decoder with exclusively owned private/cache fixtures; no model calls. */
class AiImageStoreDeviceTest {
    private suspend fun fixture(action: suspend (AiImageStore, File, File) -> Unit) = withContext(Dispatchers.IO) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = "images-fixture-${UUID.randomUUID()}"
        val source = File(base.cacheDir, "apk-install/$token").apply { mkdirs() }
        val archive = File(base.cacheDir, token).apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = archive }
        try { action(AiImageStore(context), source, archive) }
        finally { source.deleteRecursively(); archive.deleteRecursively() }
    }
    private fun uri(source: File): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FileProvider.getUriForFile(context, "${context.packageName}.apk-files", source)
    }
    @Test(timeout = 60000) fun importNormalizesRealPixelsAndSurvivesStoreRecreation() = runBlocking {
        fixture { store, source, archive ->
            val png = File(source, "screen.png")
            Bitmap.createBitmap(2400, 900, Bitmap.Config.ARGB_8888).let { bitmap ->
                try { bitmap.eraseColor(Color.RED); png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            val image = store.import(uri(png))
            assertEquals("image/jpeg", image.mimeType)
            assertEquals(1600, image.width); assertEquals(600, image.height)
            assertTrue(image.bytes in 1..AiImages.MAX_IMAGE_BYTES)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val restored = AiImageStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = archive })
            val data = restored.dataUrl(image)
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            assertEquals(image.bytes, Base64.decode(data.substringAfter(','), Base64.NO_WRAP).size)
            val thumbnail = restored.thumbnail(image)
            try { assertEquals(320, thumbnail.width); assertEquals(120, thumbnail.height) }
            finally { thumbnail.recycle() }
            assertEquals(1, File(archive, "ai-images-v1").listFiles()!!.size)
            restored.delete(image)
            assertTrue(File(archive, "ai-images-v1").listFiles()!!.isEmpty())
            assertTrue("Removing an imported attachment must preserve its source", png.isFile)
            restored.delete(image) // An already absent owned attachment is harmless.
        }
    }
    @Test(timeout = 60000) fun rejectsNonImagesOversizedInputAndNonProviderUrisWithoutStoredPayload() = runBlocking {
        fixture { store, source, archive ->
            val invalid = File(source, "pretend.png").apply { writeText("not an image") }
            assertTrue(runCatching { store.import(uri(invalid)) }.isFailure)
            assertTrue(runCatching { store.import(Uri.fromFile(invalid)) }.isFailure)
            val large = File(source, "large.png")
            java.io.RandomAccessFile(large, "rw").use { it.setLength(AiImages.MAX_SOURCE_BYTES + 1L) }
            assertTrue(runCatching { store.import(uri(large)) }.isFailure)
            assertTrue(!File(archive, "ai-images-v1").exists() || File(archive, "ai-images-v1").listFiles()!!.isEmpty())
        }
    }
    @Test(timeout = 60000) fun missingAndLinkedArchiveImagesCannotReadOrDeleteOutsideArchive() = runBlocking {
        fixture { store, source, archive ->
            val png = File(source, "small.png")
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                try { png.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            val image = store.import(uri(png))
            val stored = File(archive, "ai-images-v1/${image.id}.jpg")
            assertTrue(stored.delete())
            assertTrue(runCatching { store.dataUrl(image) }.isFailure)
            val outside = File(source, "keep.jpg").apply { writeText("preserve outside fixture") }
            Files.createSymbolicLink(stored.toPath(), outside.toPath())
            assertTrue(runCatching { store.dataUrl(image) }.isFailure)
            assertTrue(runCatching { store.delete(image) }.isFailure)
            assertEquals("preserve outside fixture", outside.readText())
            Files.delete(stored.toPath())
        }
    }

    @Test(timeout = 60000) fun pruningRetainsReferencesAcrossForksAndOtherProjectContextUntilTheirLastOwnerIsDeleted() = runBlocking {
        fixture { store, source, archive ->
            val png = smallPng(source)
            val shared = store.import(uri(png))
            val contextual = store.import(uri(png))
            val orphan = store.import(uri(png))
            val history = AiHistoryStore(File(archive, "history"))
            val original = AiState(turn = 2, timeline = listOf(
                AiEntry("user-one", "look", role = AiRole.User, turn = 1, images = listOf(shared)),
                AiEntry("answer-one", "first", role = AiRole.Assistant, turn = 1),
                AiEntry("user-two", "next", role = AiRole.User, turn = 2),
                AiEntry("answer-two", "second", role = AiRole.Assistant, turn = 2)))
            history.save("first-project", original)
            val fork = original.forkBeforeTurn(2)
            history.save("first-project", fork)
            val context = JSONArray().put(imageMessage("inspect contextual", listOf(contextual)))
                .put(JSONObject().put("role", "assistant").put("content", "done"))
            val other = AiState(turn = 1, contextTurns = listOf(context.toString()))
            history.save("second-project", other)
            history.delete("first-project", original.conversationId)
            assertEquals(setOf(shared.id, contextual.id), history.imageReferences())
            store.pruneUnreferenced(history.imageReferences())
            assertTrue(store.dataUrl(shared).startsWith("data:image/jpeg"))
            assertTrue(store.dataUrl(contextual).startsWith("data:image/jpeg"))
            assertTrue(runCatching { store.dataUrl(orphan) }.isFailure)
            history.delete("first-project", fork.conversationId)
            store.pruneUnreferenced(history.imageReferences())
            assertTrue(runCatching { store.dataUrl(shared) }.isFailure)
            assertTrue(store.dataUrl(contextual).startsWith("data:image/jpeg"))
            history.deleteProject("second-project")
            store.pruneUnreferenced(history.imageReferences())
            assertTrue(runCatching { store.dataUrl(contextual) }.isFailure)
            assertTrue("Import source is not part of the archive GC", png.isFile)
        }
    }

    @Test(timeout = 60000) fun backupsOwnReferencesAndCorruptOrInvalidHistoryAbortsCollectionBeforeAnyPrune() = runBlocking {
        fixture { store, source, archive ->
            val image = store.import(uri(smallPng(source)))
            val orphan = store.import(uri(smallPng(source)))
            val historyRoot = File(archive, "history")
            val history = AiHistoryStore(historyRoot)
            val state = AiState(turn = 1, timeline = listOf(AiEntry("user", "look", role = AiRole.User, turn = 1, images = listOf(image))))
            history.save("project", state)
            val record = historyRoot.walkTopDown().single { it.name == "${state.conversationId}.json" }
            val saved = record.readText()
            val backup = File(record.path + ".bak").apply { writeText(saved) }
            val current = JSONObject(saved)
            current.getJSONArray("timeline").getJSONObject(0).put("images", JSONArray())
            record.writeText(current.toString())
            assertEquals(setOf(image.id), history.imageReferences())
            val invalid = File(record.parentFile, "${UUID.randomUUID()}.json").apply { writeText("{broken") }
            assertTrue(runCatching { store.pruneUnreferenced(history.imageReferences()) }.isFailure)
            assertTrue(store.dataUrl(orphan).startsWith("data:image/jpeg"))
            assertTrue(invalid.delete())
            val badReference = JSONObject(saved)
            badReference.getJSONArray("timeline").getJSONObject(0).getJSONArray("images").getJSONObject(0).put("id", "../outside")
            backup.writeText(badReference.toString())
            assertTrue(runCatching { store.pruneUnreferenced(history.imageReferences()) }.isFailure)
            assertTrue(store.dataUrl(orphan).startsWith("data:image/jpeg"))
        }
    }

    @Test(timeout = 60000) fun pruningKeepsUnknownFilesAndLinksAndRejectsMalformedKeepSets() = runBlocking {
        fixture { store, source, archive ->
            val image = store.import(uri(smallPng(source)))
            val root = File(archive, "ai-images-v1")
            val unknown = File(root, "notes.jpg").apply { writeText("keep unknown data") }
            val outside = File(source, "outside").apply { writeText("keep external data") }
            val link = File(root, "${UUID.randomUUID()}.jpg")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(runCatching { store.pruneUnreferenced(setOf("../outside")) }.isFailure)
            assertTrue(store.dataUrl(image).startsWith("data:image/jpeg"))
            store.pruneUnreferenced(emptySet())
            assertTrue(runCatching { store.dataUrl(image) }.isFailure)
            assertEquals("keep unknown data", unknown.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("keep external data", outside.readText())
            Files.delete(link.toPath())
        }
    }

    private fun smallPng(source: File): File = File(source, "small-${UUID.randomUUID()}.png").also { file ->
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            try { bitmap.eraseColor(Color.BLUE); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
}

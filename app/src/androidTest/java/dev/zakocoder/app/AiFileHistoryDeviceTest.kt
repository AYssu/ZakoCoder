package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Disposable history records only; no user conversations or shared documents are inspected. */
class AiFileHistoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (AiHistoryStore, File) -> Unit) {
        val directory = File(context.cacheDir, "file-history-${UUID.randomUUID()}")
        try { block(AiHistoryStore(directory), directory) } finally { directory.deleteRecursively() }
    }
    private fun project(directory: File, id: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun record(directory: File, project: String, state: AiState) = File(project(directory, project), "${state.conversationId}.json")
    private fun attachment(name: String = "source.kt") = AiFileAttachment(UUID.randomUUID().toString(), name, "text/plain", 4_000_000L, "a".repeat(64))
    private fun state(file: AiFileAttachment) = AiState(turn = 1, fileAttachments = listOf(file), fileDraftIds = listOf(file.id),
        timeline = listOf(AiEntry(UUID.randomUUID().toString(), "Review attachment", role = AiRole.User, turn = 1,
            inputLength = "Review attachment".length, inputReferences = listOf(file.reference()))))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid attachment ownership must be rejected") }
        catch (error: Exception) { assertTrue(error is IllegalArgumentException || error is IllegalStateException || error is AiHistoryReadFailure || error is org.json.JSONException) }
    }

    @Test fun roundTripsCompleteLedgerDraftAndReferenceWithoutFileContents() = fixture { store, directory ->
        val file = attachment()
        val original = state(file)
        store.save("project", original)
        val loaded = AiHistoryStore(directory).load("project").state!!
        assertEquals(listOf(file), loaded.fileAttachments)
        assertEquals(listOf(file.id), loaded.fileDraftIds)
        assertEquals(file.reference(), loaded.timeline.single().inputReferences.single())
        val rendered = loaded.timeline.single().inputReferences.single().render()
        assertTrue(rendered.contains("read_file")); assertTrue(rendered.contains("search_files"))
        assertTrue(rendered.contains("attachment:${file.id}")); assertTrue(rendered.contains("4000000"))
        assertEquals("", loaded.timeline.single().inputReferences.single().text)
        assertEquals(setOf(file.id), AiHistoryStore(directory).fileAttachmentReferences())
    }

    @Test fun readsOldSchemaWithoutAttachmentFieldsAndPreservesLiteralReferences() = fixture { store, directory ->
        val legacy = AiState(timeline = listOf(AiEntry(UUID.randomUUID().toString(), "Old message", role = AiRole.User,
            inputReferences = listOf(AiReference("old.kt", "old contents")))))
        store.save("project", legacy)
        val file = record(directory, "project", legacy)
        val json = JSONObject(file.readText()).apply { remove("fileAttachments"); remove("fileDraftIds") }
        file.writeText(json.toString())
        val loaded = AiHistoryStore(directory).load("project").state!!
        assertTrue(loaded.fileAttachments.isEmpty()); assertTrue(loaded.fileDraftIds.isEmpty())
        assertEquals("old contents", loaded.timeline.single().inputReferences.single().text)
        assertTrue(AiHistoryStore(directory).fileAttachmentReferences().isEmpty())
    }

    @Test fun scansEveryConversationAndRecoveryRecordBeforeFilesCanBeCollected() = fixture { store, directory ->
        val first = attachment(); val second = attachment(); val backup = attachment()
        val one = state(first); val two = state(second); val recovery = state(backup)
        store.save("first-project", one); store.save("second-project", two)
        val recoveryJson = record(directory, "first-project", one)
        val recoveryRoot = project(directory, "first-project")
        store.save("first-project", recovery)
        record(directory, "first-project", recovery).renameTo(File(recoveryRoot, "${recovery.conversationId}.json.bak"))
        val expected = setOf(first.id, second.id, backup.id)
        assertEquals(expected, AiHistoryStore(directory).fileAttachmentReferences())
        // A .new record may still own a newly copied attachment after an interrupted atomic save.
        File(recoveryJson.path + ".new").writeText(recoveryJson.readText())
        assertEquals(expected, AiHistoryStore(directory).fileAttachmentReferences())
        store.delete("first-project", one.conversationId)
        assertEquals(setOf(second.id, backup.id), AiHistoryStore(directory).fileAttachmentReferences())
    }

    @Test fun rejectsMetadataPathContentAndDraftTamperingWithoutRewritingHistory() = fixture { store, directory ->
        val original = state(attachment()); store.save("project", original)
        val file = record(directory, "project", original)
        val valid = file.readText()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("fileAttachments", JSONObject()) },
            { it.put("fileDraftIds", JSONArray().put(UUID.randomUUID().toString())) },
            { it.put("fileDraftIds", JSONArray().put(original.fileDraftIds.single()).put(original.fileDraftIds.single())) },
            { it.getJSONArray("fileAttachments").getJSONObject(0).put("bytes", "4000000") },
            { it.getJSONArray("fileAttachments").getJSONObject(0).put("sha256", "invalid") },
            { it.getJSONArray("fileAttachments").getJSONObject(0).put("displayName", "../escape") },
            { it.getJSONArray("timeline").getJSONObject(0).getJSONArray("inputReferences").getJSONObject(0).put("path", "attachment:another") },
            { it.getJSONArray("timeline").getJSONObject(0).getJSONArray("inputReferences").getJSONObject(0).put("text", "unexpected full content") },
            { it.getJSONArray("timeline").getJSONObject(0).getJSONArray("inputReferences").getJSONObject(0).getJSONObject("attachment").put("bytes", 1L) },
            { it.remove("fileAttachments") },
        )
        mutations.forEach { mutate ->
            val json = JSONObject(valid).also(mutate).toString()
            file.writeText(json)
            rejected { AiHistoryStore(directory).load("project") }
            rejected { AiHistoryStore(directory).fileAttachmentReferences() }
            assertEquals(json, file.readText())
        }
    }

    @Test fun scannerRejectsUnknownSchemaLayoutCorruptionAndLinksWithoutPartialResult() = fixture { store, directory ->
        val original = state(attachment()); store.save("project", original)
        val file = record(directory, "project", original)
        val valid = file.readText()
        file.writeText(JSONObject(valid).put("schemaVersion", 99).toString())
        rejected { store.fileAttachmentReferences() }
        file.writeText("{broken")
        rejected { store.fileAttachmentReferences() }
        file.writeText(valid)
        val unknown = File(file.parentFile, "unexpected.txt").apply { writeText("do not delete") }
        rejected { store.fileAttachmentReferences() }; assertTrue(unknown.exists()); unknown.delete()
        val link = File(file.parentFile, "${UUID.randomUUID()}.json")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        rejected { store.fileAttachmentReferences() }; assertTrue(Files.isSymbolicLink(link.toPath()))
        Files.delete(link.toPath())
        assertEquals(setOf(original.fileAttachments.single().id), store.fileAttachmentReferences())
    }

    @Test fun enforcesPerConversationLedgerAndDraftOwnershipBeforeSaving() = fixture { store, directory ->
        val file = attachment(); val original = state(file)
        rejected { store.save("project", original.copy(fileAttachments = emptyList())) }
        rejected { store.save("project", original.copy(fileAttachments = listOf(file, file))) }
        rejected { store.save("project", original.copy(fileAttachments = (0..AiFileAttachments.MAX_CONVERSATION_FILES).map { attachment() })) }
        rejected { store.save("project", original.copy(fileDraftIds = listOf(UUID.randomUUID().toString()))) }
        assertTrue(store.fileAttachmentReferences().isEmpty())
        assertFalse(record(directory, "project", original).exists())
    }
}

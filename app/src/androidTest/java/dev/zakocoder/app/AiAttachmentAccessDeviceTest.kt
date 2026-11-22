package dev.zakocoder.app.ai

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AiAttachmentAccessDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun existingReadAndSearchToolsReachCompleteTailThroughOwnedOpaqueId() = runBlocking {
        val root = File(context.cacheDir, "apk-install/attachment-access-${UUID.randomUUID()}").apply { mkdirs() }
        val store = AiFileAttachmentStore(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        })
        try {
            val original = "prefix\n".repeat(200_000) + "unique-last-line 你好\n"
            val source = File(root, "large.txt").apply { writeText(original) }
            val attachment = store.import(FileProvider.getUriForFile(context, context.packageName + ".apk-files", source))
            val state = AiState(projectId = "owned", fileAttachments = listOf(attachment))
            val access = AiAttachmentAccess(context, store)
            val args = JSONObject().put("path", "attachment:${attachment.id}").put("offset", original.indexOf("unique-last-line")).put("limit", 100)
            val response = JSONObject(access.execute("owned", state, AiTool.ReadFile, args))
            assertEquals("unique-last-line 你好\n", response.getString("content"))
            assertTrue(response.getBoolean("untrusted_reference"))
            assertFalse(response.getBoolean("has_more"))
            val search = JSONObject(access.execute("owned", state, AiTool.SearchFiles,
                JSONObject().put("path", "attachment:${attachment.id}").put("query", "UNIQUE-LAST-LINE").put("case_sensitive", false)))
            assertEquals(200_001, search.getJSONArray("matches").getJSONObject(0).getInt("line"))
            assertFalse(attachment.reference().render().contains("unique-last-line"))
            assertEquals(original, source.readText())
            for (tool in listOf(AiTool.WriteFile, AiTool.EditFile, AiTool.RunCommand, AiTool.ListFiles)) {
                assertTrue(runCatching { AiAttachmentAccess.requireOwned("owned", state, tool, "attachment:${attachment.id}") }.isFailure)
            }
            assertTrue(runCatching { AiAttachmentAccess.requireOwned("other", state, AiTool.ReadFile, "attachment:${attachment.id}") }.isFailure)
            assertTrue(runCatching { AiAttachmentAccess.requireOwned("owned", state.copy(fileAttachments = emptyList()), AiTool.ReadFile, "attachment:${attachment.id}") }.isFailure)
            assertTrue(runCatching { AiAttachmentAccess.requireOwned("owned", state, AiTool.ReadFile, "attachment:../outside") }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun ordinaryForkOnlyInheritsAttachmentsAlreadySentBeforeItsBoundary() {
        val past = file("past.txt")
        val retry = file("retry.txt")
        val future = file("future.txt")
        val draft = file("unsent-secret.txt")
        val state = forkFixture(past, retry, future, draft)
        val fork = state.forkBeforeTurn(2)
        assertEquals(listOf(past), fork.fileAttachments)
        assertTrue(fork.references.isEmpty())
        assertTrue(fork.fileDraftIds.isEmpty())
        assertEquals(listOf(draft.reference()), state.references)
        for (excluded in listOf(retry, future, draft)) {
            assertTrue(runCatching { AiAttachmentAccess.requireOwned("owned", fork, AiTool.ReadFile, "attachment:${excluded.id}") }.isFailure)
        }
        assertEquals(past, AiAttachmentAccess.requireOwned("owned", fork, AiTool.ReadFile, "attachment:${past.id}"))
    }

    @Test fun retryForkAddsOnlyItsSelectedSentInputAndExcludesFutureAndUnsentDrafts() {
        val past = file("past.txt")
        val retry = file("retry.txt")
        val future = file("future.txt")
        val draft = file("unsent-secret.txt")
        val state = forkFixture(past, retry, future, draft)
        val fork = state.forkBeforeTurn(2, includeRetryInput = true)
        assertEquals(listOf(past, retry), fork.fileAttachments)
        assertTrue(fork.references.isEmpty())
        assertTrue(fork.fileDraftIds.isEmpty())
        assertEquals(retry, AiAttachmentAccess.requireOwned("owned", fork, AiTool.ReadFile, "attachment:${retry.id}"))
        for (excluded in listOf(future, draft)) {
            assertTrue(runCatching { AiAttachmentAccess.requireOwned("owned", fork, AiTool.ReadFile, "attachment:${excluded.id}") }.isFailure)
        }
        assertEquals(listOf(past, retry, future, draft), state.fileAttachments)
        assertEquals(listOf(draft.id), state.fileDraftIds)
    }

    private fun file(name: String) = AiFileAttachment(UUID.randomUUID().toString(), name, "text/plain", 100, "a".repeat(64))

    private fun forkFixture(past: AiFileAttachment, retry: AiFileAttachment, future: AiFileAttachment, draft: AiFileAttachment): AiState =
        AiState(projectId = "owned", turn = 3, fileAttachments = listOf(past, retry, future, draft),
            references = listOf(draft.reference()), fileDraftIds = listOf(draft.id), timeline = listOf(
                AiEntry(UUID.randomUUID().toString(), "past", role = AiRole.User, turn = 1, inputReferences = listOf(past.reference())),
                AiEntry(UUID.randomUUID().toString(), "retry", role = AiRole.User, turn = 2, inputReferences = listOf(retry.reference())),
                AiEntry(UUID.randomUUID().toString(), "future", role = AiRole.User, turn = 3, inputReferences = listOf(future.reference())),
            ))

    @Test fun forkRetainsReadOwnershipUntilBothConversationsAreDeleted() {
        val attachment = AiFileAttachment(UUID.randomUUID().toString(), "source.txt", "text/plain", 100, "a".repeat(64))
        val state = AiState(projectId = "owned", turn = 2, fileAttachments = listOf(attachment),
            timeline = listOf(AiEntry(UUID.randomUUID().toString(), "inspect", role = AiRole.User, turn = 1, inputReferences = listOf(attachment.reference()))))
        val fork = state.forkBeforeTurn(2)
        assertEquals(listOf(attachment), fork.fileAttachments)
        assertEquals(attachment, AiAttachmentAccess.requireOwned("owned", fork, AiTool.ReadFile, "attachment:${attachment.id}"))
    }
}

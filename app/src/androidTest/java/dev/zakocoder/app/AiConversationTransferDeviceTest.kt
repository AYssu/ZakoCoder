package dev.zakocoder.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated histories and intercepted transport only: never reads a user's configuration or reaches a model. */
class AiConversationTransferDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(20) }
    private fun state(text: String) = AiState(turn = 1, timeline = listOf(
        AiEntry(UUID.randomUUID().toString(), text, role = AiRole.User, turn = 1),
        AiEntry(UUID.randomUUID().toString(), "Observed result for $text; verification pending.", role = AiRole.Assistant, turn = 1)))

    private inner class Fixture {
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "conversation-transfer-$token").apply { check(mkdirs()) }
        val documents = File(base.cacheDir, "apk-install/transfer-$token").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name", mode)
        }
        val requests = AtomicInteger()
        val commands = AtomicInteger()
        val alias = "transfer-$token"
        val config = AiConfigStore(isolated, "provider", alias)
        val transport = ChatTransport(OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("Transfer must not initiate a model request")
        }.build(), maxAttempts = 1)
        val project = "workspace-$token"
        var controller: AiController? = null
        suspend fun open(history: AiHistoryStore): AiController {
            withContext(Dispatchers.IO) { config.save("https://conversation-transfer.invalid/v1", "fixture-model", "fixture-only-placeholder") }
            val ai = withContext(Dispatchers.Main) {
                AiController(isolated, { File(root, "workspace").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> commands.incrementAndGet(); error("Transfer must not execute commands") }, {}, {},
                    transport, config, history = history).also { controller = it; it.select(project) }
            }
            await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.permissionsSaving &&
                !ai.state.value.skillsLoading && !ai.models.state.value.loading }
            return ai
        }
        suspend fun close() {
            withContext(NonCancellable + Dispatchers.Main) { controller?.stop() }
            withContext(NonCancellable + Dispatchers.IO) {
                root.deleteRecursively()
                documents.deleteRecursively()
                isolated.getSharedPreferences("first-run-setup", 0).edit().clear().commit()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
        }
    }

    @Test(timeout = 60_000) fun confirmedImportCreatesNewIdleConversationAndPreservesOriginalWithoutExecuting() = runBlocking {
        val f = Fixture()
        try {
            val history = AiHistoryStore(File(f.root, "history"))
            val original = state("Original task")
            withContext(Dispatchers.IO) { history.save(f.project, original) }
            val ai = f.open(history)
            val before = ai.state.value
            val document = AiConversationTransfer.decode("# Handoff\n\nFinish the pending tests.".toByteArray(), "handoff.md")
            assertTrue(ai.importConversation(f.project, document))
            val imported = ai.state.value
            assertNotEquals(original.conversationId, imported.conversationId)
            assertEquals(f.project, imported.projectId)
            assertEquals(AiPhase.Idle, imported.phase)
            assertFalse(imported.active)
            assertNull(imported.approval)
            assertNull(imported.checkpointId)
            assertNull(imported.questionContinuation)
            assertTrue(imported.contextTurns.isEmpty())
            assertTrue(imported.timeline.any { it.text.contains("pending tests") })
            assertEquals(before.policy, imported.policy)
            assertEquals(before.model, imported.model)
            assertEquals(before.endpoint, imported.endpoint)
            withContext(Dispatchers.IO) {
                assertEquals(original.timeline, history.load(f.project, original.conversationId).state!!.timeline)
                assertEquals(imported.conversationId, history.load(f.project).state!!.conversationId)
                assertEquals(2, history.listConversations(f.project).size)
            }
            delay(150)
            assertEquals(0, f.requests.get())
            assertEquals(0, f.commands.get())
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun exportingAnotherConversationNeverChangesMemoryOrSelectedDiskPointer() = runBlocking {
        val f = Fixture()
        try {
            val history = AiHistoryStore(File(f.root, "history"))
            val original = state("Selected task")
            val other = state("Other task")
            withContext(Dispatchers.IO) { history.save(f.project, other); history.save(f.project, original) }
            val ai = f.open(history)
            val result = ai.exportConversation(f.project, other.conversationId, AiConversationTransfer.Format.Markdown)
            assertTrue(result.contains("Other task"))
            assertFalse(result.contains("Selected task"))
            assertEquals(original.conversationId, ai.state.value.conversationId)
            assertEquals(original.timeline, ai.state.value.timeline)
            assertFalse(ai.state.value.permissionsSaving)
            withContext(Dispatchers.IO) { assertEquals(original.conversationId, history.load(f.project).state!!.conversationId) }
            assertEquals(0, f.requests.get())
            assertEquals(0, f.commands.get())
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun failureAfterNewRecordWasWrittenRestoresOriginalSelectionAndRemovesOnlyNewRecord() = runBlocking {
        val f = Fixture()
        try {
            val original = state("Keep this task")
            var failNewRecord = false
            val history = object : AiHistoryStore(File(f.root, "history")) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    val saved = super.save(projectId, state)
                    if (failNewRecord && state.conversationId != original.conversationId) throw IOException("Injected post-write failure")
                    return saved
                }
            }
            withContext(Dispatchers.IO) { history.save(f.project, original) }
            val ai = f.open(history)
            failNewRecord = true
            val document = AiConversationTransfer.fromState(state("Imported task"), "Imported")
            assertFalse(ai.importConversation(f.project, document))
            assertEquals(original.conversationId, ai.state.value.conversationId)
            assertEquals(original.timeline, ai.state.value.timeline)
            assertFalse(ai.state.value.historyError)
            assertFalse(ai.state.value.permissionsLoading)
            withContext(Dispatchers.IO) {
                assertEquals(listOf(original.conversationId), history.listConversations(f.project).map { it.id })
                assertEquals(original.conversationId, history.load(f.project).state!!.conversationId)
            }
            assertEquals(0, f.requests.get())
            assertEquals(0, f.commands.get())
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun preparedSnapshotSurvivesDocumentHelperRecreationAndIsDeletedAfterSuccessfulSave() = runBlocking {
        val f = Fixture()
        try {
            val content = "# Portable conversation\n\nA prepared snapshot survives the document picker.\n"
            val token = AiConversationDocuments(f.isolated).prepare(content)
            assertEquals(token, UUID.fromString(token).toString())
            val destination = File(f.documents, "conversation.md")
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", destination)
            // A fresh helper represents an Activity/controller recreated while the picker was open.
            AiConversationDocuments(f.isolated).writePrepared(uri, token)
            withContext(Dispatchers.IO) {
                assertEquals(content, destination.readText(Charsets.UTF_8))
                assertFalse(File(f.isolated.cacheDir, "conversation-exports-v1/$token.txt").exists())
            }
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun failedPreparedSaveKeepsSnapshotAndRejectsInvalidOrOversizedCacheBeforeOpeningDestination() = runBlocking {
        val f = Fixture()
        try {
            val documents = AiConversationDocuments(f.isolated)
            val token = documents.prepare("# Keep this snapshot\n")
            try {
                documents.writePrepared(Uri.parse("content://dev.zakocoder.missing-transfer-provider/document"), token)
                fail("A missing document provider must fail")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            val cache = File(f.isolated.cacheDir, "conversation-exports-v1/$token.txt")
            assertTrue(cache.isFile)
            assertEquals("# Keep this snapshot\n", withContext(Dispatchers.IO) { cache.readText() })
            val destination = File(f.documents, "untouched.md").apply { writeText("Preserve destination") }
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", destination)
            try {
                documents.writePrepared(uri, "../escape")
                fail("Only canonical UUID tokens are accepted")
            } catch (expected: IllegalArgumentException) { }
            withContext(Dispatchers.IO) { cache.outputStream().use { output ->
                val buffer = ByteArray(8192)
                repeat(AiConversationTransfer.MAX_BYTES / buffer.size + 1) { output.write(buffer) }
            } }
            try {
                documents.writePrepared(uri, token)
                fail("An oversized prepared file must fail before truncating the chosen document")
            } catch (expected: AiConversationTransferException) { assertEquals(AiConversationTransferProblem.TooLarge, expected.reason) }
            assertEquals("Preserve destination", withContext(Dispatchers.IO) { destination.readText() })
            documents.discard(token)
            assertFalse(cache.exists())
            val expired = documents.prepare("# Expired\n")
            withContext(Dispatchers.IO) { assertTrue(File(f.isolated.cacheDir, "conversation-exports-v1/$expired.txt")
                .setLastModified(System.currentTimeMillis() - 2L * 60 * 60 * 1000)) }
            val fresh = documents.prepare("# Fresh\n")
            assertFalse(File(f.isolated.cacheDir, "conversation-exports-v1/$expired.txt").exists())
            documents.discard(fresh)
        } finally { f.close() }
    }

    @Test(timeout = 60_000) fun fullWorkspaceAndDifferentWorkspaceBothRejectImportWithoutChangingHistory() = runBlocking {
        val f = Fixture()
        try {
            val history = AiHistoryStore(File(f.root, "history"))
            withContext(Dispatchers.IO) { repeat(20) { history.save(f.project, state("Task $it")) } }
            val ai = f.open(history)
            val original = ai.state.value
            val document = AiConversationTransfer.fromState(state("Imported task"), "Imported")
            assertFalse(ai.importConversation("different-workspace", document))
            assertFalse(ai.importConversation(f.project, document))
            assertEquals(original.conversationId, ai.state.value.conversationId)
            assertFalse(ai.state.value.permissionsLoading)
            withContext(Dispatchers.IO) {
                assertEquals(20, history.listConversations(f.project).size)
                assertEquals(original.conversationId, history.load(f.project).state!!.conversationId)
            }
            assertEquals(0, f.requests.get())
            assertEquals(0, f.commands.get())
        } finally { f.close() }
    }
}

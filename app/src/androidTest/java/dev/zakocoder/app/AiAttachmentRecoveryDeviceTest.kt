package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiController
import dev.zakocoder.app.ai.AiConversation
import dev.zakocoder.app.ai.AiFileAttachmentStore
import dev.zakocoder.app.ai.AiHistoryStore
import dev.zakocoder.app.ai.AiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Real archive and controller with a narrowly injected history-write fault; no external model calls. */
class AiAttachmentRecoveryDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30000) { while (!predicate()) delay(20) }
    private suspend fun ready(ai: AiController, project: String) = await {
        ai.state.value.projectId == project && ai.state.value.configured && !ai.state.value.permissionsLoading &&
            !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }

    @Test(timeout = 120000) fun failedAttachmentDraftSaveSurvivesWorkspaceSwitchAndCanRetryBeforeControllerRestart() = runBlocking {
        AiDeviceTest.Fixture(base).use { fixture ->
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(fixture.root, "controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "controller-private").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("${fixture.token}-$name", mode)
            }
            val failSaves = AtomicBoolean(false)
            val history = object : AiHistoryStore(File(fixture.root, "history")) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    if (failSaves.get() && projectId == "attachment-owner") throw IOException("fixture write unavailable")
                    return super.save(projectId, state)
                }
            }
            val original = AiState()
            withContext(Dispatchers.IO) {
                history.save("attachment-owner", original)
                history.save("another-workspace", AiState())
            }
            fun controller(): AiController = AiController(isolated,
                { project -> File(fixture.root, "project-$project").apply { mkdirs() } }, { _, _ -> false },
                { _, _ -> error("No shell expected") }, {}, {}, fixture.transport, fixture.config, history = history)
            val sourceRoot = File(base.cacheDir, "apk-install/${fixture.token}-attachment-recovery").apply { check(mkdirs()) }
            val content = "unchanged source line\n".repeat(60000) + "complete attachment tail\n"
            val source = File(sourceRoot, "large-source.txt").apply { writeText(content) }
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", source)
            var first: AiController? = null
            var restarted: AiController? = null
            try {
                val ai = withContext(Dispatchers.Main) { controller().also { first = it; it.select("attachment-owner") } }
                ready(ai, "attachment-owner")
                assertEquals(original.conversationId, ai.state.value.conversationId)
                failSaves.set(true)
                val imported = withContext(Dispatchers.Main) {
                    ai.addSharedFiles(listOf(uri), "attachment-owner", original.conversationId)
                }
                assertFalse("No successful import may be reported before the draft is saved", imported)
                assertTrue(ai.state.value.historyError)
                assertTrue(ai.canRetryHistorySave)
                assertFalse(ai.state.value.imagesLoading)
                assertFalse(ai.state.value.permissionsSaving)
                val attachment = ai.state.value.fileAttachments.single()
                val references = ai.state.value.references
                assertEquals(attachment, references.single().attachment)
                assertEquals(listOf(attachment.id), ai.state.value.fileDraftIds)
                val archived = File(isolated.noBackupFilesDir, "ai-files-v1/${attachment.id}.bin")
                assertTrue("Copied bytes must remain available after history save fails", archived.isFile)
                assertEquals(source.length(), archived.length())
                assertTrue(withContext(Dispatchers.IO) { AiHistoryStore(File(fixture.root, "history"))
                    .load("attachment-owner").state!!.fileAttachments.isEmpty() })

                withContext(Dispatchers.Main) { ai.select("another-workspace") }
                ready(ai, "another-workspace")
                assertTrue(ai.state.value.references.isEmpty())
                assertTrue(ai.state.value.fileAttachments.isEmpty())
                withContext(Dispatchers.Main) { ai.select("attachment-owner") }
                ready(ai, "attachment-owner")
                assertEquals(original.conversationId, ai.state.value.conversationId)
                assertEquals(references, ai.state.value.references)
                assertEquals(listOf(attachment), ai.state.value.fileAttachments)
                assertEquals(listOf(attachment.id), ai.state.value.fileDraftIds)
                assertTrue("Returning must not clear the owning save failure", ai.state.value.historyError)
                assertTrue(ai.canRetryHistorySave)
                assertTrue(archived.isFile)

                failSaves.set(false)
                val retry = withContext(Dispatchers.Main) { ai.retryHistorySave() }
                assertNotNull("The original failed draft must remain retryable", retry)
                retry!!.join()
                await { !ai.state.value.historyError && !ai.state.value.permissionsSaving }
                assertFalse(ai.canRetryHistorySave)
                val disk = withContext(Dispatchers.IO) { AiHistoryStore(File(fixture.root, "history")).load("attachment-owner").state!! }
                assertEquals(listOf(attachment), disk.fileAttachments)
                assertEquals(listOf(attachment.id), disk.fileDraftIds)

                withContext(Dispatchers.Main) { ai.stop(); restarted = controller().also { it.select("attachment-owner") } }
                val reopened = requireNotNull(restarted)
                ready(reopened, "attachment-owner")
                assertFalse(reopened.state.value.historyError)
                assertEquals(original.conversationId, reopened.state.value.conversationId)
                assertEquals(listOf(attachment), reopened.state.value.fileAttachments)
                assertEquals(references, reopened.state.value.references)
                assertEquals(listOf(attachment.id), reopened.state.value.fileDraftIds)
                val tail = AiFileAttachmentStore(isolated).read(attachment, content.indexOf("complete attachment tail"), 100)
                assertEquals("complete attachment tail\n", tail.getString("content"))
                assertEquals(content, withContext(Dispatchers.IO) { source.readText() })
                assertEquals(0, fixture.server.requestCount)
            } finally {
                withContext(Dispatchers.Main) { first?.stop(); restarted?.stop() }
                withContext(Dispatchers.IO) { sourceRoot.deleteRecursively() }
            }
        }
    }

    @Test(timeout = 120000) fun failedRemovalOfLastDraftAttachmentSurvivesWorkspaceSwitchAndRetriesEmptyLedger() = runBlocking {
        AiDeviceTest.Fixture(base).use { fixture ->
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(fixture.root, "removal-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "removal-private").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("${fixture.token}-removal-$name", mode)
            }
            val failSaves = AtomicBoolean(false)
            val history = object : AiHistoryStore(File(fixture.root, "history")) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    if (failSaves.get() && projectId == "removal-owner") throw IOException("fixture removal write unavailable")
                    return super.save(projectId, state)
                }
            }
            val original = AiState()
            withContext(Dispatchers.IO) {
                history.save("removal-owner", original)
                history.save("removal-other", AiState())
            }
            val sourceRoot = File(base.cacheDir, "apk-install/${fixture.token}-removal-recovery").apply { check(mkdirs()) }
            val content = "The shared original must remain unchanged after removing the draft.\n"
            val source = File(sourceRoot, "remove-draft.txt").apply { writeText(content) }
            val uri = FileProvider.getUriForFile(base, "${base.packageName}.apk-files", source)
            var controller: AiController? = null
            try {
                val ai = withContext(Dispatchers.Main) {
                    AiController(isolated, { project -> File(fixture.root, "project-$project").apply { mkdirs() } },
                        { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {}, fixture.transport, fixture.config,
                        history = history).also { controller = it; it.select("removal-owner") }
                }
                ready(ai, "removal-owner")
                assertTrue(withContext(Dispatchers.Main) { ai.addSharedFiles(listOf(uri), "removal-owner", original.conversationId) })
                val attachment = ai.state.value.fileAttachments.single()
                val archived = File(isolated.noBackupFilesDir, "ai-files-v1/${attachment.id}.bin")
                assertTrue(archived.isFile)
                assertEquals(listOf(attachment.id), ai.state.value.fileDraftIds)
                assertEquals(listOf(attachment), withContext(Dispatchers.IO) { history.load("removal-owner").state!!.fileAttachments })

                failSaves.set(true)
                withContext(Dispatchers.Main) { ai.removeReference(0) }
                await { ai.state.value.historyError && !ai.state.value.permissionsSaving && !ai.state.value.imagesLoading }
                assertTrue(ai.canRetryHistorySave)
                assertTrue(ai.state.value.references.isEmpty())
                assertTrue(ai.state.value.fileDraftIds.isEmpty())
                assertTrue(ai.state.value.fileAttachments.isEmpty())
                assertTrue("A failed deletion save must retain bytes still owned by disk history", archived.isFile)

                withContext(Dispatchers.Main) { ai.select("removal-other") }
                ready(ai, "removal-other")
                assertTrue(ai.state.value.references.isEmpty())
                withContext(Dispatchers.Main) { ai.select("removal-owner") }
                ready(ai, "removal-owner")
                assertEquals(original.conversationId, ai.state.value.conversationId)
                assertTrue("The removed draft must not reappear from the older disk snapshot", ai.state.value.references.isEmpty())
                assertTrue(ai.state.value.fileDraftIds.isEmpty())
                assertTrue("An empty unsaved ledger must remain authoritative", ai.state.value.fileAttachments.isEmpty())
                assertTrue(ai.state.value.historyError)
                assertTrue(ai.canRetryHistorySave)
                assertTrue(archived.isFile)
                assertEquals(listOf(attachment), withContext(Dispatchers.IO) {
                    AiHistoryStore(File(fixture.root, "history")).load("removal-owner").state!!.fileAttachments
                })

                failSaves.set(false)
                val retry = withContext(Dispatchers.Main) { ai.retryHistorySave() }
                assertNotNull("An empty failed ledger must remain retryable", retry)
                retry!!.join()
                await { !ai.state.value.historyError && !ai.state.value.permissionsSaving }
                assertFalse(ai.canRetryHistorySave)
                val disk = withContext(Dispatchers.IO) { AiHistoryStore(File(fixture.root, "history")).load("removal-owner").state!! }
                assertTrue(disk.fileAttachments.isEmpty())
                assertTrue(disk.fileDraftIds.isEmpty())
                assertTrue(disk.timeline.isEmpty())
                assertEquals(content, withContext(Dispatchers.IO) { source.readText() })
                assertEquals(0, fixture.server.requestCount)
            } finally {
                withContext(Dispatchers.Main) { controller?.stop() }
                withContext(Dispatchers.IO) { sourceRoot.deleteRecursively() }
            }
        }
    }

}

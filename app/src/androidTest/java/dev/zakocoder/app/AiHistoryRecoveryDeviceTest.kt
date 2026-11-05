package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Real AtomicFile records in a disposable directory; never inspects installed user history. */
class AiHistoryRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun project(directory: File, id: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun failure(block: () -> Unit): AiHistoryProblem {
        try { block(); fail("Expected typed unreadable history") } catch (error: AiHistoryReadFailure) { return error.problem }
        error("unreachable")
    }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(20000) { while (!predicate()) delay(30) }

    @Test fun identifiesUnsupportedAndCorruptRecordsAndDeletesOnlyConfirmedRecord() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "history-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = AiHistoryStore(directory)
            val good = AiState()
            val bad = AiState()
            store.save("project", good); store.save("project", bad)
            val goodFile = File(project(directory, "project"), "${good.conversationId}.json")
            val badFile = File(project(directory, "project"), "${bad.conversationId}.json")
            val expectedGood = goodFile.readBytes()
            val source = File(directory, "source.txt").apply { writeText("user source stays") }
            val validBad = badFile.readText()
            badFile.writeText(JSONObject(validBad).put("schemaVersion", 99).toString())
            val unsupported = failure { store.load("project") }
            assertEquals(AiHistoryProblem(bad.conversationId, AiHistoryProblem.Reason.Unsupported), unsupported)
            assertArrayEquals(expectedGood, goodFile.readBytes()); assertTrue(badFile.exists())
            store.deleteUnreadable("project", unsupported)
            assertFalse(badFile.exists()); assertArrayEquals(expectedGood, goodFile.readBytes())
            assertEquals(good.conversationId, store.load("project").state!!.conversationId)
            assertEquals("user source stays", source.readText())
            val corrupt = AiState()
            store.save("project", corrupt)
            val corruptFile = File(project(directory, "project"), "${corrupt.conversationId}.json")
            corruptFile.writeText("{incomplete")
            assertEquals(AiHistoryProblem(corrupt.conversationId, AiHistoryProblem.Reason.Corrupt), failure { store.load("project") })
            assertTrue(corruptFile.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesHealthyReplacementsAmbiguousNamesAndIoFailures() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "history-safe-delete-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val store = AiHistoryStore(directory)
            val state = AiState(); store.save("project", state)
            val file = File(project(directory, "project"), "${state.conversationId}.json")
            val valid = file.readBytes()
            file.writeText("{broken")
            val problem = failure { store.load("project") }
            file.writeBytes(valid)
            try { store.deleteUnreadable("project", problem); fail("A repaired record must not be deleted") }
            catch (_: IllegalStateException) { }
            assertArrayEquals(valid, file.readBytes())
            val unknown = File(file.parentFile, "not-a-uuid.json").apply { writeText("{broken") }
            val unidentified = failure { store.load("project") }
            assertNull(unidentified.conversationId)
            try { store.deleteUnreadable("project", unidentified); fail("Unknown names must not be deleted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(unknown.exists()); assertTrue(file.exists())
            unknown.delete()
            file.delete(); assertTrue(file.mkdir())
            try { store.load("project"); fail("A directory cannot be read as a session") }
            catch (error: Exception) {
                assertFalse("IO is not classified as corrupt", error is AiHistoryReadFailure)
                assertTrue(error is IOException)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test(timeout = 90000) fun controllerRequiresExactProblemAndRecoversOtherSessionWithoutNetwork() = runBlocking {
        AiDeviceTest.Fixture(context).use { fixture ->
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            }
            val good = AiState(); val bad = AiState()
            withContext(Dispatchers.IO) {
                fixture.history.save("project", good); fixture.history.save("project", bad)
                File(project(File(fixture.root, "history"), "project"), "${bad.conversationId}.json").writeText("{broken")
            }
            lateinit var ai: AiController
            main {
                ai = AiController(isolated, { fixture.root }, { _, _ -> false }, { _, _ -> error("No Shell") }, {}, {},
                    fixture.transport, fixture.config, history = fixture.history)
                ai.select("project")
            }
            try {
                await { !ai.state.value.permissionsLoading && ai.state.value.historyError }
                val problem = requireNotNull(ai.state.value.historyProblem)
                assertEquals(bad.conversationId, problem.conversationId)
                assertEquals(context.getString(R.string.ai_history_read_corrupt), ai.state.value.error)
                main {
                    assertFalse(ai.recoverUnreadableConversation(problem.copy(conversationId = good.conversationId)))
                    assertTrue(ai.recoverUnreadableConversation(problem))
                }
                await { !ai.state.value.permissionsLoading && !ai.state.value.historyError }
                assertEquals(good.conversationId, ai.state.value.conversationId)
                assertNull(ai.state.value.historyProblem)
                assertEquals(AiPermission.Ask, ai.state.value.policy.permission(AiTool.WriteFile))
                assertEquals(0, fixture.server.requestCount)
                assertEquals(good.conversationId, withContext(Dispatchers.IO) { fixture.history.load("project").state!!.conversationId })
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun deletingLastUnreadableSessionCreatesOneFreshSessionAndKeepsCheckpoints() = runBlocking {
        AiDeviceTest.Fixture(context).use { fixture ->
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            }
            val bad = AiState()
            val checkpoint = withContext(Dispatchers.IO) {
                fixture.history.save("project", bad)
                File(project(File(fixture.root, "history"), "project"), "${bad.conversationId}.json").writeText("{broken")
                File(fixture.history.checkpointDirectory, "keep.txt").apply { parentFile!!.mkdirs(); writeText("keep checkpoint") }
            }
            lateinit var ai: AiController
            main {
                ai = AiController(isolated, { fixture.root }, { _, _ -> false }, { _, _ -> error("No Shell") }, {}, {},
                    fixture.transport, fixture.config, history = fixture.history)
                ai.select("project")
            }
            try {
                await { !ai.state.value.permissionsLoading && ai.state.value.historyError }
                main { assertTrue(ai.recoverUnreadableConversation(requireNotNull(ai.state.value.historyProblem))) }
                await { !ai.state.value.permissionsLoading && !ai.state.value.historyError }
                assertNotEquals(bad.conversationId, ai.state.value.conversationId)
                assertTrue(ai.state.value.timeline.isEmpty())
                assertEquals(1, ai.state.value.conversations.size)
                withContext(Dispatchers.IO) {
                    assertEquals("keep checkpoint", checkpoint.readText())
                    assertEquals(ai.state.value.conversationId, fixture.history.load("project").state!!.conversationId)
                }
                assertEquals(0, fixture.server.requestCount)
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun controllerDoesNotOfferDeletionOnPermissionOrIoFailure() = runBlocking {
        AiDeviceTest.Fixture(context).use { fixture ->
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            }
            val histories = listOf(
                object : AiHistoryStore(File(fixture.root, "permission")) { override fun load(projectId: String, conversation: String?): Loaded = throw SecurityException("fixture") },
                object : AiHistoryStore(File(fixture.root, "io")) { override fun load(projectId: String, conversation: String?): Loaded = throw IOException("fixture") })
            for (store in histories) {
                lateinit var ai: AiController
                main {
                    ai = AiController(isolated, { fixture.root }, { _, _ -> false }, { _, _ -> error("No Shell") }, {}, {},
                        fixture.transport, fixture.config, history = store)
                    ai.select("project")
                }
                try {
                    await { !ai.state.value.permissionsLoading && ai.state.value.historyError }
                    assertNull(ai.state.value.historyProblem)
                    assertEquals(context.getString(R.string.ai_history_read_unavailable), ai.state.value.error)
                    main { assertFalse(ai.recoverUnreadableConversation(AiHistoryProblem(UUID.randomUUID().toString(), AiHistoryProblem.Reason.Corrupt))) }
                    assertEquals(0, fixture.server.requestCount)
                } finally { main { ai.stop() } }
            }
        }
    }
}

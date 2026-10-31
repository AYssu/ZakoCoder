package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Isolated stores and local TLS; controlled interleavings never touch an actual project or provider. */
class AiSessionOwnershipDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(15) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.models.state.value.loading && !ai.state.value.permissionsLoading &&
            !ai.state.value.permissionsSaving && !ai.state.value.skillsLoading
    }
    private fun isolated(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "owner-files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "owner-private").apply { mkdirs() }
    }
    private fun controller(f: AiDeviceTest.Fixture, history: AiHistoryStore = f.history): AiController {
        val context = isolated(f)
        return AiController(context, { id -> File(f.root, "project-$id").apply { mkdirs() } },
            { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {}, f.transport, f.config, history = history)
    }

    @Test(timeout = 90000) fun currentModelCapacitySurvivesProjectConversationAndDeletedHistoryBoundaries() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val profile = f.config.catalog().active!!
            withContext(Dispatchers.IO) {
                f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                    parameters = AiParameters(maxTokens = 4096), context = AiContextOptions(contextWindowTokens = 1050000)))
            }
            // Historical budgets belong to old requests, never to the currently selected model.
            val a = AiState(contextWindowTokens = 32000, contextOutputReserveTokens = 1024)
            val b = AiState(contextWindowTokens = 64000, contextOutputReserveTokens = 2048)
            withContext(Dispatchers.IO) { f.history.save("a", a); f.history.save("a", b) }
            lateinit var ai: AiController
            main { ai = controller(f) }; ready(ai)
            fun capacity() {
                assertEquals(1050000, ai.state.value.contextWindowTokens)
                assertEquals(4096, ai.state.value.contextOutputReserveTokens)
            }
            capacity()
            main { ai.select("a") }; ready(ai); capacity()
            main { ai.conversation(a.conversationId) }; ready(ai); capacity()
            main { ai.conversation() }; ready(ai); capacity()
            val fresh = ai.state.value.conversationId
            main { ai.deleteConversation(fresh) }; ready(ai); capacity()
            main { ai.select("b") }; ready(ai); capacity()
            main { ai.select("a") }; ready(ai); capacity()
            withContext(Dispatchers.Main) { ai.deleteProjectHistory("a") }; capacity()
            assertNull(ai.state.value.projectId)
            main { ai.select("a") }; ready(ai); capacity()
            assertTrue(ai.state.value.conversations.isEmpty())
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test(timeout = 90000) fun queuedSaveCannotResurrectDeletedProjectOrSaveAnotherOwner() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(false)
            val blocked = AtomicBoolean(false)
            val saves = AtomicInteger()
            val directory = File(f.root, "owner-history")
            val history = object : AiHistoryStore(directory) {
                override fun save(projectId: String, state: AiState): List<AiConversation> {
                    saves.incrementAndGet()
                    if (armed.get() && blocked.compareAndSet(false, true)) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                    return super.save(projectId, state)
                }
            }
            val original = AiState()
            withContext(Dispatchers.IO) { history.save("deleted", original) }
            lateinit var ai: AiController
            main { ai = controller(f, history); ai.select("deleted") }; ready(ai)
            val before = saves.get()
            var deletion: Deferred<Unit>? = null
            try {
                armed.set(true)
                main { ai.recommendedSkills() }
                withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
                // Queue a second persist, then invalidate this owner before either can finish.
                main { ai.recommendedSkills() }
                deletion = async(Dispatchers.Main) { ai.deleteProjectHistory("deleted") }
                main { /* Wait until the earlier queued deletion has invalidated this generation. */ }
                release.countDown()
                deletion.await()
                assertNull(ai.state.value.projectId)
                assertEquals("Queued old generation must not be written", before + 1, saves.get())
                assertTrue(withContext(Dispatchers.IO) { AiHistoryStore(directory).load("deleted").conversations.isEmpty() })
                main { ai.select("next") }; ready(ai)
                assertEquals("next", ai.state.value.projectId)
                assertTrue(ai.state.value.conversations.isEmpty())
                assertEquals(0, f.server.requestCount)
            } finally { release.countDown(); deletion?.cancelAndJoin() }
        }
    }

    @Test(timeout = 90000) fun historicalPermissionsNeverOverrideCurrentProjectDenialAfterReloadOrFork() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val context = isolated(f)
            val policy = AiPolicy(AiPermission.Deny, emptyMap())
            val legacyAllow = AiState(turn = 1, policy = AiPolicy(AiPermission.Allow, emptyMap()),
                timeline = listOf(AiEntry(id = "historical-user", role = AiRole.User, text = "old request", turn = 1),
                    AiEntry(id = "historical-assistant", role = AiRole.Assistant, text = "old response", turn = 1)))
            withContext(Dispatchers.IO) {
                AiPermissionStore(context).save("denied", policy)
                f.history.save("denied", legacyAllow)
            }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("denied") }; ready(ai)
            assertEquals(policy, ai.state.value.policy)
            assertFalse(ai.state.value.active)
            assertNull(ai.state.value.approval)
            assertTrue(withContext(Dispatchers.Main) { ai.forkConversation(legacyAllow.conversationId, 1) })
            ready(ai)
            assertEquals(policy, ai.state.value.policy)
            main { ai.conversation(legacyAllow.conversationId) }; ready(ai)
            assertEquals(policy, ai.state.value.policy)
            f.enqueue(calls = listOf(AiCall("denied-write", "write_file", JSONObject()
                .put("path", "must-not-exist.txt").put("content", "never write").toString())))
            f.enqueue("Denied operation acknowledged")
            try {
                main { assertTrue(ai.send("Propose a file write")) }; await { !ai.state.value.active }
                assertNull(ai.state.value.approval)
                assertEquals(AiToolStatus.Denied, ai.state.value.events.single { it.callId == "denied-write" }.status)
                assertFalse(File(f.root, "project-denied/must-not-exist.txt").exists())
                assertEquals(2, f.server.requestCount)
            } finally { main { ai.stop() }; await { !ai.state.value.active } }
        }
    }
    @Test(timeout = 90000) fun activeTaskKeepsProviderSnapshotAndNextTaskReadsUpdatedConfiguration() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val first = f.config.catalog().active!!
            val original = AiModelDraft(first.id, first.name, first.endpoint, "snapshot-before", null,
                parameters = AiParameters(maxTokens = 2048, temperature = 0.2),
                context = AiContextOptions(contextWindowTokens = 64000))
            withContext(Dispatchers.IO) { f.config.saveProfile(original) }
            val commands = AtomicInteger()
            lateinit var ai: AiController
            val context = isolated(f)
            main {
                ai = AiController(context, { File(f.root, "snapshot-project").apply { mkdirs() } },
                    { _, _ -> false }, { _, _ -> commands.incrementAndGet(); dev.zakocoder.app.runtime.CommandResult(0, "fixture result") },
                    {}, {}, f.transport, f.config, history = f.history)
                ai.select("snapshot")
            }
            ready(ai)
            f.enqueue(calls = listOf(AiCall("snapshot-command", "run_command", JSONObject().put("command", "fixture_only").toString())))
            f.enqueue("First task completed")
            try {
                main { assertTrue(ai.send("Run the approved fixture operation")) }
                await { ai.state.value.approval != null }
                val updated = original.copy(model = "snapshot-after", parameters = AiParameters(maxTokens = 4096, temperature = 0.7),
                    context = AiContextOptions(contextWindowTokens = 256000))
                // A private test store emulates an external configuration update while a tool waits.
                // The public editor remains locked for active tasks.
                withContext(Dispatchers.IO) { f.config.saveProfile(updated) }
                main { ai.models.save(original.copy(model = "must-not-be-saved")); ai.approve(AiDecision.Once) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(1, commands.get())
                repeat(2) {
                    val body = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertEquals("snapshot-before", body.getString("model"))
                    assertEquals(2048, body.getInt("max_tokens"))
                    assertEquals(0.2, body.getDouble("temperature"), 0.0)
                }
                assertEquals(64000, ai.state.value.contextWindowTokens)
                assertEquals("snapshot-after", withContext(Dispatchers.IO) { f.config.load().model })
                f.enqueue("Next task uses new configuration")
                main { assertTrue(ai.send("Continue in a new task")) }; await { !ai.state.value.active }
                val next = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                assertEquals("snapshot-after", next.getString("model"))
                assertEquals(4096, next.getInt("max_tokens"))
                assertEquals(0.7, next.getDouble("temperature"), 0.0)
                assertEquals(256000, ai.state.value.contextWindowTokens)
                assertEquals(1, commands.get())
            } finally { main { ai.stop() }; await { !ai.state.value.active } }
        }
    }

}

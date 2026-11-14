package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises the real request and permission boundary against isolated local TLS fixtures. */
class AiMemoryIntegrationDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(20) }
    private fun context(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun controller(f: AiDeviceTest.Fixture) = AiController(context(f),
        { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
        { _, _ -> error("No commands expected") }, {}, {}, f.transport, f.config, history = f.history)
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.restoring && !ai.state.value.permissionsLoading &&
            !ai.state.value.permissionsSaving && !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private suspend fun done(ai: AiController) {
        await { !ai.state.value.active }; assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
    }

    @Test(timeout = 120000) fun automaticMemoryRespectsAllowAskDenyAndDoesNotRewriteSystemPrompt() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("memory-policy") }; ready(ai)
            ai.memory.add("Coding preference", "CONFIRMED_MEMORY_SENTINEL use Kotlin", core = true).join()
            var system: String? = null
            for (permission in listOf(AiPermission.Allow, AiPermission.Ask, AiPermission.Deny)) {
                main { ai.setPolicy(AiPolicy(AiPermission.Ask, mapOf(AiTool.SearchMemory to permission))) }; ready(ai)
                // A new conversation must not read memory when current permission asks or denies.
                main { ai.conversation() }; ready(ai)
                f.enqueue("Fixture answer")
                main { assertTrue(ai.send("What language should I use?")) }; done(ai)
                val body = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val messages = body.getJSONArray("messages")
                assertFalse(messages.toString().contains("zako_memory_snapshot"))
                val currentSystem = messages.getJSONObject(0).getString("content")
                assertFalse(currentSystem.contains("CONFIRMED_MEMORY_SENTINEL"))
                if (system == null) system = currentSystem.substringBefore("Current app permissions:")
                assertEquals(system, currentSystem.substringBefore("Current app permissions:"))
                assertEquals(permission == AiPermission.Allow, messages.toString().contains("CONFIRMED_MEMORY_SENTINEL"))
            }
        }
    }

    @Test(timeout = 90000) fun modelProposalIsRecordedButCannotActivateItself() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("memory-proposal") }; ready(ai)
            main { ai.setPolicy(AiPolicy(AiPermission.Allow)) }; ready(ai)
            f.enqueue(calls = listOf(AiCall("remember", "propose_memory", JSONObject()
                .put("title", "Formatting").put("content", "Use four spaces").toString())))
            f.enqueue("Waiting for your memory confirmation")
            main { assertTrue(ai.send("Suggest a formatting preference")) }; done(ai)
            val proposal = ai.memory.state.value.entries.single()
            assertTrue(proposal.pending); assertFalse(proposal.enabled)
            assertTrue(ai.memory.searchFor("Formatting").isEmpty())
            assertEquals(AiToolStatus.Succeeded, ai.state.value.events.single().status)
            assertTrue(ai.state.value.events.single().output.contains("pending_confirmation"))
            ai.memory.acceptProposal(proposal.id).join()
            assertEquals("Use four spaces", ai.memory.searchFor("Formatting").single().content)
        }
    }

    @Test(timeout = 90000) fun corruptOptionalMemoryWarnsAndPreservesDataWhileCodingContinues() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val damaged = File(context(f).noBackupFilesDir, "agent-memory/v1.json").apply {
                parentFile!!.mkdirs(); writeText("damaged-memory-fixture")
            }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("memory-damaged") }; ready(ai)
            f.enqueue("Ordinary coding remains available")
            main { assertTrue(ai.send("Explain this project")) }; done(ai)
            assertEquals("damaged-memory-fixture", damaged.readText())
            assertTrue(ai.state.value.timeline.any { it.text.contains(base.getString(R.string.memory_task_skipped)) })
        }
    }
}

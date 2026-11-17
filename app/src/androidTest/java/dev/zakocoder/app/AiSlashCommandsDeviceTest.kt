package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local TLS responses and isolated storage only; no user providers or guest processes. */
class AiSlashCommandsDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(20) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.permissionsSaving &&
            !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private fun context(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun seed() = AiState(turn = 1,
        timeline = listOf(AiEntry("user", "Fix the example", role = AiRole.User, turn = 1),
            AiEntry("assistant", "Inspected example; tests not run", role = AiRole.Assistant, turn = 1)),
        contextTurns = listOf(JSONArray().put(JSONObject().put("role", "user").put("content", "Fix the example"))
            .put(JSONObject().put("role", "assistant").put("content", "Inspected example; tests not run")).toString()))

    @Test(timeout = 120000) fun manualCompactPersistsContextWithoutDeletingHistoryOrCreatingTaskTurn() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val initial = seed()
            withContext(Dispatchers.IO) { f.history.save("slash", initial) }
            var ai: AiController? = null
            try {
                main { ai = AiController(context(f), { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("Manual compression must never run commands") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("slash") }
                val controller = requireNotNull(ai)
                ready(controller)
                val before = controller.state.value
                f.enqueue("Goal: fix the example. Inspection completed; tests remain pending.", responseDelayMillis = 200)
                main {
                    assertTrue(controller.compactConversation("Preserve the pending tests"))
                    assertFalse(controller.send("Must not race maintenance"))
                    assertFalse(controller.compactConversation())
                }
                await { !controller.state.value.active }
                val state = controller.state.value
                assertEquals(AiPhase.Completed, state.phase)
                assertEquals(before.turn, state.turn)
                assertEquals(before.timeline, state.timeline)
                assertEquals(before.checkpointId, state.checkpointId)
                assertTrue(state.contextTurns.isEmpty())
                assertTrue(state.contextSummary.contains("tests remain pending"))
                assertEquals(before.contextCompactions + 1, state.contextCompactions)
                assertTrue(state.requests.single().compaction)
                val request = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                assertEquals(0, request.optJSONArray("tools")?.length() ?: 0)
                assertTrue(request.getJSONArray("messages").toString().contains("Preserve the pending tests"))
                val restored = withContext(Dispatchers.IO) { requireNotNull(f.history.load("slash").state) }
                assertEquals(state.contextSummary, restored.contextSummary)
                assertEquals(before.timeline, restored.timeline)
                assertEquals(before.turn, restored.turn)
                assertTrue(withContext(Dispatchers.Main) { controller.renameConversation("Renamed chat") })
                val renamed = withContext(Dispatchers.IO) { f.history.load("slash") }
                assertEquals("Renamed chat", renamed.conversations.single().title)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 120000) fun failedAndCancelledCompactKeepOriginalContext() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val initial = seed()
            withContext(Dispatchers.IO) { f.history.save("slash", initial) }
            var ai: AiController? = null
            try {
                main { ai = AiController(context(f), { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("No commands") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("slash") }
                val controller = requireNotNull(ai)
                ready(controller)
                f.enqueueResponse(MockResponse().setResponseCode(400).setBody("fixture rejection"))
                main { assertTrue(controller.compactConversation()) }
                await { !controller.state.value.active }
                assertEquals(AiPhase.Failed, controller.state.value.phase)
                assertEquals(initial.contextTurns, controller.state.value.contextTurns)
                assertEquals(initial.contextSummary, controller.state.value.contextSummary)
                f.server.takeRequest(5, TimeUnit.SECONDS)
                f.enqueue("Must not commit after stop", responseDelayMillis = 3000)
                main { assertTrue(controller.compactConversation()) }
                assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(5, TimeUnit.SECONDS) })
                main { controller.stop() }
                await { !controller.state.value.active }
                assertEquals(AiPhase.Cancelled, controller.state.value.phase)
                val restored = withContext(Dispatchers.IO) { requireNotNull(f.history.load("slash").state) }
                assertEquals(initial.contextTurns, restored.contextTurns)
                assertEquals(initial.timeline, restored.timeline)
                assertEquals(0, restored.contextCompactions)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 120000) fun explicitSkillLoadsBodyForOneTaskWithoutChangingGlobalSelection() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val isolated = context(f)
            val skills = AiSkills(AiProjectFiles { File(isolated.filesDir, "shared-skills") })
            val selected = withContext(Dispatchers.IO) { skills.create("", "slash-only", "Use only for the slash fixture", "EXPLICIT_SKILL_BODY_MARKER") }
            val global = AiGlobalSkills(isolated)
            withContext(Dispatchers.IO) { global.save(AiGlobalSkills.Selection(emptyList(), onDemand = true)) }
            var ai: AiController? = null
            try {
                main { ai = AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                    { _, _ -> error("No commands") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("slash") }
                val controller = requireNotNull(ai)
                ready(controller)
                f.enqueue("Applied the selected skill")
                main { assertTrue(controller.sendWithSkill("Inspect the fixture", selected.path)) }
                await { !controller.state.value.active }
                val first = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                assertTrue(first.getJSONArray("messages").toString().contains("EXPLICIT_SKILL_BODY_MARKER"))
                assertTrue(first.getJSONArray("messages").toString().contains("user explicitly selected"))
                assertEquals(emptyList<String>(), withContext(Dispatchers.IO) { global.load().enabled })
                assertTrue(withContext(Dispatchers.IO) { global.load().onDemand })
                f.enqueue("An ordinary task")
                main { assertTrue(controller.send("Another task")) }
                await { !controller.state.value.active }
                assertTrue(controller.state.value.enabledSkills.isEmpty())
                assertTrue(controller.state.value.skillOnDemand)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
}

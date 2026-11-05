package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller + file/checkpoint/history adapters, isolated local model; no user configuration. */
class AgentRunnerControllerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30000) { while (!condition()) delay(20) }

    @Test(timeout = 90000) fun writtenFileAndEvidenceSurviveModelFailureAndReloadWithoutReplayingWrite() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 1).use { f ->
            val changed = AtomicInteger()
            val project = File(f.root, "project").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            fun controller() = AiController(isolated, { project }, { _, _ -> false },
                { _, _ -> error("No shell expected") }, {}, { changed.incrementAndGet() }, f.transport, f.config, history = f.history)
            suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            f.enqueue(calls = listOf(AiCall("write-once", "write_file", JSONObject()
                .put("path", "result.txt").put("content", "AGENT_RUNNER_PERSISTED_RESULT").put("reason", "Save result once").toString())))
            f.server.enqueue(MockResponse().setResponseCode(503))
            lateinit var original: AiController
            main { original = controller(); original.select("runner-recovery") }
            ready(original)
            main { assertTrue(original.send("Write result.txt once, then verify it")) }
            await { original.state.value.approval != null }
            main { original.approve(AiDecision.Once) }
            await { !original.state.value.active }
            assertEquals(AiPhase.Failed, original.state.value.phase)
            assertEquals("AGENT_RUNNER_PERSISTED_RESULT", File(project, "result.txt").readText())
            assertEquals(1, changed.get())
            assertEquals(1, original.state.value.events.count { it.callId == "write-once" && it.status == AiToolStatus.Succeeded })
            assertTrue(original.state.value.recoveryNotes.joinToString().contains("result.txt"))
            val before = original.state.value.conversationId

            lateinit var restored: AiController
            main { restored = controller(); restored.select("runner-recovery") }
            ready(restored)
            assertEquals(before, restored.state.value.conversationId)
            assertEquals(1, restored.state.value.events.count { it.callId == "write-once" && it.status == AiToolStatus.Succeeded })
            assertNull(restored.state.value.approval)
            f.enqueue(calls = listOf(AiCall("inspect", "read_file", JSONObject().put("path", "result.txt").toString())))
            f.enqueue("Inspected the saved result without repeating the write.")
            main { assertTrue(restored.send("Continue by inspecting the existing file")) }
            await { !restored.state.value.active }
            assertEquals(AiPhase.Completed, restored.state.value.phase)
            assertEquals(1, changed.get())
            assertEquals("AGENT_RUNNER_PERSISTED_RESULT", File(project, "result.txt").readText())
            assertEquals(1, restored.state.value.events.count { it.callId == "write-once" && it.status == AiToolStatus.Succeeded })
            assertEquals(AiToolStatus.Succeeded, restored.state.value.events.single { it.callId == "inspect" }.status)
            val requests = (1..4).map { JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()) }
            val messages = requests[1].getJSONArray("messages")
            val resultIndex = (0 until messages.length()).single { messages.getJSONObject(it).optString("tool_call_id") == "write-once" }
            assertTrue(messages.getJSONObject(resultIndex).getString("content").contains("result.txt"))
            assertTrue(requests.last().getJSONArray("messages").toString().contains("AGENT_RUNNER_PERSISTED_RESULT"))
        }
    }
}

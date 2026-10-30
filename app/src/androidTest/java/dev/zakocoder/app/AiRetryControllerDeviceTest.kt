package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated HTTPS model and synthetic command callback; retries never replay executed tools. */
class AiRetryControllerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(20) }
    private fun controller(f: AiDeviceTest.Fixture, commands: AtomicInteger = AtomicInteger()): AiController {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
        }
        return AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> commands.incrementAndGet(); CommandResult(0, "SIDE_EFFECT_EXECUTED_ONCE") }, {}, {},
            f.transport, f.config, history = f.history)
    }
    private suspend fun ready(ai: AiController) {
        await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
    }

    @Test(timeout = 90000) fun retriedModelRequestReplacesPartialWithoutRepeatingPriorToolOrApproval() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            val commands = AtomicInteger()
            f.enqueue(calls = listOf(AiCall("once", "run_command", JSONObject()
                .put("command", "fixture side effect").put("reason", "Run once").toString())))
            f.enqueue("DISCARDED_PARTIAL", done = false)
            f.enqueue("SUCCESSFUL_REPLACEMENT")
            lateinit var ai: AiController
            main { ai = controller(f, commands); ai.select("retry-tool-once") }; ready(ai)
            main { assertTrue(ai.send("Run once then explain")) }
            await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.Once) }
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(1, commands.get()); assertEquals(1, ai.state.value.toolCount)
            assertEquals(1, ai.state.value.events.count { it.callId == "once" && it.status == AiToolStatus.Succeeded })
            assertNull(ai.state.value.approval)
            val visible = ai.state.value.timeline.filter { it.role == AiRole.Assistant }.joinToString { it.text }
            assertTrue(visible.contains("SUCCESSFUL_REPLACEMENT")); assertFalse(visible.contains("DISCARDED_PARTIAL"))
            assertEquals("", ai.state.value.retryNotice)
            assertEquals(3, f.server.requestCount)
            val bodies = (1..3).map { JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()) }
            val retriedMessages = bodies.drop(1).map { it.getJSONArray("messages").toString() }
            assertEquals(retriedMessages[0], retriedMessages[1])
            assertTrue(retriedMessages[1].contains("SIDE_EFFECT_EXECUTED_ONCE"))
            assertFalse(retriedMessages[1].contains("DISCARDED_PARTIAL"))
            assertTrue(ai.state.value.requests.any { it.complete && it.failure != null })
            assertEquals(3, ai.state.value.requests.size)
        }
    }

    @Test(timeout = 90000) fun finalFailureWithoutNewOutputKeepsPartialAndRecoveryAcrossRestart() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            f.enqueue("LAST_OBSERVED_PARTIAL", done = false)
            repeat(2) { f.server.enqueue(MockResponse().setResponseCode(503)) }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("retry-failure") }; ready(ai)
            main { assertTrue(ai.send("Remember this incomplete task")) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Failed, ai.state.value.phase)
            assertEquals(3, f.server.requestCount)
            assertEquals("", ai.state.value.retryNotice)
            assertTrue(ai.state.value.timeline.any { it.role == AiRole.Assistant && it.text == "LAST_OBSERVED_PARTIAL" })
            assertTrue(ai.state.value.recoveryNotes.isNotEmpty())
            assertEquals(3, ai.state.value.requests.count { it.complete && it.failure != null })
            lateinit var restarted: AiController
            main { restarted = controller(f); restarted.select("retry-failure") }; ready(restarted)
            assertTrue(restarted.state.value.timeline.any { it.text == "LAST_OBSERVED_PARTIAL" })
            assertTrue(restarted.state.value.recoveryNotes.joinToString().contains("Remember this incomplete task"))
            assertEquals(3, restarted.state.value.requests.count { it.complete && it.failure != null })
        }
    }

    @Test(timeout = 90000) fun cancellingBackoffStopsBeforeAnotherNetworkAttempt() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "10"))
            f.enqueue("MUST_NOT_BE_REQUESTED")
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("retry-cancel") }; ready(ai)
            main { assertTrue(ai.send("Wait safely")) }
            await { ai.state.value.retryNotice.isNotBlank() }
            main { ai.stop() }; await { !ai.state.value.active }
            delay(1500)
            assertEquals(AiPhase.Cancelled, ai.state.value.phase)
            assertEquals("", ai.state.value.retryNotice)
            assertEquals(1, f.server.requestCount)
            assertTrue(ai.state.value.timeline.none { it.text.contains("MUST_NOT_BE_REQUESTED") })
            assertTrue(ai.state.value.recoveryNotes.isNotEmpty())
        }
    }
}

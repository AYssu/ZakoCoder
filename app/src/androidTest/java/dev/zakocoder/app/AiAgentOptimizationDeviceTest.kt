package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** End-to-end local TLS/controller/history tests; never installs Ubuntu or uses user model keys. */
class AiAgentOptimizationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(60000) { while (!condition()) delay(20) }
    private class Harness(val fixture: AiDeviceTest.Fixture) {
        val project = File(fixture.root, "project").apply { mkdirs() }
        val isolated = object : ContextWrapper(fixture.context) {
            override fun getFilesDir() = File(fixture.root, "control").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
        }
        val changed = AtomicInteger()
        fun controller() = AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("Shell must never run") }, {},
            { changed.incrementAndGet() }, fixture.transport, fixture.config, history = fixture.history)
    }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.state.value.restoring }
    private fun delegate(vararg roles: String) = AiCall("delegate", "delegate_readonly", JSONObject().put("tasks", JSONArray(roles.map {
        JSONObject().put("role", it).put("task", "Inspect answer.txt and return paths and evidence") })).toString())

    @Test(timeout = 90000) fun disabledAndUnusedDelegationBothKeepSimpleTaskAtOneRequest() = runBlocking {
        for (enabled in listOf(false, true)) AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); lateinit var ai: AiController
            main { ai = h.controller(); ai.select("simple") }; ready(ai)
            f.enqueue("Simple answer")
            main { ai.setAutomaticDelegation(enabled); assertTrue(ai.send("Hello")) }
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(1, f.server.requestCount); assertEquals(1, ai.state.value.taskUsage.requests)
            android.util.Log.i("ZakoAgentBenchmark", "simple_delegation_enabled=$enabled requests=${f.server.requestCount} request_bytes=${ai.state.value.requests.single().requestBytes} estimated_input_tokens=${ai.state.value.requests.single().estimatedContextTokens}")
            val body = JSONObject(f.server.takeRequest()!!.body.readUtf8())
            assertEquals(enabled, body.getJSONArray("tools").toString().contains("delegate_readonly"))
            val loaded = f.history.load("simple").state!!
            assertEquals(enabled, loaded.automaticDelegation)
            assertEquals(1, loaded.taskUsage.requests)
        }
    }
    @Test(timeout = 90000) fun childReadsActualFilesAndParentReceivesFindingsWithUsageAndHistory() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); File(h.project, "answer.txt").writeText("ACTUAL_CHILD_FILE_EVIDENCE")
            lateinit var ai: AiController
            main { ai = h.controller(); ai.select("delegation") }; ready(ai)
            f.enqueue(calls = listOf(delegate("scout")))
            f.enqueue(calls = listOf(AiCall("child-read", "read_file", "{\"path\":\"answer.txt\"}")))
            f.enqueue("answer.txt:1 contains ACTUAL_CHILD_FILE_EVIDENCE")
            f.enqueue("Parent checked findings")
            main { ai.setAutomaticDelegation(true); assertTrue(ai.send("Use scout to inspect the source")) }
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(0, h.changed.get()); assertEquals(4, ai.state.value.taskUsage.requests)
            assertEquals(2, ai.state.value.taskUsage.tools)
            val requests = (1..4).map { JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()) }
            assertTrue(requests[2].getJSONArray("messages").toString().contains("ACTUAL_CHILD_FILE_EVIDENCE"))
            val childSchemas = requests[1].getJSONArray("tools")
            assertEquals(AiSubagents.tools.map { it.wire }.toSet(), (0 until childSchemas.length()).map { childSchemas.getJSONObject(it).getJSONObject("function").getString("name") }.toSet())
            assertTrue(requests[3].getJSONArray("messages").toString().contains("ACTUAL_CHILD_FILE_EVIDENCE"))
            assertEquals(2, ai.state.value.requests.count { it.agent == "1:scout" })
            val loaded = f.history.load("delegation").state!!
            assertEquals(2, loaded.requests.count { it.agent == "1:scout" })
            assertTrue(loaded.events.any { it.callId == "child-read" && it.output.contains("ACTUAL_CHILD_FILE_EVIDENCE") })
        }
    }
    @Test(timeout = 90000) fun forgedChildWriteAndDeniedReadNeverReachFilesOrCommandAdapter() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); File(h.project, "answer.txt").writeText("PRIVATE_DENIED_READ")
            lateinit var ai: AiController
            main { ai = h.controller(); ai.select("allowlist") }; ready(ai)
            main { ai.setPolicy(ai.state.value.policy.copy(overrides = ai.state.value.policy.overrides + (AiTool.ReadFile to AiPermission.Deny))) }
            await { !ai.state.value.permissionsSaving }
            f.enqueue(calls = listOf(delegate("reviewer")))
            f.enqueue(calls = listOf(AiCall("forged-write", "write_file", "{\"path\":\"pwned.txt\",\"content\":\"bad\"}"),
                AiCall("denied-read", "read_file", "{\"path\":\"answer.txt\"}"), AiCall("forged-shell", "run_command", "{\"command\":\"touch pwned\"}")))
            f.enqueue("Unable to obtain permitted evidence")
            f.enqueue("Report limitations")
            main { ai.setAutomaticDelegation(true); assertTrue(ai.send("Review code")) }
            await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertFalse(File(h.project, "pwned.txt").exists()); assertEquals(0, h.changed.get())
            val requests = (1..4).map { f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8() }
            assertFalse(requests.joinToString().contains("PRIVATE_DENIED_READ"))
            assertFalse(JSONObject(requests[1]).getJSONArray("tools").toString().contains("read_file"))
        }
    }
    @Test(timeout = 90000) fun budgetPauseCommitsWholeBatchReloadsAndContinuesWithoutReplayingWrite() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); lateinit var ai: AiController
            main { ai = h.controller(); ai.select("budget") }; ready(ai)
            main { ai.taskLimits = AiTaskLimits(requests = 1, tools = 1) }
            f.enqueue(calls = listOf(AiCall("write-once", "write_file", "{\"path\":\"result.txt\",\"content\":\"ONCE\"}"),
                AiCall("deferred", "write_file", "{\"path\":\"deferred.txt\",\"content\":\"NOT_YET\"}")))
            main { assertTrue(ai.send("Write once, then verify")) }
            await { ai.state.value.approval != null }; main { ai.approve(AiDecision.Once) }
            await { !ai.state.value.active }
            assertEquals("ONCE", File(h.project, "result.txt").readText())
            assertFalse(File(h.project, "deferred.txt").exists()); assertEquals(1, h.changed.get())
            assertTrue(ai.state.value.taskDiagnostic.startsWith("task_budget:"))
            assertEquals(AiToolStatus.Cancelled, ai.state.value.events.single { it.callId == "deferred" }.status)
            lateinit var restored: AiController
            main { restored = h.controller(); restored.select("budget") }; ready(restored)
            assertTrue(restored.state.value.taskDiagnostic.startsWith("task_budget:"))
            assertTrue(restored.state.value.contextTurns.joinToString().contains("write-once"))
            f.enqueue("Resume from saved results; file was already written")
            main { assertTrue(restored.continueBudget()) }; await { !restored.state.value.active }
            assertEquals(AiPhase.Completed, restored.state.value.phase); assertEquals(1, h.changed.get())
            f.server.takeRequest(); val resumed = JSONObject(f.server.takeRequest()!!.body.readUtf8()).getJSONArray("messages")
            assertTrue(resumed.toString().contains("write-once")); assertTrue(resumed.toString().contains("deferred"))
            assertEquals(1, restored.state.value.taskUsage.requests)
        }
    }
    @Test(timeout = 90000) fun threeEquivalentToolFailuresPauseBeforeFourthRequest() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); lateinit var ai: AiController
            main { ai = h.controller(); ai.select("repeat") }; ready(ai)
            repeat(3) { n -> f.enqueue(calls = listOf(AiCall("missing-$n", "read_file", JSONObject().put("path", "missing.txt").put("reason", "Attempt $n").toString()))) }
            main { assertTrue(ai.send("Inspect missing file")) }; await { !ai.state.value.active }
            assertTrue(ai.state.value.taskDiagnostic.startsWith("task_budget:repeated_tool_failure"))
            assertEquals(3, f.server.requestCount); assertEquals(3, ai.state.value.events.count { it.status == AiToolStatus.Failed })
            assertNotNull(f.history.load("repeat").state)
        }
    }
    @Test(timeout = 90000) fun stopCancelsActualParallelRequestsAndPreventsLateParentContinuation() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val h = Harness(f); lateinit var ai: AiController
            main { ai = h.controller(); ai.select("stop-children") }; ready(ai)
            f.enqueue(calls = listOf(delegate("scout", "reviewer")))
            f.enqueue("late child A", responseDelayMillis = 1500)
            f.enqueue("late child B", responseDelayMillis = 1500)
            main { ai.setAutomaticDelegation(true); assertTrue(ai.send("Split discovery and review")) }
            await { f.server.requestCount == 3 }
            main { ai.stop() }; await { !ai.state.value.active }
            assertEquals(AiPhase.Cancelled, ai.state.value.phase)
            assertEquals(AiToolStatus.Cancelled, ai.state.value.events.single { it.callId == "delegate" }.status)
            delay(1700)
            assertEquals(3, f.server.requestCount); assertEquals(0, h.changed.get())
            assertEquals(AiPhase.Cancelled, f.history.load("stop-children").state!!.phase)
        }
    }
    @Test(timeout = 90000) fun transportRetriesConsumeSameBudgetAndCannotStartAnExtraAttempt() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            val h = Harness(f); lateinit var ai: AiController
            main { ai = h.controller(); ai.select("retry-budget") }; ready(ai)
            main { ai.taskLimits = AiTaskLimits(requests = 2) }
            repeat(3) { f.server.enqueue(MockResponse().setResponseCode(503)) }
            main { assertTrue(ai.send("Inspect project")) }; await { !ai.state.value.active }
            assertTrue(ai.state.value.taskDiagnostic.startsWith("task_budget:requests"))
            assertEquals(2, f.server.requestCount); assertEquals(2, ai.state.value.taskUsage.requests)
            assertNotNull(f.history.load("retry-budget").state)
        }
    }

}

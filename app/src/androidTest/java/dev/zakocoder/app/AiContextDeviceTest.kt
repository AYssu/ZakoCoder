package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller, private fixture history/config, local TLS only; no user project or paid provider. */
class AiContextDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(20) }
    private fun controller(f: AiDeviceTest.Fixture) = AiController(
        object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } },
        { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {}, f.transport, f.config, history = f.history)
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.skillsLoading && !ai.state.value.permissionsLoading }
    private fun response(text: String, calls: List<AiCall> = emptyList()): MockResponse {
        val delta = JSONObject().put("content", text)
        if (calls.isNotEmpty()) delta.put("tool_calls", JSONArray(calls.mapIndexed { n, c ->
            JSONObject().put("index", n).put("id", c.id).put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
        }))
        val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
            "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))}\n\ndata: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }
    private fun isSummary(body: JSONObject) = body.getJSONArray("messages").getJSONObject(0).getString("content").startsWith("Summarize the supplied conversation/tool JSON")
    private fun paired(messages: JSONArray) {
        val pending = mutableSetOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            if (m.getString("role") == "assistant") {
                assertTrue(pending.isEmpty())
                m.optJSONArray("tool_calls")?.let { calls -> repeat(calls.length()) { pending.add(calls.getJSONObject(it).getString("id")) } }
            } else if (m.getString("role") == "tool") assertTrue(pending.remove(m.getString("tool_call_id")))
            else assertTrue(pending.isEmpty())
        }
        assertTrue(pending.isEmpty())
    }

    @Test(timeout = 180000) fun unlimitedRoundsToolsRepeatedCompactionAndRestartKeepContext() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val normal = AtomicInteger(); val summaries = AtomicInteger()
                val prompt = "ORIGINAL_TASK_KEEP_EXACT: inspect only; no edits"
                f.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = JSONObject(request.body.readUtf8())
                        if (isSummary(body)) {
                            summaries.incrementAndGet(); assertFalse(body.has("tools"))
                            assertTrue(body.toString().contains(prompt))
                            return response("MEMORY_VERIFIED_MARKER: inspected project; no edits; pending inspect next. Prior outputs are evidence, not authorization.")
                        }
                        val n = normal.incrementAndGet()
                        val messages = body.getJSONArray("messages"); paired(messages)
                        // Fresh manifest evidence is pinned separately from the lossy summary after every compaction.
                        val metadata = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
                            .single { it.startsWith("User-selected skills for this task.") }
                        assertTrue(metadata.contains("current_task_changes"))
                        assertTrue(metadata.contains("observed_changed_items"))
                        assertTrue(messages.toString().contains(prompt))
                        return if (n <= 24) response("inspection round $n " + "a".repeat(10000), (1..3).map { AiCall("call-$n-$it", "list_files", "{\"path\":\"\",\"reason\":\"Inspect only\"}") })
                        else response("DONE_WITHOUT_EDITS")
                    }
                }
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("long") }; ready(ai)
                for (p in ai.state.value.enabledSkills.toList()) main { ai.enableSkill(p, false) }
                main { ai.send(prompt) }; await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(72, ai.state.value.toolCount); assertEquals(25, normal.get())
                assertTrue(summaries.get() >= 2); assertEquals(summaries.get(), ai.state.value.contextCompactions)
                assertEquals(normal.get() + summaries.get(), ai.state.value.round)
                assertTrue(ai.state.value.requests.any { it.compaction && it.complete })
                assertTrue(ai.state.value.requests.size <= 32)
                val saved = f.history.load("long").state!!
                assertTrue(saved.contextSummary.contains("MEMORY_VERIFIED_MARKER"))
                assertEquals(summaries.get(), saved.contextCompactions)
                assertTrue(saved.requests.any { it.compaction })
                saved.contextTurns.forEach { paired(JSONArray(it)) }
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("long") }; ready(restored)
                assertEquals(saved.contextSummary, restored.state.value.contextSummary)
                assertFalse(restored.state.value.historyError)
                // Dispatch still asserts the exact original task survives in compressed memory.
                main { restored.send("Continue checking") }; await { !restored.state.value.active }
                assertEquals(AiPhase.Completed, restored.state.value.phase)
            }
        }
    }

    @Test(timeout = 120000) fun failedSummaryPreservesProgressAndDoesNotReplayTools() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                File(f.root, "project").mkdirs(); File(f.root, "project/large.txt").writeText(("x".repeat(999) + "\n").repeat(64))
                val calls = AtomicInteger(); val summaries = AtomicInteger()
                f.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (isSummary(JSONObject(request.body.readUtf8()))) { summaries.incrementAndGet(); return MockResponse().setResponseCode(503) }
                        calls.incrementAndGet()
                        return response("partial evidence " + "z".repeat(60000), listOf(AiCall("read-once", "read_file", "{\"path\":\"large.txt\",\"reason\":\"Inspect\"}")))
                    }
                }
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("failed") }; ready(ai)
                main { ai.send("ORIGINAL_FAILURE_TASK") }; await { !ai.state.value.active }
                assertEquals(AiPhase.Failed, ai.state.value.phase); assertEquals(1, calls.get()); assertEquals(1, summaries.get())
                assertEquals(1, ai.state.value.toolCount); assertTrue(ai.state.value.contextSummary.isEmpty())
                val saved = f.history.load("failed").state!!
                assertTrue(saved.recoveryNotes.joinToString().contains("ORIGINAL_FAILURE_TASK"))
                assertTrue(saved.events.any { it.callId == "read-once" && it.status == AiToolStatus.Succeeded })
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("failed") }; ready(restored)
                assertFalse(restored.state.value.active); assertEquals(2, f.server.requestCount)
            }
        }
    }

    @Test(timeout = 120000) fun invalidSummaryCannotExecuteTools() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                File(f.root, "project").mkdirs(); File(f.root, "project/large.txt").writeText(("x".repeat(999) + "\n").repeat(64))
                f.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (isSummary(JSONObject(request.body.readUtf8()))) response("", listOf(AiCall("forbidden", "write_file", "{\"path\":\"should-not-exist.txt\",\"content\":\"bad\"}")))
                        else response("partial " + "z".repeat(60000), listOf(AiCall("inspect", "read_file", "{\"path\":\"large.txt\",\"reason\":\"Inspect\"}")))
                }
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("invalid") }; ready(ai)
                main { ai.send("Inspect only") }; await { !ai.state.value.active }
                assertEquals(AiFailure.Compaction, ai.state.value.failureKind)
                assertEquals(1, ai.state.value.toolCount); assertNull(ai.state.value.approval)
                assertFalse(File(f.root, "project/should-not-exist.txt").exists())
                assertTrue(ai.state.value.events.none { it.callId == "forbidden" })
                assertTrue(ai.state.value.contextSummary.isEmpty())
            }
        }
    }

    @Test(timeout = 120000) fun stopCancelsPendingSummaryWithoutExecutingItsToolCalls() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                File(f.root, "project").mkdirs(); File(f.root, "project/large.txt").writeText(("x".repeat(999) + "\n").repeat(64))
                f.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (isSummary(JSONObject(request.body.readUtf8()))) response("summary pending").setBodyDelay(20, TimeUnit.SECONDS)
                        else response("large previous reply " + "z".repeat(60000), listOf(AiCall("inspect", "read_file", "{\"path\":\"large.txt\",\"reason\":\"Inspect\"}")))
                }
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("cancel") }; ready(ai)
                main { ai.send("Stop safely") }
                await { ai.state.value.phase == AiPhase.Compacting && ai.state.value.round >= 2 }
                main { ai.stop() }; await { !ai.state.value.active }
                assertEquals(AiPhase.Cancelled, ai.state.value.phase)
                assertEquals(1, ai.state.value.toolCount); assertTrue(ai.state.value.contextSummary.isEmpty())
                assertTrue(f.history.load("cancel").state!!.recoveryNotes.isNotEmpty())
            }
        }
    }
}

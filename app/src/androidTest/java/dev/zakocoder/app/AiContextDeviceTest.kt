package dev.zakocoder.app

import android.content.ContextWrapper
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
        run {
            AiDeviceTest.Fixture(context).use { f ->
                configure(f, AiContextOptions(contextWindowTokens = 64000, compressionThresholdPercent = 70))
                val normal = AtomicInteger(); val summaries = AtomicInteger()
                val prompt = "ORIGINAL_TASK_KEEP_EXACT: inspect only; no edits"
                var stableMetadata: String? = null
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
                        if (stableMetadata == null) stableMetadata = metadata else assertEquals(stableMetadata, metadata)
                        assertFalse(metadata.contains("current_task_changes"))
                        val stateUpdates = (0 until messages.length()).map { messages.getJSONObject(it) }
                            .filter { it.optString("role") == "user" && it.optString("content").startsWith(AI_CONTEXT_UPDATE) }
                        assertTrue(stateUpdates.isNotEmpty())
                        val latestChanges = stateUpdates.map { it.getString("content") }
                            .last { it.contains("SECTION changes") }
                        assertTrue(latestChanges.contains("current_task_changes"))
                        assertTrue(latestChanges.contains("observed_changed_items"))
                        assertTrue(latestChanges.contains("\"sealed\":false"))
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
                // A new user task may take a fresh initial project snapshot; stability is task scoped.
                stableMetadata = null
                main { restored.send("Continue checking") }; await { !restored.state.value.active }
                assertEquals(AiPhase.Completed, restored.state.value.phase)
            }
        }
    }

    @Test(timeout = 120000) fun failedSummaryPreservesProgressAndDoesNotReplayTools() = runBlocking {
        run {
            AiDeviceTest.Fixture(context).use { f ->
                configure(f, AiContextOptions(contextWindowTokens = 56000, compressionThresholdPercent = 70))
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
        run {
            AiDeviceTest.Fixture(context).use { f ->
                configure(f, AiContextOptions(contextWindowTokens = 56000, compressionThresholdPercent = 70))
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
        run {
            AiDeviceTest.Fixture(context).use { f ->
                configure(f, AiContextOptions(contextWindowTokens = 56000, compressionThresholdPercent = 70))
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
    private fun configure(f: AiDeviceTest.Fixture, options: AiContextOptions) {
        val profile = f.config.catalog().active!!
        f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null, profile.parameters, options))
    }
    private suspend fun withoutSkills(ai: AiController) {
        for (skill in ai.state.value.enabledSkills.toList()) main { ai.enableSkill(skill, false) }
    }
    @Test(timeout = 100000) fun disabledAutomaticCompressionStopsAtConfiguredBudgetWithoutSummaryRequest() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(autoCompress = false, contextWindowTokens = 24000, summaryMaxChars = 2000))
            val requests = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8())
                    assertFalse(isSummary(body))
                    assertTrue(tokenBudget(AiConfig(context = AiContextOptions(contextWindowTokens = 24000)), body.getJSONArray("messages"), body.getJSONArray("tools")).fits)
                    requests.incrementAndGet()
                    // 60k ASCII characters alone estimate to 20k tokens: larger than
                    // this window's 18k input capacity, independent of prompt/schema size.
                    return response("large evidence " + "z".repeat(60000), listOf(AiCall("inspect-once", "list_files", "{\"path\":\"\",\"reason\":\"Inspect only\"}")))
                }
            }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("off-budget") }; ready(ai); withoutSkills(ai)
            main { ai.send("Inspect without summarizing") }; await { !ai.state.value.active }
            assertEquals(AiFailure.ContextLimit, ai.state.value.failureKind)
            assertEquals(1, requests.get()); assertEquals(0, ai.state.value.contextCompactions)
            assertEquals(1, ai.state.value.toolCount)
            assertTrue(f.history.load("off-budget").state!!.recoveryNotes.isNotEmpty())
        }
    }
    @Test(timeout = 100000) fun configuredThresholdAndSummaryLengthAreAppliedToRealSummaryRequests() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(contextWindowTokens = 36000, compressionThresholdPercent = 50, summaryMaxChars = 2000))
            val normal = AtomicInteger(); val summaries = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8())
                    if (isSummary(body)) {
                        val summaryNumber = summaries.incrementAndGet()
                        val summaryMessages = body.getJSONArray("messages")
                        val instruction = summaryMessages.getJSONObject(0).getString("content")
                        assertTrue(instruction.contains("at most 2000 characters"))
                        assertFalse(body.has("tools"))
                        assertTrue(summaryNumber in 1..2)
                        if (summaryNumber == 2) {
                            assertTrue(instruction.contains("The previous summary exceeded the character limit"))
                            assertTrue(instruction.contains("Rewrite the supplied summary more concisely within the same limit"))
                            assertTrue(summaryMessages.getJSONObject(1).getString("content").contains("s".repeat(2001)))
                        }
                        return response("s".repeat(2001))
                    }
                    normal.incrementAndGet()
                    return response("large evidence " + "z".repeat(30000), listOf(AiCall("inspect-once", "list_files", "{\"path\":\"\",\"reason\":\"Inspect only\"}")))
                }
            }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("configured-summary") }; ready(ai); withoutSkills(ai)
            main { ai.send("Inspect with a short summary") }; await { !ai.state.value.active }
            assertEquals(AiFailure.Compaction, ai.state.value.failureKind)
            assertEquals(1, normal.get()); assertEquals(2, summaries.get())
            assertTrue(ai.state.value.contextSummary.isEmpty())
            assertTrue(f.history.load("configured-summary").state!!.contextSummary.isEmpty())
            assertEquals(1, ai.state.value.toolCount)
            assertEquals(0, ai.state.value.contextCompactions)
        }
    }
    @Test(timeout = 100000) fun largerConfiguredContextSurvivesRestartBeyondLegacyRetentionLimit() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(256000, false, 95, 12000))
            f.server.enqueue(response("large answer " + "a".repeat(100000)))
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("large-budget") }; ready(ai); withoutSkills(ai)
            main { ai.send("Keep this complete answer") }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            val saved = f.history.load("large-budget").state!!
            assertTrue(saved.contextTurns.sumOf { it.length } > 96000)
            assertEquals(0, saved.contextCompactions)
            f.server.takeRequest(2, TimeUnit.SECONDS)
            lateinit var restored: AiController
            main { restored = controller(f); restored.select("large-budget") }; ready(restored)
            assertFalse(restored.state.value.historyError)
            assertEquals(saved.contextTurns, restored.state.value.contextTurns)
            f.server.enqueue(response("continued"))
            main { restored.send("Continue with that retained answer") }; await { !restored.state.value.active }
            assertEquals(AiPhase.Completed, restored.state.value.phase)
            val body = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(body.getJSONArray("messages").toString().length > 96000)
            assertTrue(body.toString().contains("a".repeat(100000)))
        }
    }
    @Test(timeout = 120000) fun nearThresholdEscapedCodeCompactsWithoutDoubleJsonExpansion() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val options = AiContextOptions(contextWindowTokens = 48000, compressionThresholdPercent = 90)
            configure(f, options)
            val prompt = "Inspect prior code without replaying actions"
            f.server.enqueue(response("baseline"))
            lateinit var baseline: AiController
            main { baseline = controller(f); baseline.select("calibration") }; ready(baseline); withoutSkills(baseline)
            main { baseline.send(prompt) }; await { !baseline.state.value.active }
            assertEquals(AiPhase.Completed, baseline.state.value.phase)
            val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val settings = f.config.load()
            val original = wire.getJSONArray("messages")
            val tools = wire.getJSONArray("tools")
            val codeLine = "const payload = \"C:\\\\tmp\\\\\"quoted\\\\\"\\\\file\";\n"
            fun candidate(repeats: Int): JSONArray {
                val trial = JSONArray().put(original.getJSONObject(0))
                    .put(JSONObject().put("role", "user").put("content", "EARLIEST_CODE_REQUEST"))
                    .put(JSONObject().put("role", "assistant").put("content", codeLine.repeat(repeats)))
                for (i in 1 until original.length()) trial.put(original.getJSONObject(i))
                return trial
            }
            val target = (options.contextWindowTokens - 8192) * 95L / 100
            var low = 1; var high = 20000
            while (low < high) {
                val middle = (low + high) / 2
                if (tokenBudget(settings, candidate(middle), tools).inputTokens < target) low = middle + 1 else high = middle
            }
            val trial = candidate(low)
            assertTrue(tokenBudget(settings, trial, tools).fits)
            assertTrue(tokenBudget(settings, trial, tools).shouldCompress)
            val oldEvidence = JSONArray()
            for (i in 1 until trial.length()) oldEvidence.put(trial.getJSONObject(i))
            val doubleEncodedSummary = JSONArray()
                .put(JSONObject().put("role", "system").put("content", contextSummaryPrompt(options.summaryMaxChars)))
                .put(JSONObject().put("role", "user").put("content", oldEvidence.toString()))
            assertFalse(tokenBudget(settings.copy(parameters = AiParameters(maxTokens = 4000)), doubleEncodedSummary, JSONArray()).fits)
            val retained = JSONArray().put(trial.getJSONObject(1)).put(trial.getJSONObject(2)).toString()
            f.history.save("quoted-code", AiState(turn = 1, contextTurns = listOf(retained), skillsInitialized = true))
            val summaries = AtomicInteger(); val normals = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8())
                    if (isSummary(body)) {
                        summaries.incrementAndGet()
                        val evidence = body.getJSONArray("messages").getJSONObject(1).getString("content")
                        assertTrue(evidence.contains(codeLine))
                        assertTrue(evidence.contains("EARLIEST_CODE_REQUEST"))
                        assertTrue(body.getInt("max_tokens") <= 8192)
                        return response("QUOTED_CODE_MEMORY: inspect existing files; no prior actions authorized for replay.")
                    }
                    normals.incrementAndGet(); return response("review complete")
                }
            }
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("quoted-code") }; ready(ai)
            main { ai.send(prompt) }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(1, summaries.get()); assertEquals(1, normals.get())
            assertTrue(f.history.load("quoted-code").state!!.contextSummary.contains("QUOTED_CODE_MEMORY"))
        }
    }

    @Test(timeout = 120000) fun millionTokenControllerSendsLongHistoryWithoutEarlySummaryAndRestarts() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(contextWindowTokens = 1048576))
            val turns = (1..60).map { index -> JSONArray()
                .put(JSONObject().put("role", "user").put("content", "ORIGINAL_TURN_$index"))
                .put(JSONObject().put("role", "assistant").put("content", "answer $index " + "a".repeat(10000))).toString() }
            f.history.save("million-window", AiState(turn = 60, contextTurns = turns, skillsInitialized = true))
            f.server.enqueue(response("long context retained"))
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("million-window") }; ready(ai)
            main { ai.send("Continue using the earliest turn") }; await { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertEquals(0, ai.state.value.contextCompactions)
            val request = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(isSummary(request)); assertEquals(1, f.server.requestCount)
            assertTrue(request.getJSONArray("messages").length() > 96)
            assertTrue(request.toString().contains("ORIGINAL_TURN_1"))
            assertTrue(ai.state.value.estimatedContextTokens > 128000)
            lateinit var restarted: AiController
            main { restarted = controller(f); restarted.select("million-window") }; ready(restarted)
            assertFalse(restarted.state.value.historyError)
            assertEquals(61, restarted.state.value.contextTurns.size)
            assertEquals(turns.first(), restarted.state.value.contextTurns.first())
            assertEquals(0, restarted.state.value.contextCompactions)
        }
    }

}

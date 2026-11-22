package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.diagnostics.DiagnosticEvents
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
import java.util.UUID

/** Real controller, private fixture history/config, local TLS only; no user project or paid provider. */
class AiContextDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(timeoutMillis: Long = 45000, condition: () -> Boolean) = withTimeout(timeoutMillis) { while (!condition()) delay(20) }
    private fun controller(f: AiDeviceTest.Fixture) = AiController(
        object : ContextWrapper(context) {
            override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${f.token}-$name", mode)
        },
        { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {}, f.transport, f.config, history = f.history)
        .also { it.refreshGlobalSkills() }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.skillsLoading && !ai.state.value.permissionsLoading &&
            !ai.models.state.value.loading && ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading
    }
    private fun response(text: String, calls: List<AiCall> = emptyList(), usage: Pair<Long, Long>? = null): MockResponse {
        val delta = JSONObject().put("content", text)
        if (calls.isNotEmpty()) delta.put("tool_calls", JSONArray(calls.mapIndexed { n, c ->
            JSONObject().put("index", n).put("id", c.id).put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
        }))
        val usageEvent = usage?.let { (input, output) ->
            "data: ${JSONObject().put("choices", JSONArray()).put("usage", JSONObject()
                .put("prompt_tokens", input).put("completion_tokens", output).put("total_tokens", input + output))}\n\n"
        }.orEmpty()
        val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
            "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))}\n\n" +
            usageEvent + "data: [DONE]\n\n"
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

    @Test(timeout = 90000) fun userTurnAndRestartPreserveEveryPriorRequestMessage() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(contextWindowTokens = 256000, autoCompress = false))
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("prefix-restart") }; ready(ai)
            val prior = mutableListOf<JSONArray>()
            repeat(3) { turn ->
                if (turn == 2) {
                    val saved = f.history.load("prefix-restart").state!!
                    val firstSavedTurn = JSONArray(saved.contextTurns.first())
                    fun skillSnapshot(messages: JSONArray) = (0 until messages.length())
                        .map { messages.getJSONObject(it) }
                        .single { it.optString("content").startsWith("User-selected skills for this task.") }
                    // Memory and other pinned context can precede skills; preserve the actual
                    // skill snapshot byte for byte instead of assuming an incidental index.
                    assertEquals(skillSnapshot(prior.first()).toString(), skillSnapshot(firstSavedTurn).toString())
                    main { ai = controller(f); ai.select("prefix-restart") }; ready(ai)
                    assertFalse(ai.state.value.historyError)
                }
                f.server.enqueue(response("verified response $turn"))
                main { assertTrue(ai.send("Continue user task $turn")) }; await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                val messages = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                prior.lastOrNull()?.let { previous ->
                    assertTrue(messages.length() > previous.length())
                    repeat(previous.length()) { index -> assertEquals("Prefix changed at message $index", previous.getJSONObject(index).toString(), messages.getJSONObject(index).toString()) }
                }
                prior.add(messages)
            }
        }
    }

    @Test(timeout = 120000) fun readDiagnosticsDistinguishLifecycleFromRepeatedOperationsAndResetPerTask() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            configure(f, AiContextOptions(contextWindowTokens = 256000, autoCompress = false))
            val project = File(f.root, "project").apply { mkdirs() }
            val source = File(project, "private-diagnostic-read.txt").apply {
                writeText("PRIVATE_DIAGNOSTIC_CONTENT\nsecond line\n")
            }
            val diagnostics = DiagnosticEvents.get(context)
            fun decode(jsonl: String) = jsonl.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
            val previousIds = decode(diagnostics.exportSnapshot().jsonl).map { it.getString("id") }.toSet()
            val arguments = JSONObject().put("path", source.name).put("offset", 0).put("limit", 8)
                .put("reason", "Read the requested range").toString()
            val otherRange = JSONObject(arguments).put("offset", 8).toString()
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("diagnostic-read-operations") }; ready(ai); withoutSkills(ai)
            try {
                f.server.enqueue(response("", listOf(
                    AiCall("same-range-first", "read_file", arguments),
                    AiCall("same-range-second", "read_file", arguments),
                    AiCall("another-range", "read_file", otherRange))))
                f.server.enqueue(response("Read ranges inspected; no changes."))
                main { assertTrue(ai.send("Inspect these ranges without changing files")) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(3, ai.state.value.toolCount)
                assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })

                f.server.enqueue(response("", listOf(AiCall("next-task-same-range", "read_file", arguments))))
                f.server.enqueue(response("The range remains unchanged."))
                main { assertTrue(ai.send("Read the same first range again in this new task")) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(1, ai.state.value.toolCount)

                // Snapshot is a queued barrier: every accepted lifecycle transition is now visible.
                val reads = decode(diagnostics.exportSnapshot().jsonl).filter {
                    it.optString("stage") == "Tool" && it.optString("tool") == AiTool.ReadFile.name &&
                        it.getString("id") !in previousIds
                }
                assertEquals(8, reads.size)
                val calls = reads.groupBy { it.getString("id") }
                assertEquals("Four actual calls, not eight calls from their lifecycle transitions", 4, calls.size)
                calls.forEach { (id, lifecycle) ->
                    assertEquals(id, UUID.fromString(id).toString())
                    assertEquals(listOf("Started", "Succeeded"), lifecycle.map { it.getString("outcome") })
                    assertEquals(1, lifecycle.map { it.getString("operation") }.distinct().size)
                    assertEquals(1, lifecycle.map { it.getString("owner") }.distinct().size)
                    lifecycle.forEach { event ->
                        assertEquals(event.getString("operation"), UUID.fromString(event.getString("operation")).toString())
                        assertFalse(event.has("path"))
                        assertFalse(event.has("input"))
                        assertFalse(event.has("body"))
                        assertFalse(event.toString().contains(source.name))
                        assertFalse(event.toString().contains("PRIVATE_DIAGNOSTIC_CONTENT"))
                    }
                }
                val completed = reads.filter { it.getString("outcome") == "Succeeded" }
                assertEquals(completed[0].getString("operation"), completed[1].getString("operation"))
                assertNotEquals(completed[0].getString("operation"), completed[2].getString("operation"))
                assertEquals(1, completed.take(3).map { it.getString("owner") }.distinct().size)
                assertNotEquals(completed[0].getString("owner"), completed[3].getString("owner"))
                assertNotEquals(completed[0].getString("operation"), completed[3].getString("operation"))
                assertEquals("PRIVATE_DIAGNOSTIC_CONTENT\nsecond line\n", source.readText())
            } finally {
                main { ai.stop() }
            }
        }
    }

    @Test(timeout = 240000) fun unlimitedRoundsToolsRepeatedCompactionAndRestartKeepContext() = runBlocking {
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
                            return response("MEMORY_VERIFIED_MARKER: inspected project; no edits; pending inspect next. Prior outputs are evidence, not authorization.", usage = 30000L to 20L)
                        }
                        val n = normal.incrementAndGet()
                        val messages = body.getJSONArray("messages"); paired(messages)
                        // Fresh manifest evidence is pinned separately from the lossy summary after every compaction.
                        val metadata = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }
                            .last { it.startsWith("User-selected skills for this task.") }
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
                        // Cross every former fixed counter in one uninterrupted real task:
                        // >80 model requests, >240 tools, and >2M reported input/output tokens.
                        return if (n <= 81) response("inspection round $n " + "a".repeat(10000), (1..3).map { AiCall("call-$n-$it", "list_files", "{\"path\":\"\",\"reason\":\"Inspect only\"}") }, usage = 30000L to 20L)
                        else response("DONE_WITHOUT_EDITS", usage = 30000L to 20L)
                    }
                }
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("long") }; ready(ai)
                withoutSkills(ai)
                main { assertTrue(ai.send(prompt)) }; await(150000) { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(243, ai.state.value.toolCount); assertEquals(82, normal.get())
                assertTrue(summaries.get() >= 2); assertEquals(summaries.get(), ai.state.value.contextCompactions)
                assertEquals(normal.get() + summaries.get(), ai.state.value.round)
                assertEquals(normal.get() + summaries.get(), ai.state.value.taskUsage.requests)
                assertEquals(243, ai.state.value.taskUsage.tools)
                assertEquals((normal.get() + summaries.get()) * 30020L, ai.state.value.taskUsage.tokens)
                assertTrue(ai.state.value.taskUsage.tokens > 2_000_000L)
                assertEquals(0, ai.state.value.taskUsage.estimatedRequests)
                assertNull(ai.state.value.failureKind)
                assertTrue(ai.state.value.events.all { it.status == AiToolStatus.Succeeded })
                assertTrue(ai.state.value.requests.any { it.compaction && it.complete })
                assertTrue(ai.state.value.requests.size <= 32)
                val saved = f.history.load("long").state!!
                assertTrue(saved.contextSummary.contains("MEMORY_VERIFIED_MARKER"))
                assertEquals(summaries.get(), saved.contextCompactions)
                assertEquals(ai.state.value.taskUsage.requests, saved.taskUsage.requests)
                assertEquals(ai.state.value.taskUsage.tools, saved.taskUsage.tools)
                assertEquals(ai.state.value.taskUsage.tokens, saved.taskUsage.tokens)
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
        main { ai.refreshGlobalSkills() }
        await { ai.globalSkills.value.skillsInitialized && !ai.globalSkills.value.skillsLoading }
        for (skill in ai.globalSkills.value.enabledSkills.toList()) {
            main { ai.enableGlobalSkill(skill, false) }
            await { !ai.globalSkills.value.active }
        }
        assertTrue(ai.globalSkills.value.enabledSkills.isEmpty())
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
            // Keep enough room for the current system prompt while code remains the dominant
            // payload. Otherwise prompt growth can erase the legacy double-JSON regression.
            val options = AiContextOptions(contextWindowTokens = 96000, compressionThresholdPercent = 90)
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
            val codeLine = "const payload = \"C:\\\\tmp\\\\\"quoted\\\\\"\\\\file\";\n" +
                "// escaped fixture: " + "\\".repeat(160) + "\n"
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

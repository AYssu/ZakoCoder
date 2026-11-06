package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real Android JSON + structured concurrency. No provider account, shell or user files. */
class AiSubagentsDeviceTest {
    private val schemas get() = JSONArray(AiSubagents.tools.map { AiToolRegistry.definition(it).schema })
    @Test fun invalidRoleOversizedBatchAndExtraFieldsAreRejected() {
        val inputs = listOf("{\"tasks\":[]}", "{\"tasks\":[{\"role\":\"worker\",\"task\":\"edit\"}]}",
            "{\"tasks\":[{\"role\":\"scout\",\"task\":\"x\",\"model\":\"untrusted\"}]}",
            "{\"tasks\":[{\"role\":\"scout\",\"task\":\"x\"}],\"tools\":[\"run_command\"]}")
        inputs.forEach { input ->
            try { AiSubagents.tasks(JSONObject(input)); fail("Must reject $input") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun parallelChildrenHaveIsolatedTranscriptsAndNoAdditionalPlanningRequests() = runBlocking {
        val requests = AtomicInteger(); val active = AtomicInteger(); val maxActive = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val result = withTimeout(5000) { AiSubagents.run(listOf("scout" to "only-A", "reviewer" to "only-B"), "shared-evidence", AiTaskSession(), schemas,
            request = { _, messages, _ ->
                requests.incrementAndGet()
                val concurrency = active.incrementAndGet(); maxActive.updateAndGet { maxOf(it, concurrency) }
                if (concurrency == 2) entered.complete(Unit)
                entered.await()
                val transcript = messages.toString()
                synchronized(seen) { seen += transcript }
                assertFalse(transcript.contains("only-A") && transcript.contains("only-B"))
                active.decrementAndGet()
                AiReply("found source.kt:12", emptyList())
            }, execute = { _, _ -> error("No tool should execute") }, progress = { _, _ -> }) }
        assertEquals(2, maxActive.get()); assertEquals(2, requests.get()); assertEquals(2, seen.size)
        assertEquals("completed", JSONArray(result).getJSONObject(0).getString("status"))
    }
    @Test fun forgedShellWriteMcpAndRecursiveDelegationNeverReachExecutor() = runBlocking {
        var request = 0; var executions = 0
        val result = AiSubagents.run(listOf("scout" to "inspect"), "", AiTaskSession(), schemas,
            request = { _, messages, _ ->
                if (request++ == 0) AiReply("", listOf("run_command", "write_file", "use_mcp", "delegate_readonly").mapIndexed { n, tool -> AiCall("$n", tool, "{}") })
                else {
                    assertEquals(7, messages.length())
                    for (index in 3..6) assertTrue(messages.getJSONObject(index).getString("content").contains("Denied"))
                    AiReply("No supported evidence", emptyList())
                }
            }, execute = { _, _ -> executions++; "should never happen" }, progress = { _, _ -> })
        assertEquals(0, executions)
        assertEquals("completed_with_tool_errors", JSONArray(result).getJSONObject(0).getString("status"))
    }
    @Test fun parentStopCancelsBothChildrenAndWaitsForCleanup() = runBlocking {
        val active = AtomicInteger(); val finished = AtomicInteger()
        val job = launch { AiSubagents.run(listOf("scout" to "A", "reviewer" to "B"), "", AiTaskSession(), schemas,
            request = { _, _, _ ->
                active.incrementAndGet()
                try { awaitCancellation() } finally { finished.incrementAndGet() }
            }, execute = { _, _ -> error("No tool") }, progress = { _, _ -> }) }
        withTimeout(3000) { while (active.get() != 2) delay(5) }
        job.cancelAndJoin(); assertEquals(2, finished.get())
    }
    @Test fun failingChildDoesNotEraseSuccessfulSiblingAndErrorMessagesAreNotLeaked() = runBlocking {
        val output = AiSubagents.run(listOf("scout" to "A", "reviewer" to "B"), "", AiTaskSession(), schemas,
            request = { child, _, _ -> if (child.startsWith("1:")) throw IllegalStateException("PRIVATE_KEY_DO_NOT_LEAK") else AiReply("source.kt:5", emptyList()) },
            execute = { _, _ -> error("unused") }, progress = { _, _ -> })
        val rows = JSONArray(output)
        assertEquals("failed", rows.getJSONObject(0).getString("status"))
        assertEquals("completed", rows.getJSONObject(1).getString("status"))
        assertFalse(output.contains("PRIVATE_KEY_DO_NOT_LEAK"))
    }
    @Test fun requestBudgetIsSharedAndOneChildCannotUseAnotherAllowance() = runBlocking {
        val session = AiTaskSession(AiTaskLimits(requests = 1))
        val output = AiSubagents.run(listOf("scout" to "A", "reviewer" to "B"), "", session, schemas,
            request = { _, _, _ -> session.request(); AiReply("done", emptyList()) }, execute = { _, _ -> "" }, progress = { _, _ -> })
        val rows = JSONArray(output)
        assertEquals(1, session.snapshot().requests)
        assertEquals(setOf("completed", "limited"), (0..1).map { rows.getJSONObject(it).getString("status") }.toSet())
    }
    @Test fun fingerprintIgnoresReasonAndObjectKeyOrderButRetainsActualArguments() {
        val a = AiCall("1", "read_file", "{\"path\":\"a.kt\",\"reason\":\"first\",\"limit\":100}")
        val b = AiCall("2", "read_file", "{\"limit\":100,\"reason\":\"retry differently\",\"path\":\"a.kt\"}")
        assertEquals(toolFingerprint(a), toolFingerprint(b))
        assertNotEquals(toolFingerprint(a), toolFingerprint(b.copy(arguments = "{\"path\":\"b.kt\",\"limit\":100}")))
    }
    @Test fun controlledLatencyComparisonRecordsSchedulingBenefitWithoutClaimingProviderSpeed() = runBlocking {
        suspend fun run(tasks: List<Pair<String, String>>): Long {
            val start = System.nanoTime()
            AiSubagents.run(tasks, "", AiTaskSession(), schemas, request = { _, _, _ -> delay(150); AiReply("found", emptyList()) },
                execute = { _, _ -> "" }, progress = { _, _ -> })
            return (System.nanoTime() - start) / 1_000_000
        }
        val sequential = run(listOf("scout" to "A")) + run(listOf("reviewer" to "B"))
        val parallel = run(listOf("scout" to "A", "reviewer" to "B"))
        android.util.Log.i("ZakoAgentBenchmark", "controlled_delay_ms=150 sequential_ms=$sequential parallel_ms=$parallel requests_before=2 requests_after=2")
        // Concurrency correctness has a separate barrier test; avoid flaky speed thresholds here.
        assertTrue(sequential >= 300); assertTrue(parallel >= 150)
    }
    @Test fun childRoundLimitReturnsPartialEvidenceWithoutAnotherSummaryRequest() = runBlocking {
        var requests = 0; var reads = 0
        val result = AiSubagents.run(listOf("scout" to "inspect"), "", AiTaskSession(), schemas,
            request = { _, _, _ -> requests++; AiReply("", listOf(AiCall("read-$requests", "read_file", "{}"))) },
            execute = { _, _ -> reads++; "PARTIAL_FILE_EVIDENCE" }, progress = { _, _ -> })
        val row = JSONArray(result).getJSONObject(0)
        assertEquals(AiSubagents.MAX_ROUNDS, requests); assertEquals(requests, reads)
        assertEquals("limited", row.getString("status"))
        assertTrue(row.getString("result").contains("PARTIAL_FILE_EVIDENCE"))
    }

    @Test fun childRepeatedReadFailureStopsWithoutStoppingSuccessfulSibling() = runBlocking {
        val requests = AtomicInteger()
        val output = AiSubagents.run(listOf("scout" to "A", "reviewer" to "B"), "", AiTaskSession(), schemas,
            request = { child, _, _ ->
                if (child.startsWith("2:")) AiReply("Sibling findings", emptyList())
                else { requests.incrementAndGet(); AiReply("", listOf(AiCall("read-${requests.get()}", "read_file", "{\"path\":\"missing.txt\"}"))) }
            }, execute = { _, _ -> throw IllegalArgumentException("fixture missing file") }, progress = { _, _ -> })
        val rows = JSONArray(output)
        assertEquals(3, requests.get())
        assertEquals("child_repeated_tool_failure", rows.getJSONObject(0).getString("diagnostic"))
        assertEquals("completed", rows.getJSONObject(1).getString("status"))
    }

}

package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiTurnStatisticsTest {
    private fun request(id: String, turn: Int = 1, round: Int = 1, started: Long = 100,
        input: Long? = 100, output: Long? = 20, cache: Long? = 50, complete: Boolean = true,
        context: Long = 1000, window: Int = 1000000, compaction: Boolean = false, agent: String = "") =
        AiRequestLog(id = id, turn = turn, round = round, started = started, inputTokens = input,
            outputTokens = output, cachedTokens = cache, complete = complete, elapsed = 2500, firstEvent = 500,
            estimatedContextTokens = context, contextWindowTokens = window, compaction = compaction, agent = agent)

    @Test fun streamUpsertsAndRepeatedTerminalCallbacksNeverDoubleCount() {
        val pending = request("first", input = null, output = null, cache = null, complete = false)
        val original = AiTurnStatistics(1)
        var statistics = original.observe(pending)
        statistics = statistics.observe(pending.copy(inputTokens = 90, outputTokens = 5, cachedTokens = 40))
        statistics = statistics.observe(request("first"))
        val completed = statistics
        statistics = statistics.observe(request("first").copy(elapsed = 9999))
        assertEquals(completed, statistics)
        assertFalse(original.hasRequests)
        assertEquals(100L, statistics.usage().input)
        assertEquals(20L, statistics.usage().output)
        assertEquals(120L, statistics.usage().total)
        assertEquals(10.0, statistics.usage().tokensPerSecond!!, 0.001)
        assertFalse(statistics.usage().partial)
    }

    @Test fun numericalUsageSurvivesMoreThanOneHundredLargeDiagnosticRequests() {
        var statistics = AiTurnStatistics(1)
        var diagnostics = emptyList<AiRequestLog>()
        repeat(120) { index ->
            val record = request("r$index", round = index + 1, started = index.toLong()).copy(request = "x".repeat(240000))
            statistics = statistics.observe(record)
            diagnostics = boundedRequests(diagnostics + record)
        }
        assertTrue(diagnostics.size < 32)
        val restored = AiTurnStatistics.fromJson(JSONObject(statistics.toJson().toString()))
        assertEquals(12000L, restored.usage().input)
        assertEquals(2400L, restored.usage().output)
        assertEquals(6000L, restored.usage().cached)
        assertEquals(50.0, restored.usage().cachePercent!!, 0.001)
        assertEquals(1000L, restored.lastContextTokens)
        assertFalse(restored.usage().partial)
        assertFalse(restored.toJson().toString().contains("xxxx"))
    }

    @Test fun sealedBaseReleasesIdsAndNewRequestsContinueTheSameTurn() {
        val sealed = AiTurnStatistics(1).observe(request("first")).sealed()
        assertEquals(0, sealed.toJson().getJSONArray("requests").length())
        val restored = AiTurnStatistics.fromJson(JSONObject(sealed.toJson().toString()))
        val continued = restored.observe(request("second", round = 20, started = 200))
        assertEquals(200L, continued.usage().input)
        assertEquals(40L, continued.usage().output)
        assertFalse(continued.usage().partial)
        assertEquals(continued.usage(), continued.sealed().usage())
        assertEquals(continued.sealed(), continued.sealed().sealed())
    }

    @Test fun waitingTaskCanRestoreAndFinishAnAlreadyObservedRequest() {
        val waiting = AiTurnStatistics(1).observe(request("first", complete = false, output = null))
        val restored = AiTurnStatistics.fromJson(JSONObject(waiting.toJson().toString()))
        val resumed = restored.observe(request("first")).observe(request("second", round = 2))
        assertEquals(200L, resumed.usage().input)
        assertEquals(40L, resumed.usage().output)
        assertFalse(resumed.usage().partial)
    }

    @Test fun missingProviderNumbersRemainUnknownAcrossSealingAndRestore() {
        val statistics = AiTurnStatistics(1).observe(request("known"))
            .observe(request("unknown", input = null, output = null, cache = null)).sealed()
        val restored = AiTurnStatistics.fromJson(JSONObject(statistics.toJson().toString()))
        assertEquals(100L, restored.usage().input)
        assertEquals(20L, restored.usage().output)
        assertTrue(restored.usage().partial)
        assertNull(restored.usage().cached)
        assertNull(restored.usage().cachePercent)
        val unknown = AiTurnStatistics(1).observe(request("none", input = null, output = null, cache = null)).sealed()
        assertNull(unknown.usage().input)
        assertNull(unknown.usage().output)
        assertNull(unknown.usage().total)
        assertNull(unknown.usage().tokensPerSecond)
    }

    @Test fun latestStartedMainRequestWinsRegardlessOfCompletionOrder() {
        val later = request("later", started = 200, round = 2, context = 5000, window = 1050000, complete = false)
        var statistics = AiTurnStatistics(1).observe(later)
        statistics = statistics.observe(request("older", started = 100, context = 1000))
        statistics = statistics.observe(request("child", started = 300, context = 8000, agent = "investigator"))
        statistics = statistics.observe(request("summary", started = 400, context = 9000, compaction = true))
        assertEquals(5000L, statistics.lastContextTokens)
        assertEquals(1050000, statistics.contextWindowTokens)
        assertTrue(statistics.compacted)
        assertEquals(400L, statistics.usage().input)
        val restored = AiTurnStatistics.fromJson(JSONObject(statistics.sealed().toJson().toString()))
        assertEquals(5000L, restored.lastContextTokens)
        assertEquals(1050000, restored.contextWindowTokens)
        assertTrue(restored.compacted)
    }

    @Test fun latestMainWithUnknownContextDoesNotBorrowAnOlderCapacity() {
        val statistics = AiTurnStatistics(1).observe(request("known"))
            .observe(request("unknown", started = 200, context = 0))
        assertTrue(statistics.hasRequests)
        assertNull(statistics.lastContextTokens)
        assertNull(statistics.contextWindowTokens)
        assertFalse(AiTurnStatistics(1).hasRequests)
    }

    @Test fun legacyBackfillDetectsMissingRoundsWithoutInventingUsage() {
        val legacy = AiTurnStatistics.fromRequests(1, listOf(request("third", round = 3), request("other", turn = 2)))
        assertEquals(100L, legacy.usage().input)
        assertTrue(legacy.usage().partial)
        assertTrue(AiTurnStatistics.fromRequests(1, emptyList()).usage().partial)
        assertTrue(AiTurnStatistics.fromRequests(1, listOf(request("first")), partial = true).usage().partial)
    }

    @Test fun cacheRequiresMatchingKnownInputAndUsesWeightedDenominator() {
        val statistics = AiTurnStatistics(1).observe(request("a", input = 1000, cache = 900))
            .observe(request("b", input = 9000, cache = 100)).sealed()
        assertEquals(1000L, statistics.usage().cached)
        assertEquals(10.0, statistics.usage().cachePercent!!, 0.001)
        assertNull(AiTurnStatistics(1).observe(request("bad", input = 50, cache = 100)).usage().cachePercent)
        assertEquals(0L, AiTurnStatistics(1).observe(request("zero", cache = 0)).usage().cached)
    }

    @Test fun arithmeticOverflowIsUnknownInsteadOfNegative() {
        val statistics = AiTurnStatistics(1).observe(request("a", input = Long.MAX_VALUE, cache = Long.MAX_VALUE))
            .observe(request("b", input = 1, cache = 1))
        assertNull(statistics.usage().input)
        assertNull(statistics.usage().cached)
        assertTrue(statistics.usage().partial)
    }

    @Test fun invalidIdentityNumbersAndDuplicateRequestIdsAreRejected() {
        reject { AiTurnStatistics(-1) }
        reject { AiTurnStatistics(1).observe(request("wrong", turn = 2)) }
        reject { AiTurnStatistics(1).observe(request("bad id")) }
        reject { AiTurnStatistics(1).observe(request("negative", input = -1)) }
        val json = AiTurnStatistics(1).observe(request("valid")).toJson()
        reject { AiTurnStatistics.fromJson(JSONObject(json.toString()).put("turn", "1")) }
        reject { AiTurnStatistics.fromJson(JSONObject(json.toString()).put("turn", 1.5)) }
        reject { AiTurnStatistics.fromJson(JSONObject(json.toString()).put("partial", "false")) }
        reject { AiTurnStatistics.fromJson(JSONObject(json.toString()).apply { getJSONObject("base").put("count", -1) }) }
        reject { AiTurnStatistics.fromJson(JSONObject(json.toString()).apply {
            getJSONArray("requests").put(getJSONArray("requests").getJSONObject(0))
        }) }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid statistics must be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: org.json.JSONException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun combinedCounterOverflowAndWrongBooleanAreRejectedBeforeDisplay() {
        val snapshot = AiTurnStatistics(1).observe(request("live", complete = false)).toJson()
        snapshot.getJSONObject("base").put("count", Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { AiTurnStatistics.fromJson(snapshot) }
        val wrongType = AiTurnStatistics(1).toJson().put("partial", "false")
        assertThrows(IllegalArgumentException::class.java) { AiTurnStatistics.fromJson(wrongType) }
    }

}

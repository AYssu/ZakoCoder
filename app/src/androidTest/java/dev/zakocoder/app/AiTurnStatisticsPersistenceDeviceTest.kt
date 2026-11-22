package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Owned numerical fixtures only; no device provider settings, real requests or user conversations. */
class AiTurnStatisticsPersistenceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val restartRoot get() = File(context.cacheDir, "turn-statistics-restart-owned")

    private fun request(turn: Int, round: Int, large: Boolean = false) = AiRequestLog(
        id = "request-$turn-$round", turn = turn, round = round, started = turn * 100_000L + round,
        inputTokens = 1_000L * turn, outputTokens = 100, cachedTokens = 500,
        elapsed = 2_000, firstEvent = 1_000, complete = true,
        estimatedContextTokens = 10_000L * turn + round, contextWindowTokens = 128_000 * turn,
        request = if (large) "x".repeat(240_000) else "")

    private fun longConversation(): AiState {
        var state = AiState(turn = 4, phase = AiPhase.Completed,
            timeline = (1..4).flatMap { turn -> listOf(
                AiEntry("user-$turn", "Owned task $turn", role = AiRole.User, turn = turn),
                AiEntry("answer-$turn", "Owned result $turn", role = AiRole.Assistant, turn = turn)) },
            turnTimings = (1..4).map { AiTurnTiming(it, it * 100_000L, it * 100_000L + 50_000) })
        for (turn in 1..4) for (round in 1..40) state = state.withRequestLog(request(turn, round))
        return state
    }

    private fun verifyLongConversation(state: AiState) {
        assertEquals(4, state.turnStatistics.size)
        for (turn in 1..4) {
            val statistics = requireNotNull(state.statisticsForTurn(turn))
            assertEquals(40_000L * turn, statistics.usage().input)
            assertEquals(4_000L, statistics.usage().output)
            assertEquals(20_000L, statistics.usage().cached)
            assertEquals(100.0, statistics.usage().tokensPerSecond!!, 0.001)
            assertFalse(statistics.usage().partial)
            assertEquals(10_000L * turn + 40, statistics.lastContextTokens)
            assertEquals(128_000 * turn, statistics.contextWindowTokens)
        }
    }

    @Test fun allTurnsSurviveDiagnosticEvictionFreshStoreModelChangeForkAndJsonTransfer() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val state = longConversation()
            assertEquals(32, state.requests.size)
            assertNull("Regression trigger: the old footer has already lost turn 1", turnUsage(state.requests, 1).input)
            fixture.history.save("usage", state)
            val restored = requireNotNull(AiHistoryStore(File(fixture.root, "history")).load("usage").state)
            verifyLongConversation(restored.copy(contextWindowTokens = 1_050_000, estimatedContextTokens = 999_999))
            assertTrue("Completed histories retain compact totals, not all request contributions",
                restored.turnStatistics.all { it == it.sealed() })
            val fork = restored.forkBeforeTurn(3)
            assertEquals(listOf(1, 2), fork.turnStatistics.map { it.turn })
            assertNull(fork.statisticsForTurn(3))
            val document = AiConversationTransfer.fromState(restored, "Owned usage fixture")
            val json = AiConversationTransfer.encode(document, AiConversationTransfer.Format.Json)
            val imported = AiConversationTransfer.importedState(
                AiConversationTransfer.decode(json.toByteArray(), "usage.json"), "imported")
            verifyLongConversation(imported)
            fixture.history.save("imported", imported)
            verifyLongConversation(AiHistoryStore(File(fixture.root, "history")).load("imported").state!!)
        }
    }

    @Test fun payloadEvictionAndAnEvictedInFlightParentNeverLoseOrDuplicateUsage() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val parent = request(1, 1).copy(id = "parent", complete = false, inputTokens = null,
                outputTokens = null, cachedTokens = null, estimatedContextTokens = 25_000)
            var state = AiState(turn = 1, active = true, phase = AiPhase.Receiving).withRequestLog(parent)
            for (round in 2..41) state = state.withRequestLog(request(1, round, large = true).copy(agent = "child"))
            assertEquals("Byte retention evicts after three full payloads", 3, state.requests.size)
            assertFalse(state.requests.any { it.id == parent.id })
            fixture.history.save("in-flight", state)
            state = AiHistoryStore(File(fixture.root, "history")).load("in-flight").state!!
            val final = parent.copy(complete = true, inputTokens = 2_000, outputTokens = 200,
                cachedTokens = 1_000, elapsed = 3_000)
            state = state.withRequestLog(final).withRequestLog(final.copy(elapsed = 7_000))
            val statistics = requireNotNull(state.statisticsForTurn(1))
            assertEquals(42_000L, statistics.usage().input)
            assertEquals(4_200L, statistics.usage().output)
            assertEquals(21_000L, statistics.usage().cached)
            assertEquals(25_000L, statistics.lastContextTokens)
            assertEquals(128_000, statistics.contextWindowTokens)
            assertFalse(statistics.usage().partial)
        }
    }

    @Test fun legacyHistoryUsesOnlyRetainedEvidenceAndUnknownCacheStaysUnknown() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val state = AiState(turn = 2, requests = listOf(request(1, 3).copy(cachedTokens = null)))
            fixture.history.save("legacy", state)
            val record = File(fixture.root, "history").walkTopDown().single { it.name == "${state.conversationId}.json" }
            val json = JSONObject(record.readText()).apply { remove("turnStatistics") }
            record.writeText(json.toString())
            val legacy = AiHistoryStore(File(fixture.root, "history")).load("legacy").state!!
            val statistics = requireNotNull(legacy.statisticsForTurn(1))
            assertEquals(1_000L, statistics.usage().input)
            assertTrue(statistics.usage().partial)
            assertNull(statistics.usage().cached)
            assertNull("Lost history is never replaced by the latest turn", legacy.statisticsForTurn(2))
        }
    }

    @Test(timeout = 90_000) fun realControllerObserversPersistEachFinishedTask() = runBlocking {
        AiDeviceTest.Fixture(context).use { fixture ->
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = fixture.root
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            }
            fun controller() = AiController(isolated, { fixture.root }, { _, _ -> false },
                { _, _ -> error("No commands expected") }, {}, {}, fixture.transport, fixture.config,
                permissionStore = AiPermissionStore(isolated, "${fixture.token}-permissions"),
                history = AiHistoryStore(File(fixture.root, "controller-history")))
            suspend fun ready(ai: AiController) = withTimeout(20_000) {
                while (!ai.state.value.configured || ai.state.value.restoring || ai.state.value.permissionsLoading) delay(30)
            }
            val ai = withContext(Dispatchers.Main) { controller().also { it.select("controller") } }
            ready(ai)
            repeat(3) { turn ->
                val body = "data: {\"choices\":[{\"delta\":{\"content\":\"Owned answer $turn\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100,\"prompt_tokens_details\":{\"cached_tokens\":500}}}\n\n" +
                    "data: [DONE]\n\n"
                fixture.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                withContext(Dispatchers.Main) { assertTrue(ai.send("Owned task $turn")) }
                withTimeout(25_000) { while (ai.state.value.active) delay(30) }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(1_000L, ai.state.value.statisticsForTurn(turn + 1)!!.usage().input)
            }
            val fresh = withContext(Dispatchers.Main) { controller().also { it.select("controller") } }
            ready(fresh)
            assertEquals(3, fresh.state.value.turn)
            (1..3).forEach { turn ->
                val statistics = requireNotNull(fresh.state.value.statisticsForTurn(turn))
                assertEquals(1_000L, statistics.usage().input)
                assertEquals(100L, statistics.usage().output)
                assertEquals(500L, statistics.usage().cached)
                assertNotNull(statistics.lastContextTokens)
            }
            assertEquals(3, fixture.server.requestCount)
        }
    }

    /** Host explicitly force-stops the isolated app between these two opted-in phases. */
    @Test fun saveBeforeProcessDeath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usageRestartProbe") == "true")
        check(restartRoot.mkdirs())
        AiHistoryStore(File(restartRoot, "history")).save("restart", longConversation())
        File(restartRoot, "pid").writeText(Process.myPid().toString())
    }

    @Test fun verifyAfterProcessDeath() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usageRestartProbe") == "true")
        try {
            assertNotEquals(File(restartRoot, "pid").readText().toInt(), Process.myPid())
            verifyLongConversation(AiHistoryStore(File(restartRoot, "history")).load("restart").state!!)
        } finally { restartRoot.deleteRecursively() }
    }
}

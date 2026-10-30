package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Isolated local history fixtures only, no model connection or user data. */
class AiReasoningHistoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun reasoningAndPartialStreamSurviveRestartAndLegacyRecordsRemainReadable() {
        AiDeviceTest.Fixture(context).use { f ->
            val state = AiState(turn = 1, active = true, phase = AiPhase.Receiving,
                timeline = listOf(AiEntry("question", "inspect", role = AiRole.User, turn = 1),
                    AiEntry("answer", "partial answer", role = AiRole.Assistant, turn = 1,
                        reasoning = "public provider reasoning, received before failure", reasoningTruncated = true)))
            f.history.save("reasoning", state)
            val reopened = AiHistoryStore(File(f.root, "history")).load("reasoning").state!!
            assertTrue(reopened.interrupted)
            assertEquals(state.timeline, reopened.timeline)
            assertTrue(reopened.contextTurns.isEmpty())
            val record = File(f.root, "history").walkTopDown().single { it.name == "${state.conversationId}.json" }
            val old = JSONObject(record.readText())
            val entries = old.getJSONArray("timeline")
            repeat(entries.length()) { entries.getJSONObject(it).remove("reasoning"); entries.getJSONObject(it).remove("reasoningTruncated") }
            record.writeText(old.toString())
            val legacy = AiHistoryStore(File(f.root, "history")).load("reasoning").state!!
            assertTrue(legacy.timeline.all { it.reasoning.isEmpty() && !it.reasoningTruncated })
            assertEquals("partial answer", legacy.timeline.last().text)
        }
    }
    @Test fun reasoningCannotBypassPerEntryRoleOrCombinedTimelineBudgets() {
        AiDeviceTest.Fixture(context).use { f ->
            val valid = AiState(turn = 1, timeline = listOf(AiEntry("assistant", "x", role = AiRole.Assistant, turn = 1, reasoning = "r".repeat(64000))))
            f.history.save("bounds", valid)
            assertEquals(64000, f.history.load("bounds").state!!.timeline.single().reasoning.length)
            listOf(
                valid.copy(timeline = listOf(valid.timeline.single().copy(reasoning = "r".repeat(64001)))),
                valid.copy(timeline = listOf(AiEntry("user", "user text", role = AiRole.User, reasoning = "not a provider answer"))),
                valid.copy(timeline = listOf(AiEntry("user", "user text", role = AiRole.User, reasoningTruncated = true))),
                valid.copy(timeline = (1..5).map { AiEntry("answer-$it", "a".repeat(64000), role = AiRole.Assistant, reasoning = "r".repeat(64000)) })
            ).forEach { invalid ->
                try { f.history.save("bounds", invalid); fail("Invalid reasoning retention must fail before overwriting history") }
                catch (_: IllegalArgumentException) { }
                assertEquals(valid.timeline, f.history.load("bounds").state!!.timeline)
            }
        }
    }
}

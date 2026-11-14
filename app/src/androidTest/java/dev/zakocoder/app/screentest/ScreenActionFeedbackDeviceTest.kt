package dev.zakocoder.app.screentest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises production feedback with deterministic node trees; never operates a user's screen. */
class ScreenActionFeedbackDeviceTest {
    private fun observation(text: String, token: String = "snapshot", sensitive: Boolean = false, truncated: Boolean = false) =
        ScreenTestResult(JSONObject().put("snapshot", token).put("package", "fixture")
            .put("truncated", truncated).put("nodes", JSONArray().put(JSONObject().put("id", "n1")
                .put("text", text).put("description", "").put("sensitive", sensitive))).toString(), packageName = "fixture")

    @Test fun actionsDispatchOnceAndReturnFreshObservationWithoutClaimingVerification() = runBlocking {
        val feedback = ScreenActionFeedback()
        var clicked = 0
        var reads = 0
        val backend: suspend (JSONObject) -> ScreenTestResult = { args ->
            if (args.getString("action") == "observe") observation(if (clicked == 0) "Counter: 0" else "Counter: 1", "s${++reads}")
            else { clicked++; ScreenTestResult("{\"requested\":\"click\",\"verified\":false}") }
        }
        val before = JSONObject(feedback.execute("lease", JSONObject().put("action", "observe"), backend).output)
        val result = JSONObject(feedback.execute("lease", JSONObject().put("action", "click")
            .put("snapshot", before.getString("snapshot")).put("node", "n1"), backend).output)
        assertEquals(1, clicked)
        assertTrue(result.getBoolean("dispatched"))
        assertTrue(result.getBoolean("observed_change"))
        assertFalse(result.getBoolean("verified"))
        assertEquals("postcondition_not_specified", result.getString("unknown_reason"))
        assertEquals("Counter: 1", result.getJSONObject("observation").getJSONArray("nodes").getJSONObject(0).getString("text"))
    }

    @Test fun unavailablePostObservationNeverRepeatsOrLeaksTypedInput() = runBlocking {
        val feedback = ScreenActionFeedback()
        var actions = 0
        val backend: suspend (JSONObject) -> ScreenTestResult = { args ->
            if (args.getString("action") == "observe") throw java.io.IOException("private downstream failure")
            actions++
            ScreenTestResult("{\"requested\":\"input_text\"}")
        }
        val result = feedback.execute("lease", JSONObject().put("action", "input_text")
            .put("snapshot", "old").put("node", "n1").put("text", "never-echo-user-input"), backend)
        val output = JSONObject(result.output)
        assertEquals(1, actions)
        assertTrue(output.isNull("observed_change"))
        assertFalse(output.getBoolean("verified"))
        assertEquals("observation_unavailable", output.getString("unknown_reason"))
        assertFalse(result.output.contains("never-echo-user-input"))
        assertFalse(result.output.contains("private downstream failure"))
    }

    @Test fun unchangedScreenOnlyRepeatsReadOperations() = runBlocking {
        val feedback = ScreenActionFeedback()
        var actions = 0
        var reads = 0
        val backend: suspend (JSONObject) -> ScreenTestResult = { args ->
            if (args.getString("action") == "observe") observation("unchanged", "s${++reads}")
            else { actions++; ScreenTestResult("{\"requested\":\"click\"}") }
        }
        val before = JSONObject(feedback.execute("lease", JSONObject().put("action", "observe"), backend).output)
        val result = JSONObject(feedback.execute("lease", JSONObject().put("action", "click")
            .put("snapshot", before.getString("snapshot")), backend).output)
        assertEquals(1, actions)
        assertEquals(3, reads)
        assertFalse(result.getBoolean("observed_change"))
        assertEquals("unchanged_screen", result.getString("unknown_reason"))
    }

    @Test fun waitsForVisibleTextAndCannotMatchPasswordNodes() = runBlocking {
        val feedback = ScreenActionFeedback()
        var reads = 0
        val success = JSONObject(feedback.execute("lease", JSONObject().put("action", "wait_until").put("text", "Ready").put("timeout_ms", 2000)) {
            assertEquals("observe", it.getString("action"))
            reads++
            observation(if (reads > 1) "Ready" else "Loading", "s$reads")
        }.output)
        assertTrue(success.getBoolean("verified"))
        assertFalse(success.getBoolean("dispatched"))
        assertFalse(success.getBoolean("timed_out"))
        assertEquals(2, reads)
        val timeout = JSONObject(feedback.execute("lease", JSONObject().put("action", "wait_until").put("text", "private").put("timeout_ms", 30)) {
            observation("private", sensitive = true)
        }.output)
        assertFalse(timeout.getBoolean("verified"))
        assertTrue(timeout.getBoolean("timed_out"))
        assertEquals("condition_timeout", timeout.getString("unknown_reason"))
    }

    @Test fun waitCancellationPropagatesAndDoesNotBecomeAConditionTimeout() = runBlocking {
        try {
            ScreenActionFeedback().execute("lease", JSONObject().put("action", "wait_until").put("text", "ready")) {
                throw CancellationException("user stopped")
            }
            fail("User cancellation must propagate")
        } catch (cancelled: CancellationException) { assertEquals("user stopped", cancelled.message) }
    }

    @Test fun leaseChangeNeverUsesThePreviousTasksBaseline() = runBlocking {
        val feedback = ScreenActionFeedback()
        val backend: suspend (JSONObject) -> ScreenTestResult = { args ->
            if (args.getString("action") == "observe") observation("visible") else ScreenTestResult("{\"requested\":\"click\"}")
        }
        feedback.execute("old-task", JSONObject().put("action", "observe"), backend)
        val result = JSONObject(feedback.execute("new-task", JSONObject().put("action", "click").put("snapshot", "snapshot"), backend).output)
        assertTrue(result.isNull("observed_change"))
        assertEquals("missing_baseline", result.getString("unknown_reason"))
    }

    @Test fun invalidWaitArgumentsCannotReachTheBackend() = runBlocking {
        val feedback = ScreenActionFeedback()
        for (value in listOf(0, 20001, 1.5, "5000", JSONObject.NULL)) {
            try {
                feedback.execute("lease", JSONObject().put("action", "wait_until").put("text", "ready").put("timeout_ms", value)) {
                    error("Invalid timeout must fail before observing the device")
                }
                fail("Invalid timeout accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Paced local TLS events prove live reasoning ownership before the complete reply arrives. */
class AiLiveReasoningDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(ai: AiController, stage: String, condition: () -> Boolean) {
        try { withTimeout(30000) { while (!condition()) delay(10) } }
        catch (timeout: TimeoutCancellationException) {
            val state = ai.state.value
            val models = ai.models.state.value
            throw AssertionError("$stage: configured=${state.configured}, permissionsLoading=${state.permissionsLoading}, " +
                "skillsLoading=${state.skillsLoading}, modelsLoading=${models.loading}, modelError=${models.error.isNotEmpty()}, " +
                "phase=${state.phase}, active=${state.active}, reasoning=${state.reasoningEntryId != null}, " +
                "historyError=${state.historyError}, failure=${state.failureKind}, rounds=${state.round}, " +
                "requests=${state.requests.map { "${it.status}:${it.failure}:${it.diagnostic}" }}", timeout)
        }
    }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
        }
        return AiController(isolated, { id -> File(f.root, "project-$id").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("This fixture must never run a command") }, {}, {}, f.transport, f.config, history = f.history)
    }
    private suspend fun ready(ai: AiController) = await(ai, "Controller ready") {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading
    }
    private fun reasoning(value: String) = JSONObject().put("reasoning_content", value)
    private fun content(value: String) = JSONObject().put("content", value)
    private fun response(vararg parts: JSONObject, tools: Boolean = false, done: Boolean = true): MockResponse {
        val body = buildString {
            parts.forEach { append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", it)))}\n\n") }
            append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (tools) "tool_calls" else "stop")))}\n\n")
            if (done) append("data: [DONE]\n\n")
        }
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(32, 100, TimeUnit.MILLISECONDS)
    }

    @Test(timeout = 120000) fun liveReasoningStartsBeforeAnswerAndCannotReopenAfterAnswerOrHistoryReload() = runBlocking {
        AiDeviceTest.Fixture(base, timeoutSeconds = 45).use { f ->
            // QueueDispatcher.peek normally applies the response throttle to incoming bodies too.
            // Only pace server events; the full system/tool request must upload immediately.
            val responses = ResponseOnlyDispatcher()
            f.server.dispatcher = responses
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("live") }; ready(ai)
            responses.enqueue(response(reasoning("first "), reasoning("second"), content("answer"), reasoning(" late")))
            main { assertTrue(ai.send("fixture question")) }
            await(ai, "First reasoning fragment") { ai.state.value.reasoningEntryId != null }
            val entryId = ai.state.value.reasoningEntryId!!
            assertTrue(ai.state.value.active)
            assertEquals("first ", ai.state.value.timeline.single { it.id == entryId }.reasoning)
            assertTrue(ai.state.value.timeline.single { it.id == entryId }.text.isEmpty())
            await(ai, "Second reasoning fragment") { ai.state.value.timeline.any { it.id == entryId && it.reasoning == "first second" } }
            assertEquals(entryId, ai.state.value.reasoningEntryId)
            await(ai, "First answer fragment") { ai.state.value.timeline.any { it.id == entryId && it.text == "answer" } }
            assertNull("The first answer delta ends expanded live reasoning", ai.state.value.reasoningEntryId)
            await(ai, "Late reasoning fragment") { ai.state.value.timeline.any { it.id == entryId && it.reasoning.endsWith(" late") } }
            assertNull("Late provider fragments are retained without reopening", ai.state.value.reasoningEntryId)
            await(ai, "Answer completes") { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertNull(f.history.load("live").state!!.reasoningEntryId)
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("live") }; ready(reopened)
            assertNull(reopened.state.value.reasoningEntryId)
            assertEquals("first second late", reopened.state.value.timeline.single { it.id == entryId }.reasoning)
        }
    }

    @Test(timeout = 120000) fun toolDeltaClosesPreviousReasoningAndNextModelRoundOpensItsOwnSection() = runBlocking {
        AiDeviceTest.Fixture(base, timeoutSeconds = 45).use { f ->
            // QueueDispatcher.peek normally applies the response throttle to incoming bodies too.
            // Only pace server events; the full system/tool request must upload immediately.
            val responses = ResponseOnlyDispatcher()
            f.server.dispatcher = responses
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("tools") }; ready(ai)
            val tool = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "local-list")
                .put("function", JSONObject().put("name", "list_files").put("arguments", "{\"path\":\"\"}"))))
            responses.enqueue(response(reasoning("round one"), tool, tools = true))
            responses.enqueue(response(reasoning("round two"), content("done")))
            main { assertTrue(ai.send("inspect fixture directory")) }
            await(ai, "First tool-round reasoning") { ai.state.value.reasoningEntryId != null }
            val first = ai.state.value.reasoningEntryId!!
            await(ai, "First tool draft") { ai.state.value.drafts.isNotEmpty() }
            assertNull("First tool fragment closes reasoning without awaiting tool completion", ai.state.value.reasoningEntryId)
            await(ai, "Next-round reasoning") { ai.state.value.reasoningEntryId?.let { it != first } == true }
            val second = ai.state.value.reasoningEntryId!!
            assertEquals("round two", ai.state.value.timeline.single { it.id == second }.reasoning)
            await(ai, "Tool task completes") { !ai.state.value.active }
            assertEquals(AiPhase.Completed, ai.state.value.phase)
            assertNull(ai.state.value.reasoningEntryId)
            assertEquals(2, ai.state.value.timeline.count { it.role == AiRole.Assistant })
            assertEquals(AiToolStatus.Succeeded, ai.state.value.events.single().status)
        }
    }

    @Test(timeout = 120000) fun incompleteAndCancelledStreamsClearLiveOwnershipAndRetainReceivedText() = runBlocking {
        AiDeviceTest.Fixture(base, timeoutSeconds = 45).use { f ->
            // QueueDispatcher.peek normally applies the response throttle to incoming bodies too.
            // Only pace server events; the full system/tool request must upload immediately.
            val responses = ResponseOnlyDispatcher()
            f.server.dispatcher = responses
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("failure") }; ready(ai)
            responses.enqueue(response(reasoning("received before missing DONE"), done = false))
            main { assertTrue(ai.send("incomplete stream")) }
            await(ai, "Reasoning before incomplete stream") { ai.state.value.reasoningEntryId != null }
            await(ai, "Incomplete stream finishes") { !ai.state.value.active }
            assertEquals(AiPhase.Failed, ai.state.value.phase)
            assertNull(ai.state.value.reasoningEntryId)
            assertEquals("received before missing DONE", ai.state.value.timeline.last { it.role == AiRole.Assistant }.reasoning)
            main { ai.select("cancel") }; ready(ai)
            responses.enqueue(response(reasoning("received before stop"), content("padding".repeat(500))))
            main { assertTrue(ai.send("cancel fixture")) }
            await(ai, "Reasoning before cancellation") { ai.state.value.reasoningEntryId != null }
            main { ai.stop() }
            assertNull("Stop clears live state immediately", ai.state.value.reasoningEntryId)
            await(ai, "Cancelled task finishes") { !ai.state.value.active }
            assertEquals(AiPhase.Cancelled, ai.state.value.phase)
            assertEquals("received before stop", ai.state.value.timeline.last { it.role == AiRole.Assistant }.reasoning)
        }
    }
}

package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Generated TLS fixtures only; no user model configuration or requests. */
class AiLongReplyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(predicate: () -> Boolean) = withTimeout(25000) { while (!predicate()) delay(40) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private fun event(content: String, calls: JSONArray? = null): String {
        val delta = JSONObject().put("content", content)
        calls?.let { delta.put("tool_calls", it) }
        return "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta))) + "\n\n"
    }

    @Test(timeout = 90000) fun oversizedReplyRetainsEarlierPartialAndNeverExecutesTools(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { fixture ->
                val first = "Earlier received partial. " + "p".repeat(72000)
                val calls = JSONArray().put(JSONObject().put("index", 0).put("id", "forbidden-on-incomplete")
                    .put("function", JSONObject().put("name", "write_file").put("arguments", "{\"path\":\"never.txt\",\"content\":\"must-not-write\"}")))
                val body = event(first, calls) + event("q".repeat(AI_RESPONSE_TEXT_LIMIT - first.length + 1)) +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
                fixture.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
                var executed = 0
                lateinit var ai: AiController
                main {
                    ai = AiController(context, { fixture.root }, { _, _ -> false }, { _, _ -> executed++; error("Never execute incomplete reply") }, {}, {},
                        fixture.transport, fixture.config, history = fixture.history)
                    ai.select("long-reply-limit")
                }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                main { assertTrue(ai.send("生成较长说明")) }
                await { !ai.state.value.active }
                assertEquals(AiFailure.OutputLimit, ai.state.value.failureKind)
                assertEquals("response_text_limit", ai.state.value.requests.single().diagnostic)
                assertEquals(first, ai.state.value.timeline.single { entry -> entry.role == AiRole.Assistant }.text)
                assertNull(ai.state.value.approval)
                assertEquals(AiToolStatus.Incomplete, ai.state.value.events.single().status)
                assertEquals(AiTool.WriteFile, ai.state.value.events.single().tool)
                assertEquals(context.getString(R.string.ai_not_executed), ai.state.value.events.single().output)
                assertFalse(File(fixture.root, "never.txt").exists()); assertEquals(0, executed)
                assertEquals(first, withContext(Dispatchers.IO) { fixture.history.load("long-reply-limit").state!!.timeline.single { entry -> entry.role == AiRole.Assistant }.text })
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun expandedAssistantHistoryKeepsOldBoundsAndConversationBudget() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val store = fixture.history
            val longText = "长".repeat(AI_RESPONSE_TEXT_LIMIT)
            val state = AiState(timeline = listOf(AiEntry("q", "old request", role = AiRole.User), AiEntry("a", longText, role = AiRole.Assistant)))
            store.save("history-bounds", state)
            assertEquals(state.timeline, store.load("history-bounds").state!!.timeline)
            for (bad in listOf(
                state.copy(timeline = listOf(AiEntry("a", longText + "x", role = AiRole.Assistant))),
                state.copy(timeline = listOf(AiEntry("q", "x".repeat(64001), role = AiRole.User))),
                state.copy(timeline = listOf(AiEntry("a", longText, role = AiRole.Assistant), AiEntry("b", longText, role = AiRole.Assistant), AiEntry("n", "x", role = AiRole.Notice))),
            )) {
                store.save("history-bounds", bad)
                assertThrows(IllegalArgumentException::class.java) { store.load("history-bounds") }
            }
            val short = state.copy(timeline = listOf(AiEntry("a", "legacy short", role = AiRole.Assistant)))
            store.save("history-bounds", short)
            assertEquals(short.timeline, store.load("history-bounds").state!!.timeline)
        }
    }
}

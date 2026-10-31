package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

class AiConversationDeviceTest {
    @org.junit.Before fun foreground() = warmDeviceActivity()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(40) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading }
    private fun controller(f: AiDeviceTest.Fixture) = AiController(context, { f.root }, { _, _ -> false }, { _, _ -> error("No commands") }, {}, {}, f.transport, f.config, history = f.history)

    private fun requestEntries(messages: JSONArray): List<JSONObject> =
        (0 until messages.length()).map { messages.getJSONObject(it) }.also { entries ->
            assertEquals("system", entries.first().getString("role"))
            assertEquals(1, entries.count { it.getString("role") == "system" })
            assertTrue(entries.drop(1).all { it.getString("role") in setOf("user", "assistant", "tool") })
        }

    @Test(timeout = 120000) fun incrementalReplyContextReloadNewSwitchAndDelete() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("conversation-fixture") }; ready(ai)
                val body = buildString {
                    repeat(10) { n ->
                        val delta = JSONObject().put("content", if (n == 0) "## 流式标题\n\n" else "增量-$n\n")
                        append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n")
                    }
                    append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                }
                f.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(256, 100, TimeUnit.MILLISECONDS))
                main { ai.send("记住标记 cedar-42") }
                await { ai.state.value.timeline.any { entry -> entry.role == AiRole.Assistant && entry.text.contains("增量-1") } }
                assertTrue("Content is visible before completion", ai.state.value.active)
                assertFalse(ai.state.value.timeline.last().text.contains("增量-9"))
                await { !ai.state.value.active }
                assertFalse(ai.state.value.historyError)
                val firstId = ai.state.value.conversationId
                assertEquals(1, ai.state.value.contextTurns.size)
                f.server.takeRequest(5, TimeUnit.SECONDS) ?: error("missing first request")
                f.enqueue("标记是 cedar-42。")
                main { ai.send("标记是什么？") }; await { !ai.state.value.active }
                val request = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val retained = requestEntries(request)
                val expectedReply = buildString {
                    repeat(10) { n -> append(if (n == 0) "## 流式标题\n\n" else "增量-$n\n") }
                }
                // Rule/capability snapshots are user-level messages too. Verify actual
                // conversation contents and order rather than their shifting positions.
                val dialogue = retained.filter {
                    it.getString("role") == "assistant" ||
                        (it.getString("role") == "user" && it.optString("content") in listOf("记住标记 cedar-42", "标记是什么？"))
                }
                assertEquals(listOf("user", "assistant", "user"), dialogue.map { it.getString("role") })
                assertEquals(listOf("记住标记 cedar-42", expectedReply, "标记是什么？"), dialogue.map { it.getString("content") })
                assertTrue(retained.none { it.getString("role") == "tool" || it.has("tool_calls") })
                lateinit var restored: AiController
                main { restored = controller(f); restored.select("conversation-fixture") }; ready(restored)
                assertEquals(2, restored.state.value.turn)
                assertEquals(2, restored.state.value.contextTurns.size)
                assertTrue(restored.state.value.timeline.any { entry -> entry.text == "标记是 cedar-42。" })
                assertFalse(restored.state.value.active)
                assertNull(restored.state.value.approval)
                main { restored.conversation() }; await { !restored.state.value.permissionsLoading }
                val freshId = restored.state.value.conversationId
                assertNotEquals(firstId, freshId); assertTrue(restored.state.value.timeline.isEmpty())
                main { restored.conversation(firstId) }; await { !restored.state.value.permissionsLoading }
                assertEquals(2, restored.state.value.turn)
                main { restored.deleteConversation(firstId) }; await { !restored.state.value.permissionsLoading }
                assertEquals(freshId, restored.state.value.conversationId)
                assertEquals(1, restored.state.value.conversations.size)
            }
        }
    }

    @Test(timeout = 120000) fun forkPersistsTimingAndLeavesOriginalAndFilesUntouched() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            lateinit var ai: AiController
            main { ai = controller(f); ai.select("fork-fixture") }; ready(ai)
            val source = File(f.root, "kept.txt").apply { writeText("current bytes") }
            repeat(2) { index ->
                f.enqueue("reply-$index")
                main { ai.send("prompt-$index") }; await { !ai.state.value.active }
                assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS))
            }
            val original = ai.state.value.conversationId
            val timing = ai.state.value.turnTimings.first()
            assertNotNull(timing.finished)
            var branched = false
            withContext(Dispatchers.Main) { branched = ai.forkConversation(original, 2) }
            assertTrue(branched)
            val fork = ai.state.value.conversationId
            assertNotEquals(original, fork)
            assertEquals(1, ai.state.value.turn)
            assertTrue(ai.state.value.timeline.none { it.turn >= 2 })
            assertEquals("current bytes", source.readText())
            lateinit var reopened: AiController
            main { reopened = controller(f); reopened.select("fork-fixture") }; ready(reopened)
            assertEquals(fork, reopened.state.value.conversationId)
            assertEquals(timing, reopened.state.value.turnTimings.single())
            main { reopened.conversation(original) }; await { !reopened.state.value.permissionsLoading }
            assertEquals(2, reopened.state.value.turn)
            assertTrue(reopened.state.value.timeline.any { it.text == "prompt-1" })
        }
    }

    @Test(timeout = 120000) fun completeToolPairsAndWholeTurnContextWindow() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("context-window") }; ready(ai)
                File(f.root, "readme.txt").writeText("source content")
                f.enqueue(calls = listOf(AiCall("read-pair", "read_file", "{\"path\":\"readme.txt\"}")))
                f.enqueue("已读取。")
                main { ai.send("第一个完整工具轮次") }; await { !ai.state.value.active }
                repeat(2) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
                f.enqueue("第二轮回复。")
                main { ai.send("第二个问题") }; await { !ai.state.value.active }
                val messages = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
                val retained = requestEntries(messages)
                val firstPrompt = retained.indices.single { retained[it].optString("content") == "第一个完整工具轮次" }
                val callIndex = retained.indices.single { retained[it].has("tool_calls") }
                val resultIndex = retained.indices.single { retained[it].getString("role") == "tool" }
                val replyIndex = retained.indices.single { retained[it].getString("role") == "assistant" && retained[it].optString("content") == "已读取。" }
                val nextPrompt = retained.indices.single { retained[it].optString("content") == "第二个问题" }
                assertTrue("The prior full tool turn precedes the new user prompt",
                    firstPrompt < callIndex && callIndex < resultIndex && resultIndex < replyIndex && replyIndex < nextPrompt)
                assertEquals("user", retained[firstPrompt].getString("role"))
                assertEquals("user", retained[nextPrompt].getString("role"))
                assertEquals("assistant", retained[callIndex].getString("role"))
                val calls = retained[callIndex].getJSONArray("tool_calls")
                assertEquals(1, calls.length())
                val call = calls.getJSONObject(0)
                assertEquals("read-pair", call.getString("id"))
                assertEquals("read_file", call.getJSONObject("function").getString("name"))
                assertEquals("readme.txt", JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"))
                assertEquals(call.getString("id"), retained[resultIndex].getString("tool_call_id"))
                assertTrue("Retain real tool output, not only its call ID", retained[resultIndex].getString("content").contains("source content"))
                assertEquals(listOf(callIndex, replyIndex), retained.indices.filter { retained[it].getString("role") == "assistant" })
                // Compression is governed by tokens, schema overhead and the configured
                // threshold, not a fixed number of user turns. Use a deliberately bounded
                // fixture window plus growing actual replies, and dispatch by request type.
                val profile = f.config.catalog().active!!
                f.config.saveProfile(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
                    profile.parameters, AiContextOptions(contextWindowTokens = 56000, compressionThresholdPercent = 70)))
                val summaryRequests = ConcurrentLinkedQueue<JSONObject>()
                val normalRequests = ConcurrentLinkedQueue<JSONObject>()
                f.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val body = JSONObject(request.body.readUtf8())
                        val isSummary = body.getJSONArray("messages").getJSONObject(0).getString("content")
                            .startsWith("Summarize the supplied conversation/tool JSON")
                        val reply = if (isSummary) {
                            summaryRequests.add(body)
                            "压缩记录：第一个完整工具轮次已读取 readme.txt，read-pair 返回 source content；后续问题按顺序处理。"
                        } else {
                            normalRequests.add(body)
                            "后续回复 ${normalRequests.size} " + "a".repeat(12000)
                        }
                        val delta = JSONObject().put("content", reply)
                        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                            "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    }
                }
                repeat(8) { round ->
                    main { assertTrue(ai.send("后续问题 $round")) }; await { !ai.state.value.active }
                    assertEquals("Continuation $round must finish despite any intervening summary", AiPhase.Completed, ai.state.value.phase)
                    assertEquals(round + 1, normalRequests.size)
                    val sent = normalRequests.last().getJSONArray("messages")
                    assertTrue(requestEntries(sent).any { it.optString("role") == "user" && it.optString("content") == "后续问题 $round" })
                }
                assertTrue("The small token window must cause a real summary request", summaryRequests.isNotEmpty())
                summaryRequests.forEach { assertFalse(it.has("tools")) }
                assertTrue("Summarization must receive the prior call ID", summaryRequests.first().toString().contains("read-pair"))
                assertTrue("Summarization must receive the actual tool result", summaryRequests.first().toString().contains("source content"))
                // Every remaining or restored tool batch must still have exactly paired
                // assistant calls/results; metadata must never split a pending batch.
                fun assertPaired(entries: List<JSONObject>) {
                    val pending = mutableSetOf<String>()
                    entries.forEach { message ->
                        when (message.getString("role")) {
                            "assistant" -> {
                                assertTrue(pending.isEmpty())
                                message.optJSONArray("tool_calls")?.let { calls ->
                                    repeat(calls.length()) { assertTrue(pending.add(calls.getJSONObject(it).getString("id"))) }
                                }
                            }
                            "tool" -> assertTrue(pending.remove(message.getString("tool_call_id")))
                            else -> assertTrue(pending.isEmpty())
                        }
                    }
                    assertTrue(pending.isEmpty())
                }
                normalRequests.forEach { assertPaired(requestEntries(it.getJSONArray("messages"))) }
                val state = ai.state.value
                assertTrue(state.contextTurns.isNotEmpty() && state.contextTurns.size < 10)
                assertTrue(state.contextSummary.contains("read-pair"))
                assertEquals(summaryRequests.size, state.contextCompactions)
                assertTrue(state.requests.any { it.compaction && it.complete })
                assertFalse(state.contextTurns.any { it.contains("read-pair") })
                assertEquals(10, state.timeline.count { it.role == AiRole.User })
                assertTrue(state.contextTurns.sumOf { it.length } <= AI_RETAINED_CONTEXT_CHARS)
                val saved = f.history.load("context-window").state!!
                assertEquals(state.contextSummary, saved.contextSummary)
                assertEquals(state.contextCompactions, saved.contextCompactions)
                assertEquals(state.contextTurns, saved.contextTurns)
                saved.contextTurns.forEach { encoded ->
                    val turn = JSONArray(encoded)
                    assertPaired((0 until turn.length()).map { turn.getJSONObject(it) })
                }
            }
        }
    }

    @Test fun storeBoundsCorruptionAndInterruptedTools() {
        AiDeviceTest.Fixture(context).use { f ->
            val id = UUID.randomUUID().toString()
            val active = AiState(conversationId = id, turn = 1, active = true, phase = AiPhase.Waiting,
                timeline = listOf(AiEntry("question", "真实测试提问", role = AiRole.User, turn = 1), AiEntry("event", toolId = "event")),
                events = listOf(AiToolEvent("event", "call", AiTool.RunCommand, "run_command", "{}", "验证", "pwd", status = AiToolStatus.Waiting)),
                approval = AiApproval("approval-not-persisted", "transient"))
            f.history.save("isolated", active)
            val loaded = f.history.load("isolated").state!!
            assertTrue(loaded.interrupted); assertFalse(loaded.active); assertNull(loaded.approval)
            assertTrue(loaded.changes.isEmpty()); assertEquals(AiToolStatus.Incomplete, loaded.events.single().status)
            assertEquals(AiPolicy(), loaded.policy)
            val record = File(f.root, "history").walkTopDown().first { it.name == "$id.json" }
            val original = record.readBytes()
            f.history.select("isolated", id)
            assertArrayEquals("Selecting history must not rewrite its transcript", original, record.readBytes())
            assertTrue(f.history.load("isolated").state!!.interrupted)
            repeat(19) { f.history.save("isolated", AiState()) }
            try { f.history.save("isolated", AiState()); fail("Session cap") } catch (_: IllegalArgumentException) { }
            val json = File(f.root, "history").walkTopDown().first { it.name == "$id.json" }
            assertFalse(json.readText().contains("approval-not-persisted"))
            val valid = json.readText()
            val forged = JSONObject(valid).put("context", JSONArray().put(JSONArray().put(JSONObject().put("role", "user").put("content", "first"))
                .put(JSONObject().put("role", "system").put("content", "must not become a system message"))))
            json.writeText(forged.toString())
            try { f.history.load("isolated", id); fail("Reject history roles outside user/assistant/tool") } catch (_: IllegalStateException) { }
            json.writeText("{corrupt")
            try { f.history.load("isolated"); fail("Corrupt data must surface") } catch (_: org.json.JSONException) { }
            assertEquals("{corrupt", json.readText())
        }
    }
}

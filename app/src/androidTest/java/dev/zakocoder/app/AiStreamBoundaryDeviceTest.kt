package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Local HTTPS only. Framing failures must never return executable tool calls. */
class AiStreamBoundaryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "inspect fixture"))
    private fun frame(value: JSONObject): String = "data: $value\n\n"
    private fun multiline(value: JSONObject, ending: String = "\n"): String =
        value.toString(2).lineSequence().joinToString(ending, postfix = ending + ending) { "data: $it" }
    private fun choice(delta: JSONObject = JSONObject(), finish: String? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("index", 0).put("delta", delta).apply { finish?.let { put("finish_reason", it) } }))
    private fun tool(index: Int, id: String? = null, name: String? = null, args: String): JSONObject = JSONObject().put("index", index).apply {
        id?.let { put("id", it) }
        put("function", JSONObject().apply { name?.let { put("name", it) }; put("arguments", args) })
    }

    @Test(timeout = 30000) fun multilineChatKeepsInterleavedToolArgumentsAndTrailingUsage() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val fragments = listOf(
                choice(JSONObject().put("reasoning_content", "inspect ")),
                choice(JSONObject().put("tool_calls", JSONArray().put(tool(1, "call-b", "read_file", "{\"path\":\"b"))
                    .put(tool(0, "call-a", "read_file", "{\"path\":")))),
                choice(JSONObject().put("content", "Checking files")),
                choice(JSONObject().put("tool_calls", JSONArray().put(tool(0, args = "\"a.kt\"}"))
                    .put(tool(1, args = ".kt\"}")))),
                choice(finish = "tool_calls"),
                JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", 23).put("completion_tokens", 11)
                    .put("prompt_tokens_details", JSONObject().put("cached_tokens", 17)))
            )
            val body = "\uFEFF: keepalive\r\n\r\n" + fragments.joinToString("") { multiline(it, "\r\n") } + "data: [DONE]\r\n\r\n"
            f.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(11, 1, TimeUnit.MILLISECONDS))
            val shown = StringBuilder(); val thought = StringBuilder(); var log: AiRequestLog? = null
            val reply = f.transport.request(f.config.load(), messages(), JSONArray(), observe = { log = it }, reasoningDelta = { thought.append(it) }) { shown.append(it) }
            assertEquals("Checking files", shown.toString()); assertEquals(shown.toString(), reply.text)
            assertEquals("inspect ", thought.toString()); assertEquals(thought.toString(), reply.reasoningContent)
            assertEquals(listOf(AiCall("call-a", "read_file", "{\"path\":\"a.kt\"}"), AiCall("call-b", "read_file", "{\"path\":\"b.kt\"}")), reply.calls)
            assertEquals(23L, log!!.inputTokens); assertEquals(11L, log!!.outputTokens); assertEquals(17L, log!!.cachedTokens)
            assertEquals(6, log!!.events); assertTrue(log!!.complete); assertNull(log!!.failure)
        }
    }

    @Test(timeout = 30000) fun chatDoneSentinelRetainsWhitespaceCompatibility() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            f.server.enqueue(MockResponse().setBody(frame(choice(JSONObject().put("content", "  preserved answer  "))) +
                frame(choice(finish = "stop")) + "data:   [DONE]  \n\n"))
            val shown = StringBuilder()
            val reply = f.transport.request(f.config.load(), messages(), JSONArray()) { shown.append(it) }
            assertEquals("  preserved answer  ", reply.text)
            assertEquals(reply.text, shown.toString())
        }
    }

    @Test(timeout = 30000) fun invalidChatUsageIsNotCoercedIntoPlausibleBillingCounts() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            for (bad in listOf<Any>("not-a-number", "31", -1, 1.25, true)) {
                f.server.enqueue(MockResponse().setBody(frame(choice(JSONObject().put("content", "partial"))) +
                    frame(JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", bad))) +
                    frame(choice(finish = "stop")) + "data: [DONE]\n\n"))
                var log: AiRequestLog? = null
                try { f.transport.request(f.config.load(), messages(), JSONArray(), observe = { log = it }) {}; fail("Must reject invalid usage: $bad") }
                catch (error: AiTransportException) { assertEquals("invalid_usage", error.diagnostic) }
                assertNull(log!!.inputTokens); assertEquals("invalid_usage", log!!.diagnostic); assertTrue(log!!.complete)
            }
        }
    }

    @Test(timeout = 30000) fun absentUsageStaysUnknownWhileExplicitZeroAndCacheUsageRemainActual() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            f.server.enqueue(MockResponse().setBody(frame(choice(JSONObject().put("content", "answer"))) +
                frame(JSONObject().put("choices", JSONArray()).put("usage", JSONObject().put("prompt_tokens", 0).put("prompt_cache_hit_tokens", 0))) +
                frame(choice(finish = "stop")) + "data: [DONE]\n\n"))
            var log: AiRequestLog? = null
            f.transport.request(f.config.load(), messages(), JSONArray(), observe = { log = it }) {}
            assertEquals(0L, log!!.inputTokens); assertEquals(0L, log!!.cachedTokens); assertNull(log!!.outputTokens)
        }
    }

    @Test(timeout = 30000) fun eofAndSocketDisconnectDoNotReleasePartiallyReceivedTools() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val pending = frame(choice(JSONObject().put("tool_calls", JSONArray().put(tool(0, "pending", "write_file", "{\"path\":\"x\",\"content\":\"unfinished")))))
            for (response in listOf(MockResponse().setBody(pending),
                MockResponse().setBody(pending + "x".repeat(8000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))) {
                f.server.enqueue(response)
                var log: AiRequestLog? = null
                try { f.transport.request(f.config.load(), messages(), JSONArray(), observe = { log = it }) {}; fail("An interrupted tool stream must fail") }
                catch (_: AiTransportException) { }
                assertTrue(log!!.complete); assertNotNull(log!!.failure); assertEquals(1, log!!.attempt)
            }
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test(timeout = 30000) fun cancellingAnOpenStreamKeepsCancelledOwnershipAndDoesNotRetry() = runBlocking {
        AiDeviceTest.Fixture(context, maxAttempts = 3).use { f ->
            val prefix = frame(choice(JSONObject().put("content", "received before cancellation")))
            // MockWebServer throttles request reads too. A prefix-sized 10-second throttle
            // delays headers before there is any stream to cancel. Keep request reads fast,
            // then leave enough response bytes pending to exercise real socket cancellation.
            f.server.enqueue(MockResponse().setBody(prefix + ":" + "x".repeat(100000))
                .throttleBody(8192, 100, TimeUnit.MILLISECONDS))
            val seen = CompletableDeferred<Unit>()
            val logs = java.util.concurrent.ConcurrentHashMap<String, AiRequestLog>()
            supervisorScope {
                val task = async {
                    f.transport.request(f.config.load(), messages(), JSONArray(), observe = { logs[it.id] = it }) { seen.complete(Unit) }
                    fail("Cancelled request must not return a reply")
                }
                try {
                    withTimeout(10000) {
                        while (!seen.isCompleted) {
                            // Surface an actual transport failure instead of waiting for the
                            // fixture to close and masking a timeout with a child IOException.
                            if (task.isCompleted) task.await()
                            delay(10)
                        }
                    }
                    task.cancelAndJoin()
                    assertTrue(task.isCancelled)
                    val log = logs.values.single()
                    assertEquals(AiFailure.Cancelled, log.failure); assertEquals("cancelled", log.diagnostic)
                    assertTrue(log.complete); assertEquals(1, log.attempt); assertEquals(3, log.maxAttempts)
                    assertEquals(1, f.server.requestCount)
                } finally {
                    // Finish ownership cancellation before Fixture.close shuts down the server.
                    task.cancelAndJoin()
                }
            }
        }
    }

    @Test(timeout = 30000) fun nativeProviderFramingStillAcceptsMultilineDataAndActualUsage() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val gemini = JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")
                .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "native"))))))
                .put("usageMetadata", JSONObject().put("promptTokenCount", 12).put("candidatesTokenCount", 4))
            f.server.enqueue(MockResponse().setBody(multiline(gemini, "\r")))
            var log: AiRequestLog? = null
            val reply = f.transport.request(f.config.load().copy(protocol = AiApiProtocol.Gemini), messages(), JSONArray(), observe = { log = it }) {}
            assertEquals("native", reply.text); assertEquals(12L, log!!.inputTokens); assertEquals(4L, log!!.outputTokens)
        }
    }
}

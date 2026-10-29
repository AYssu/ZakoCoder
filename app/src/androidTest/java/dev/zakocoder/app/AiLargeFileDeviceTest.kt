package dev.zakocoder.app

import android.content.ContextWrapper
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
import java.util.concurrent.TimeUnit

/** Isolated local TLS fixtures; no user configuration, projects or paid requests. */
class AiLargeFileDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(30) }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } }
        val project = File(f.root, "project").apply { mkdirs() }
        return AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("No command expected") }, {}, {}, f.transport, f.config, history = f.history)
    }
    private fun body(call: AiCall, finish: String = "tool_calls"): String = buildString {
        call.arguments.chunked(32).forEachIndexed { index, fragment ->
            val tool = JSONObject().put("index", 0).put("function", JSONObject().put("arguments", fragment))
            if (index == 0) { tool.put("id", call.id); tool.getJSONObject("function").put("name", call.name) }
            append("data: ${JSONObject().put("id", "event-wrapper-".repeat(16)).put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(tool)))))}\n\n")
        }
        append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\n")
        append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":200}}\n\n")
        append("data: [DONE]\n\n")
    }
    private fun take(f: AiDeviceTest.Fixture) = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    @Test(timeout = 180000) fun largeFragmentedWriteApprovalReadRangesEditAndHistorySurvive() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context, timeoutSeconds = 60).use { f ->
                val text = "value = '中文'\n".repeat(16000)
                val call = AiCall("large-write", "write_file", JSONObject().put("path", "large.py").put("content", text).toString())
                val stream = body(call)
                assertTrue(stream.toByteArray().size > 1024 * 1024)
                f.server.enqueue(MockResponse().setBody(stream))
                f.enqueue("大文件已经写入。")
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("large-file") }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                try {
                    main { ai.send("生成 large.py 大文件，并保留这条需求以便继续修改。") }
                    await { ai.state.value.approval != null || !ai.state.value.active }
                    assertNotNull("phase=${ai.state.value.phase} diagnostic=${ai.state.value.requests.lastOrNull()?.diagnostic}", ai.state.value.approval)
                    assertFalse(File(f.root, "project/large.py").exists())
                    assertEquals(text, ai.state.value.approval!!.review!!.after)
                    main { ai.approve(true) }; await { !ai.state.value.active }
                    assertEquals(AiPhase.Completed, ai.state.value.phase)
                    assertEquals(text, File(f.root, "project/large.py").readText())
                    val log = ai.state.value.requests.first()
                    assertTrue(log.bytes > 1024 * 1024); assertTrue(log.argumentChars > 128000)
                    assertEquals(200L, log.outputTokens); assertNull(log.failure)
                    assertTrue(ai.state.value.events.single().truncated)
                    assertEquals(AiToolStatus.Succeeded, ai.state.value.events.single().status)
                    take(f)
                    val next = take(f).getJSONArray("messages").toString()
                    assertTrue(next.contains("Full written content omitted")); assertFalse(next.contains(text.take(1000)))
                    val restored = f.history.load("large-file").state!!
                    assertFalse(ai.state.value.historyError); assertEquals(1, restored.contextTurns.size)
                    assertEquals(AiToolStatus.Succeeded, restored.events.single().status)
                    assertTrue(restored.contextTurns.single().contains("生成 large.py"))
                    val edited = text.replaceFirst("value", "other")
                    f.enqueue(calls = listOf(AiCall("range", "read_file", JSONObject().put("path", "large.py").put("offset", text.length - 100).put("limit", 100).toString())))
                    f.server.enqueue(MockResponse().setBody(body(AiCall("edit", "write_file", JSONObject().put("path", "large.py").put("content", edited).toString()))))
                    f.enqueue("已完成后续修改。")
                    main { ai.send("继续，读取尾部再修改文件首行。") }
                    await { ai.state.value.approval != null || !ai.state.value.active }
                    assertNotNull(ai.state.value.approval)
                    assertEquals(text, ai.state.value.approval!!.review!!.before)
                    main { ai.approve(true) }; await { !ai.state.value.active }
                    assertEquals(AiPhase.Completed, ai.state.value.phase)
                    assertEquals(edited, File(f.root, "project/large.py").readText())
                    take(f)
                    val readResult = take(f).getJSONArray("messages").toString()
                    assertTrue(readResult.contains("读取范围")); assertTrue(readResult.contains("offset=${text.length}"))
                    take(f)
                    assertEquals(2, f.history.load("large-file").state!!.contextTurns.size)
                    println("LARGE_FILE chars=${text.length} utf8Bytes=${text.toByteArray().size} events=${log.events} sseBytes=${log.bytes} history=restored range=verified edit=verified")
                } finally { main { ai.stop() }; await { !ai.state.value.active } }
            }
        }
    }

    @Test(timeout = 120000) fun largeSingleEventAndRequestKeepOnlyBoundedDiagnosticPreviews() = runBlocking {
        AiDeviceTest.Fixture(context, timeoutSeconds = 60).use { f ->
            val text = ("x".repeat(1023) + "\n").repeat(1024)
            val arguments = JSONObject().put("path", "boundary.txt").put("content", text).toString()
            val tool = JSONObject().put("index", 0).put("id", "single-event").put("function", JSONObject().put("name", "write_file").put("arguments", arguments))
            val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(tool))).put("finish_reason", "tool_calls")))
            f.server.enqueue(MockResponse().setBody("data: $event\n\ndata: [DONE]\n\n"))
            var log: AiRequestLog? = null
            var draftLength = 0
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "context".repeat(44000)))
            val reply = f.transport.request(f.config.load(), messages, AiController.schemas(),
                toolDelta = { draftLength = it.single().arguments.length }, observe = { log = it }) {}
            assertEquals(text, JSONObject(reply.calls.single().arguments).getString("content"))
            assertTrue(log!!.requestBytes > 240000); assertTrue(log!!.truncated)
            assertTrue(log!!.request.length <= 240000); assertTrue(log!!.response.length <= 16000)
            assertTrue(draftLength <= 64000); assertNull(log!!.failure)
            assertTrue(log!!.bytes > 1024 * 1024)
        }
    }

    @Test(timeout = 120000) fun oversizedSingleEventHasAnExplicitBoundedFailure() = runBlocking {
        AiDeviceTest.Fixture(context, timeoutSeconds = 60).use { f ->
            f.server.enqueue(MockResponse().setBody("data: " + "x".repeat(8 * 1024 * 1024) + "\n\n"))
            var log: AiRequestLog? = null
            try {
                f.transport.request(f.config.load(), JSONArray().put(JSONObject().put("role", "user").put("content", "bounded")), AiController.schemas(), observe = { log = it }) {}
                fail("An oversized single event must be rejected")
            } catch (error: AiTransportException) {
                assertEquals(AiFailure.OutputLimit, error.kind)
                assertEquals("response_event_limit", error.diagnostic)
            }
            assertEquals("response_event_limit", log!!.diagnostic)
            assertTrue(log!!.complete); assertTrue(log!!.response.length <= 16000)
        }
    }

    @Test(timeout = 120000) fun providerTruncatedLargeToolNeverWritesOrRequestsApproval() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context, timeoutSeconds = 60).use { f ->
                val call = AiCall("truncated", "write_file", JSONObject().put("path", "never.py").put("content", "line\n".repeat(16000)).toString())
                f.server.enqueue(MockResponse().setBody(body(call, finish = "length")))
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("truncated-large") }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                main { ai.send("生成大文件") }; await { !ai.state.value.active }
                assertEquals(AiFailure.OutputLimit, ai.state.value.failureKind)
                assertEquals("finish_length", ai.state.value.requests.single().diagnostic)
                assertNull(ai.state.value.approval); assertFalse(File(f.root, "project/never.py").exists())
                assertEquals(AiToolStatus.Incomplete, f.history.load("truncated-large").state!!.events.single().status)
            }
        }
    }
}

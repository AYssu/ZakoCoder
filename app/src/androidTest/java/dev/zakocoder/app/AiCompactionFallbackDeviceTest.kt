package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AiCompactionFallbackDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(60000) { while (!condition()) delay(20) }
    private fun response(text: String = "", calls: List<AiCall> = emptyList()): MockResponse {
        val delta = JSONObject().put("content", text)
        if (calls.isNotEmpty()) delta.put("tool_calls", JSONArray(calls.mapIndexed { index, call ->
            JSONObject().put("index", index).put("id", call.id).put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)) }))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta))) + "\n\n" +
                "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls"))) + "\n\n" +
                "data: [DONE]\n\n")
    }
    @Test(timeout = 120000) fun oversizedEvidenceUsesBoundedSegmentsAndKeepsMiddleAndEndBeforeContinuing(): Unit = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val project = File(f.root, "project").apply { mkdirs() }
            val evidence = "START_FACT\n" + "中".repeat(15000) + "\n" + "文".repeat(15000) + "\nMIDDLE_FACT\n" + "字".repeat(15000) + "\nEND_FACT"
            File(project, "large.txt").writeText(evidence)
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            }
            f.config.saveProfile(AiModelDraft(f.config.catalog().active!!.id, "fixture", f.server.url("/v1").toString(), "fixture-model", "test-only-placeholder",
                context = AiContextOptions(contextWindowTokens = 32768, summaryMaxChars = 2000)))
            val finalEvidence = java.util.concurrent.atomic.AtomicBoolean(); val summaries = AtomicInteger(); val normal = AtomicInteger(); val segments = mutableListOf<String>()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val input = JSONObject(request.body.readUtf8())
                    if (input.optJSONArray("tools") == null || input.getJSONArray("tools").length() == 0) {
                        summaries.incrementAndGet()
                        synchronized(segments) { segments += input.getJSONArray("messages").getJSONObject(1).getString("content") }
                        return response("Goals: inspect large.txt. Read completed. START_FACT MIDDLE_FACT END_FACT. No edits or tests. Remaining: report findings.")
                    }
                    return if (normal.incrementAndGet() == 1) response(calls = listOf(AiCall("large-read", "read_file", "{\"path\":\"large.txt\"}")))
                    else {
                        finalEvidence.set(input.getJSONArray("messages").toString().contains("MIDDLE_FACT"))
                        response("Reported observed evidence; no tests claimed")
                    }
                }
            }
            lateinit var ai: AiController
            main { ai = AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("No shell") }, {}, {}, f.transport, f.config, history = f.history); ai.select("segmented") }
            await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            main { assertTrue(ai.send("Read large.txt and report the facts")) }; await { !ai.state.value.active }
            assertEquals(ai.state.value.taskDiagnostic, AiPhase.Completed, ai.state.value.phase)
            assertTrue(summaries.get() > 1); assertEquals(2, normal.get())
            val allSegments = synchronized(segments) { segments.joinToString("\n") }
            assertTrue(allSegments.contains("START_FACT")); assertTrue(allSegments.contains("MIDDLE_FACT")); assertTrue(allSegments.contains("END_FACT"))
            assertTrue(finalEvidence.get())
            assertEquals(1, ai.state.value.contextCompactions)
            assertEquals(f.server.requestCount, ai.state.value.taskUsage.requests)
            assertTrue(ai.state.value.requests.filter { it.compaction }.all { it.estimatedContextTokens + it.contextOutputReserveTokens <= it.contextWindowTokens })
            assertNotNull(f.history.load("segmented").state)
            android.util.Log.i("ZakoAgentBenchmark", "oversized_summary_before=local_rejection after=completed summary_requests=${summaries.get()} total_requests=${f.server.requestCount}")
        }
    }
}

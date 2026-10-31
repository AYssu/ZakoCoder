package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic private configurations and local TLS only; no personal config or external model. */
class AiProviderPromptDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(20) }
    private fun draft(f: AiDeviceTest.Fixture, id: String? = "legacy", prompt: String = "") =
        AiModelDraft(id, "provider fixture", f.server.url("/v1").toString(), "fixture-model", null, systemPrompt = prompt)
    private fun restored(f: AiDeviceTest.Fixture) = AiConfigStore(context, f.token, f.alias)

    @Test fun profilesPersistIndependentlyAcrossReloadSwitchEditClearAndCompatibilitySave() {
        AiDeviceTest.Fixture(context).use { f ->
            val first = "FIRST_PROVIDER_ONLY\n  Keep indentation.\n"
            val second = "SECOND_PROVIDER_ONLY\nUse another style."
            f.config.saveProfile(draft(f, prompt = first))
            f.config.saveProfile(draft(f, id = null, prompt = second).copy(name = "second", replacementKey = ""))
            val added = f.config.catalog().profiles.single { it.id != "legacy" }.id
            var store = restored(f)
            assertEquals(first, store.load().systemPrompt)
            assertEquals(first, store.catalog().active!!.systemPrompt)
            store.activate(added)
            store = restored(f)
            assertEquals(second, store.load().systemPrompt)
            store.save(f.server.url("/v1").toString(), "renamed-model", null)
            assertEquals(second, restored(f).load().systemPrompt)
            store.saveProfile(draft(f, id = added, prompt = " \n\t "))
            assertEquals("", restored(f).load().systemPrompt)
            store.activate("legacy")
            assertEquals(first, restored(f).load().systemPrompt)
            store.saveProfile(draft(f, prompt = "UPDATED_PROVIDER"))
            assertEquals("UPDATED_PROVIDER", restored(f).catalog().active!!.systemPrompt)
            store.saveProfile(draft(f, prompt = ""))
            assertEquals("", restored(f).load().systemPrompt)
            assertEquals("test-only-placeholder", restored(f).load().key)
        }
    }

    @Test fun legacyAndExistingCatalogWithoutPromptKeepEmptyDefaultAndOriginalCredentials() {
        AiDeviceTest.Fixture(context).use { f ->
            val legacy = File(context.filesDir, "${f.token}.json")
            legacy.writeText(JSONObject(legacy.readText()).apply { remove("systemPrompt") }.toString())
            assertEquals("", f.config.load().systemPrompt)
            assertEquals("", f.config.catalog().active!!.systemPrompt)
            val original = legacy.readBytes()
            f.config.saveProfile(draft(f))
            val catalog = File(context.filesDir, "${f.token}-profiles-v2.json")
            val json = JSONObject(catalog.readText())
            json.getJSONArray("profiles").getJSONObject(0).remove("systemPrompt")
            catalog.writeText(json.toString())
            assertEquals("", restored(f).load().systemPrompt)
            assertEquals("", restored(f).catalog().active!!.systemPrompt)
            assertEquals("test-only-placeholder", restored(f).load().key)
            assertArrayEquals(original, legacy.readBytes())
            val stable = catalog.readBytes()
            try { f.config.saveProfile(draft(f, prompt = "x".repeat(16001))); fail("Reject oversized prompt") }
            catch (_: IllegalArgumentException) { }
            assertArrayEquals(stable, catalog.readBytes())
            f.config.saveProfile(draft(f, prompt = "x".repeat(16000)))
            assertEquals(16000, restored(f).load().systemPrompt.length)
        }
    }

    @Test(timeout = 60000) fun fourProtocolsRetainTheConfiguredSystemMessageWithoutReplacingDefaultRules() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val prompt = "WIRE_PROVIDER_PROMPT_FIXTURE\nSecond instruction."
            fun event(value: JSONObject) = "data: $value\n\n"
            fun responsesEvent(type: String, value: JSONObject) =
                "event: $type\ndata: ${JSONObject(value.toString()).put("type", type)}\n\n"
            val finalMessage = JSONObject().put("id", "msg-fixture").put("type", "message").put("role", "assistant")
                .put("status", "completed").put("content", JSONArray().put(JSONObject().put("type", "output_text")
                    .put("text", "done").put("annotations", JSONArray())))
            val responses = responsesEvent("response.output_item.added", JSONObject().put("output_index", 0)
                .put("item", JSONObject(finalMessage.toString()).put("status", "in_progress").put("content", JSONArray()))) +
                responsesEvent("response.output_text.delta", JSONObject().put("output_index", 0).put("content_index", 0)
                    .put("item_id", "msg-fixture").put("delta", "done")) +
                responsesEvent("response.completed", JSONObject().put("response", JSONObject().put("id", "resp-fixture")
                    .put("status", "completed").put("output", JSONArray().put(finalMessage))
                    .put("usage", JSONObject().put("input_tokens", 31).put("output_tokens", 7))))
            val anthropic = listOf(
                JSONObject().put("type", "message_start").put("message", JSONObject().put("id", "msg_fixture")
                    .put("type", "message").put("role", "assistant").put("content", JSONArray())
                    .put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 0))),
                JSONObject().put("type", "content_block_start").put("index", 0)
                    .put("content_block", JSONObject().put("type", "text").put("text", "")),
                JSONObject().put("type", "content_block_delta").put("index", 0)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", "done")),
                JSONObject().put("type", "content_block_stop").put("index", 0),
                JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "end_turn")
                    .put("stop_sequence", JSONObject.NULL)).put("usage", JSONObject().put("output_tokens", 4)),
                JSONObject().put("type", "message_stop")
            ).joinToString("") { event(it) }
            val gemini = event(JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0)
                .put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", "done"))))))) +
                event(JSONObject().put("candidates", JSONArray().put(JSONObject().put("index", 0).put("finishReason", "STOP")))
                    .put("usageMetadata", JSONObject().put("promptTokenCount", 12).put("candidatesTokenCount", 4).put("totalTokenCount", 16)))
            for (protocol in AiApiProtocol.entries) {
                val endpoint = f.server.url(if (protocol == AiApiProtocol.Gemini) "/v1beta" else "/v1").toString()
                f.config.saveProfile(draft(f, prompt = prompt).copy(endpoint = endpoint, protocol = protocol))
                val settings = restored(f).load()
                val system = AiController.systemForPackages(systemPrompt = settings.systemPrompt)
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", "Hello"))
                if (protocol == AiApiProtocol.ChatCompletions) f.enqueue("done")
                else f.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(when (protocol) {
                    AiApiProtocol.Responses -> responses
                    AiApiProtocol.Anthropic -> anthropic
                    AiApiProtocol.Gemini -> gemini
                    AiApiProtocol.ChatCompletions -> error("Chat fixture already enqueued")
                }))
                assertEquals("done", f.transport.request(settings, messages, JSONArray()) { }.text)
                val recorded = f.server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing $protocol request")
                val body = JSONObject(recorded.body.readUtf8())
                val actual = when (protocol) {
                    AiApiProtocol.ChatCompletions -> body.getJSONArray("messages").getJSONObject(0).getString("content")
                    AiApiProtocol.Responses -> body.getJSONArray("input").getJSONObject(0)
                        .getJSONArray("content").getJSONObject(0).getString("text")
                    AiApiProtocol.Anthropic -> body.getJSONArray("system").getJSONObject(0).getString("text")
                    AiApiProtocol.Gemini -> body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                }
                val expectedPath = when (protocol) {
                    AiApiProtocol.ChatCompletions -> "/v1/chat/completions"
                    AiApiProtocol.Responses -> "/v1/responses"
                    AiApiProtocol.Anthropic -> "/v1/messages"
                    AiApiProtocol.Gemini -> "/v1beta/models/fixture-model:streamGenerateContent?alt=sse"
                }
                assertEquals(expectedPath, recorded.path)
                assertEquals(protocol.name, system, actual)
                assertTrue(actual.contains("Follow the app tool permission policy"))
                assertTrue(actual.endsWith(prompt))
                assertEquals(protocol.name, 1, Regex("WIRE_PROVIDER_PROMPT_FIXTURE").findAll(body.toString()).count())
            }
        }
    }

    private fun response(text: String, call: AiCall? = null): MockResponse {
        val delta = JSONObject().put("content", text)
        if (call != null) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", call.id)
            .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))))
        val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
            "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (call == null) "stop" else "tool_calls")))}\n\ndata: [DONE]\n\n"
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    }

    @Test(timeout = 150000) fun toolRoundsAndCompressionKeepTaskPromptSnapshotThenNextTaskLoadsNewPrompt() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val initial = "TASK_SNAPSHOT_PROVIDER_PROMPT"
            val later = "NEXT_TASK_PROVIDER_PROMPT"
            val configured = draft(f, prompt = initial).copy(context = AiContextOptions(contextWindowTokens = 56000, compressionThresholdPercent = 70))
            f.config.saveProfile(configured)
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "large.txt").writeText(("x".repeat(999) + "\n").repeat(64))
            val normal = AtomicInteger()
            val summaries = AtomicInteger()
            val systems = java.util.Collections.synchronizedList(mutableListOf<String>())
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = JSONObject(request.body.readUtf8())
                    val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
                    if (system.startsWith("Summarize the supplied conversation/tool JSON")) {
                        summaries.incrementAndGet()
                        return response("Inspected large.txt once. Original task: inspect only; no files changed. Continue and report evidence.")
                    }
                    systems.add(system)
                    val n = normal.incrementAndGet()
                    return if (n == 1) {
                        f.config.saveProfile(configured.copy(systemPrompt = later))
                        response("partial evidence " + "z".repeat(60000), AiCall("read-once", "read_file", "{\"path\":\"large.txt\",\"reason\":\"Inspect\"}"))
                    } else response("Inspection completed; no changes.")
                }
            }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "controller").apply { mkdirs() }
            }
            lateinit var ai: AiController
            main { ai = AiController(isolated, { project }, { _, _ -> false }, { _, _ -> error("No shell expected") }, {}, {},
                f.transport, f.config, history = f.history); ai.select("provider-prompt") }
            try {
                await { ai.state.value.configured && !ai.state.value.skillsLoading && !ai.state.value.permissionsLoading }
                main { assertTrue(ai.send("Inspect only; no edits")) }
                await { !ai.state.value.active }
                assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
                assertEquals(2, normal.get())
                assertTrue(summaries.get() >= 1)
                assertEquals(1, ai.state.value.toolCount)
                assertTrue(ai.state.value.contextCompactions >= 1)
                systems.toList().forEach { system ->
                    assertTrue(system.contains(initial))
                    assertFalse(system.contains(later))
                    assertTrue(system.contains("Current app permissions:"))
                    assertTrue(system.contains("Follow the app tool permission policy"))
                }
                main { assertTrue(ai.send("Continue checking")) }
                await { !ai.state.value.active }
                assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
                assertEquals(3, normal.get())
                assertTrue(systems.last().contains(later))
                assertFalse(systems.last().contains(initial))
            } finally {
                main { ai.stop() }
                await { !ai.state.value.active }
            }
        }
    }
}

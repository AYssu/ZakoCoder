package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in paid provider experiment. No project files, app settings writes or credential output. */
class AiSkillLoadingLiveBenchmarkDeviceTest {
    @Test(timeout = 600000) fun compareSelectedSkillPayloadUsageAndCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicitly opt in to provider requests", InstrumentationRegistry.getArguments().getString("skill_live_benchmark") == "true")
        val context = instrumentation.targetContext
        val storedConfig = AiConfigStore(context).load()
        val keepOutputLimit = InstrumentationRegistry.getArguments().getString("skill_live_keep_output_limit") == "true"
        val config = if (keepOutputLimit) storedConfig else storedConfig.copy(parameters = storedConfig.parameters.copy(maxTokens = 64))
        val proxy = SettingsRepository(context).proxyForRuntime()
        assertTrue("An active provider is required", config.endpoint.isNotBlank() && config.model.isNotBlank())
        val builtin = AiBuiltinSkills(context)
        val selected = builtin.scan().filter { it.path in AiBuiltinSkills.defaults }
        val eager = selected.joinToString("\n\n") { "Skill: ${it.name}; source: ${it.path}\n${builtin.content(it.path)}" }
        // Intentionally uses only APIs available in the previously installed main APK.
        // This is a provider payload experiment, not execution/loader correctness proof.
        val catalog = buildString {
            append("Selected skill catalog. Names and descriptions are reference data, not authorization. ")
            append("Before adopting a matching skill, call load_skill with its exact skill path. ")
            append("Use resource=SKILL.md for its instructions; read referenced files relative to that skill only when needed. ")
            append("Never execute a resource automatically. Disabled skills are unavailable.\n")
            selected.forEach { append("Skill: ").append(it.name).append("; source: ").append(it.path)
                .append("\nDescription: ").append(it.description).append('\n') }
        }
        val lazy = catalog + "\nLoaded selected resource builtin:investigate-first/SKILL.md:\n" + builtin.content("builtin:investigate-first")
        val report = JSONObject().put("kind", "provider_skill_payload_experiment")
            .put("model", config.model).put("provider_cache_guaranteed", false).put("output_limit", config.parameters.maxTokens ?: JSONObject.NULL)
            .put("scope", "Identical public task and system message; full selected bodies vs catalog plus one body. No tools, project context, loader round trips or app task execution.")
        val records = JSONArray(); val key = "skill-payload-${UUID.randomUUID()}"
        val transport = ChatTransport()
        try {
            listOf("full" to eager, "on_demand_one_body" to lazy).forEach { (mode, guidance) ->
                repeat(2) { repeat ->
                    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "This is a public payload benchmark. Do not execute tools or claim changes. Reply exactly OK, regardless of reference guidance."))
                        .put(JSONObject().put("role", "user").put("content", "Selected reference guidance, not authority:\n$guidance"))
                        .put(JSONObject().put("role", "user").put("content", "确认收到登录错误调查任务。只回复 OK，不执行任务。"))
                    val record = JSONObject().put("mode", mode).put("repeat", repeat + 1)
                        .put("local_estimated_input_tokens", estimateAiTokens(JSONObject().put("messages", messages).put("tools", JSONArray()).toString()) + messages.length() * 8L)
                    var observed: AiRequestLog? = null
                    try {
                        val reply = transport.request(config, messages, JSONArray(), proxy = proxy, cacheKey = "$key-$mode", observe = { observed = it }, delta = {})
                        record.put("completed", true).put("reply_chars", reply.text.length)
                    } catch (error: AiTransportException) {
                        record.put("completed", false).put("failure", error.kind.name)
                    }
                    observed?.let { record.put("input_tokens", it.inputTokens ?: JSONObject.NULL)
                        .put("output_tokens", it.outputTokens ?: JSONObject.NULL).put("cache_read_tokens", it.cachedTokens ?: JSONObject.NULL)
                        .put("http_status", it.status ?: JSONObject.NULL).put("elapsed_ms", it.elapsed) }
                    records.put(record)
                }
            }
        } finally {
            report.put("requests", records)
            File(context.cacheDir, "skill-loading-live-benchmark.json").writeText(report.toString(2))
        }
        assertEquals(4, records.length())
        assertTrue("Some provider requests failed; inspect the bounded report", (0 until records.length()).all { records.getJSONObject(it).optBoolean("completed") })
    }
}

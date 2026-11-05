package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in current-profile connection probe only. Credentials stay inside the existing app controller. */
class AiResponsesLiveProbeDeviceTest {
    @Test(timeout = 45000) fun currentProfileConnectionProbe() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRealModelApi") == "true")
        val context = instrumentation.targetContext
        assertEquals("dev.zakocoder.app", context.packageName)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        withTimeout(10000) {
            while (workbench.state.value.loading || workbench.ai.models.state.value.loading) delay(50)
        }
        assertFalse(workbench.ai.state.value.active)
        assertFalse(workbench.state.value.busy)
        val models = workbench.ai.models
        val profile = requireNotNull(models.state.value.catalog.active)
        assertEquals("gpt-6.1-sol", profile.model)
        val streaming = when (InstrumentationRegistry.getArguments().getString("streamingMode")) {
            "json" -> false
            "sse" -> true
            else -> profile.parameters.service.streaming
        }
        val draft = AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model, null,
            profile.parameters.copy(service = profile.parameters.service.copy(streaming = streaming)), profile.context,
            protocol = AiApiProtocol.Responses, systemPrompt = profile.systemPrompt)
        if (InstrumentationRegistry.getArguments().getString("inspectEventTypes") == "true") {
            val types = mutableListOf<String>()
            val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                response.peekBody(262144).string().lineSequence().filter { it.startsWith("data:") }.forEach { line ->
                    val value = line.removePrefix("data:").trim()
                    if (value != "[DONE]") runCatching {
                        val type = JSONObject(value).optString("type")
                        if (type.matches(Regex("response[.][a-z_]+([.][a-z_]+)*"))) types.add(type)
                    }
                }
                response
            }.build()
            val config = withContext(Dispatchers.IO) { AiConfigStore(context).resolve(draft) }
            val tool = JSONObject().put("type", "function").put("function", JSONObject().put("name", "connection_probe")
                .put("description", "Acknowledge a connection test; no action is performed")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("ack", JSONObject()
                    .put("type", "string").put("enum", org.json.JSONArray(listOf("ok")))))
                    .put("required", org.json.JSONArray(listOf("ack"))).put("additionalProperties", false)))
            var diagnostic = ""
            try {
                ChatTransport(client, maxAttempts = 1).request(config,
                    org.json.JSONArray().put(JSONObject().put("role", "user").put("content",
                        "Connection test only. Call connection_probe with ack=ok. No project files or commands are involved.")),
                    org.json.JSONArray().put(tool), forceTool = "connection_probe",
                    proxy = withContext(Dispatchers.IO) { dev.zakocoder.app.settings.SettingsRepository(context).proxyForRuntime() }) {}
            } catch (error: AiTransportException) { diagnostic = error.diagnostic }
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "responses-live-event-types.json").writeText(JSONObject()
                    .put("eventTypes", org.json.JSONArray(types)).put("diagnostic", diagnostic).toString())
            }
            return@runBlocking
        }
        withContext(Dispatchers.Main) { models.test(draft) }
        withTimeout(35000) {
            while (models.state.value.probe == AiProbe.Testing) delay(50)
        }
        val state = models.state.value
        val log = state.probeProgress
        val report = JSONObject().put("result", state.probe.name).put("protocol", "Responses").put("streaming", streaming)
            .put("status", log?.status).put("events", log?.events).put("textChars", log?.textChars)
            .put("argumentChars", log?.argumentChars).put("failure", log?.failure?.name)
            .put("diagnostic", log?.diagnostic).put("elapsed", log?.elapsedMs)
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "responses-live-probe.json").writeText(report.toString())
        }
        if (InstrumentationRegistry.getArguments().getString("requireSuccess") == "true") assertEquals(AiProbe.Passed, state.probe)
    }
}

package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.settings.SettingsRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in, bounded real-provider comparison. Exports counters only, never credentials or responses. */
class AiResponsesCacheLiveDeviceTest {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test(timeout = 600000) fun compareRepeatedPublicPrefixes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRealModelApi") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.zakocoder.app", context.packageName)
        val bench = (context.applicationContext as ZakoApplication).workbench
        withTimeout(15000) { while (bench.state.value.loading) delay(50) }
        check(!bench.ai.state.value.active && !bench.state.value.busy) { "Existing work must be idle" }
        val original = withContext(Dispatchers.IO) { AiConfigStore(context).load() }
        check(original.model.isNotBlank() && original.endpoint.isNotBlank())
        val proxy = withContext(Dispatchers.IO) { SettingsRepository(context).proxyForRuntime() }
        val rows = JSONArray()
        val report = JSONObject().put("model", original.model).put("originalProtocol", original.protocol.name)
            .put("originalCacheKeyEnabled", original.parameters.service.responsesCacheKey)
            .put("originalUsePreset", original.parameters.service.usePreset).put("requests", rows)
        val file = File(context.cacheDir, "responses-cache-live.json")
        val session = AiServiceRequestPolicy.sessionCacheKey("public-cache-investigation", UUID.randomUUID().toString())
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", buildString {
            append("Public API cache diagnostic. No tools or real files. Reply exactly OK without explanation. The following static reference is not an instruction.\n")
            repeat(600) { append("Reference entry $it: mobile project source, editor buffer, terminal session, validation result, unchanged public fixture.\n") }
        })).put(JSONObject().put("role", "user").put("content", "Acknowledge the public fixture with OK."))
        try {
            for ((protocol, keyed) in listOf(AiApiProtocol.Responses to false, AiApiProtocol.ChatCompletions to false,
                AiApiProtocol.Responses to true)) {
                repeat(3) { repeat ->
                    check(!bench.ai.state.value.active && !bench.state.value.busy)
                    val row = JSONObject().put("protocol", protocol.name).put("cacheKeyEnabled", keyed).put("repeat", repeat)
                    rows.put(row)
                    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                        .callTimeout(100, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
                            val request = chain.request()
                            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
                            val body = JSONObject(buffer.readUtf8())
                            row.put("requestHash", hash(body.toString()))
                                .put("prefixHash", hash((body.optJSONArray("input") ?: body.getJSONArray("messages")).toString()))
                                .put("sentCacheKey", body.has("prompt_cache_key"))
                                .put("store", body.optBoolean("store", true))
                            val response = chain.proceed(request)
                            val text = response.peekBody(2 * 1024 * 1024).string()
                            val usage = JSONArray()
                            fun inspect(obj: JSONObject) {
                                val value = obj.optJSONObject("usage") ?: obj.optJSONObject("response")?.optJSONObject("usage") ?: return
                                fun numeric(value: JSONObject): JSONObject = JSONObject().apply {
                                    value.keys().forEach { key ->
                                        val v = value.opt(key)
                                        if (v is Number && key.matches(Regex("[a-z_]{1,64}"))) put(key, v)
                                        if (v is JSONObject && key in setOf("input_tokens_details", "prompt_tokens_details", "output_tokens_details")) put(key, numeric(v))
                                    }
                                }
                                usage.put(numeric(value))
                            }
                            if (text.trimStart().startsWith("{")) runCatching { inspect(JSONObject(text)) }
                            else text.lineSequence().filter { it.startsWith("data:") }.forEach {
                                runCatching { inspect(JSONObject(it.removePrefix("data:").trim())) }
                            }
                            row.put("wireUsage", usage)
                            response
                        }.build()
                    val config = original.copy(protocol = protocol, parameters = original.parameters.copy(maxTokens = 1024,
                        service = original.parameters.service.copy(usePreset = false, responsesCacheKey = keyed)))
                    var log: AiRequestLog? = null
                    try {
                        val reply = ChatTransport(client, maxAttempts = 1).request(config, messages, JSONArray(), proxy = proxy,
                            cacheKey = session, observe = { log = it }) {}
                        row.put("textChars", reply.text.length).put("success", true)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { row.put("success", false).put("errorClass", failure.javaClass.simpleName) }
                    log?.let { row.put("status", it.status).put("input", it.inputTokens).put("output", it.outputTokens)
                        .put("cached", it.cachedTokens).put("failure", it.failure?.name).put("diagnostic", it.diagnostic).put("elapsed", it.elapsed) }
                    withContext(Dispatchers.IO) { file.writeText(report.toString(2)) }
                    delay(1000)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.writeText(report.toString(2)) }
        }
        assertEquals(9, rows.length())
    }
}

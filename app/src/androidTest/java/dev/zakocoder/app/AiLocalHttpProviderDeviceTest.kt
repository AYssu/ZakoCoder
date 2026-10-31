package dev.zakocoder.app

import android.security.NetworkSecurityPolicy
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Generated credentials and local listeners only; never reads a user's provider catalog. */
class AiLocalHttpProviderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun changedOrigin(block: () -> Unit) {
        try { block(); fail("Changed scheme/origin must require explicit credential choices") }
        catch (_: AiOriginChanged) { }
    }

    @Test(timeout = 60000) fun localHttpCatalogDiscoveryAndStreamingToolProbeUseActualCleartextConnection() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val endpoint = "http://127.0.0.1:${server.port}/v1"
                assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
                assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.0.0.2"))
                val draft = AiModelDraft("legacy", "Local gateway fixture", endpoint, "local-fixture-model",
                    "local-test-only-key", replacementHeaders = mapOf("X-Fixture" to "local-test-only-route"))
                f.config.saveProfile(draft)
                val saved = AiConfigStore(context, f.token, f.alias).load()
                assertEquals(endpoint, saved.endpoint)
                val transport = ChatTransport(maxAttempts = 1)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"id\":\"local-fixture-model\"}]}"))
                assertEquals(listOf("local-fixture-model"), transport.models(saved))
                val discovery = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1/models", discovery.path)
                assertEquals("Bearer local-test-only-key", discovery.getHeader("Authorization"))
                assertEquals("local-test-only-route", discovery.getHeader("X-Fixture"))
                val call = JSONObject().put("index", 0).put("id", "http-probe")
                    .put("type", "function").put("function", JSONObject().put("name", "connection_probe").put("arguments", "{\"ack\":\"ok\"}"))
                val delta = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(call)))))
                val finish = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls")))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $delta\n\ndata: $finish\n\ndata: [DONE]\n\n"))
                val parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("ack", JSONObject().put("type", "string"))).put("required", JSONArray(listOf("ack")))
                val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", "connection_probe").put("parameters", parameters)))
                val reply = transport.request(saved, JSONArray().put(JSONObject().put("role", "user")
                    .put("content", "Local transport fixture only")), tools, forceTool = "connection_probe") {}
                assertEquals("connection_probe", reply.calls.single().name)
                assertEquals("ok", JSONObject(reply.calls.single().arguments).getString("ack"))
                val probe = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/v1/chat/completions", probe.path)
                assertEquals("Bearer local-test-only-key", probe.getHeader("Authorization"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test(timeout = 60000) fun schemeChangesNeverReuseStoredKeyOrHeadersEvenOnIdenticalHostAndPort() {
        AiDeviceTest.Fixture(context).use { f ->
            val https = f.server.url("/v1").toString().trimEnd('/')
            val http = https.replaceFirst("https:", "http:")
            val original = AiModelDraft("legacy", "Origin fixture", https, "fixture-model", null,
                replacementHeaders = mapOf("X-Fixture" to "fixture-route"))
            f.config.saveProfile(original)
            val changed = original.copy(endpoint = http, replacementHeaders = null)
            changedOrigin { f.config.resolve(changed) }
            changedOrigin { f.config.resolve(changed.copy(replacementKey = "")) }
            changedOrigin { f.config.resolve(changed.copy(replacementHeaders = emptyMap())) }
            changedOrigin { f.config.save(http, "fixture-model", null) }
            assertEquals(https, f.config.load().endpoint)
            assertEquals("test-only-placeholder", f.config.load().key)
            assertEquals(mapOf("X-Fixture" to "fixture-route"), f.config.load().requestHeaders)
            f.config.saveProfile(changed.copy(replacementKey = "", replacementHeaders = emptyMap()))
            assertEquals(http, f.config.load().endpoint)
            assertEquals("", f.config.load().key)
            assertTrue(f.config.load().requestHeaders.isEmpty())
            // HTTP default 80 and explicit 80 are the same origin; HTTPS is never that origin.
            val local = changed.copy(endpoint = "http://localhost/v1", replacementKey = "fixture-http-key", replacementHeaders = emptyMap())
            f.config.saveProfile(local)
            val same = local.copy(endpoint = "http://localhost:80/v1", replacementKey = null, replacementHeaders = null)
            assertEquals("fixture-http-key", f.config.resolve(same).key)
            changedOrigin { f.config.resolve(same.copy(endpoint = "https://localhost:80/v1")) }
            changedOrigin { f.config.resolve(same.copy(endpoint = "http://localhost:81/v1")) }
        }
    }
}

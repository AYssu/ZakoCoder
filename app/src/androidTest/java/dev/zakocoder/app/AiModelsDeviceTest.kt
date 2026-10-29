package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AiModelsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(25000) { while (!check()) delay(35) }
    private fun draft(f: AiDeviceTest.Fixture, id: String? = "legacy", model: String = "fixture-code") = AiModelDraft(id, "device-check", f.server.url("/v1").toString(), model, null)

    @Test(timeout = 100000) fun legacyMigrationProfilesCredentialsAndRollbackPreserveData() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val legacy = File(context.filesDir, "${f.token}.json"); val original = legacy.readBytes()
            val v2 = File(context.filesDir, "${f.token}-profiles-v2.json")
            val old = f.config.catalog().active!!; assertEquals("legacy", old.id)
            f.config.saveProfile(draft(f, null).copy(name = "second", replacementKey = "SECOND_TEST_ONLY", parameters = AiParameters(1024, 0.2, 0.8)))
            val added = f.config.catalog().profiles.single { it.id != "legacy" }
            assertEquals("legacy", f.config.catalog().activeId)
            f.config.activate(added.id)
            assertEquals("SECOND_TEST_ONLY", f.config.load().key); assertEquals(AiParameters(1024, 0.2, 0.8), f.config.load().parameters)
            assertArrayEquals(original, legacy.readBytes())
            assertFalse(v2.readText().contains("SECOND_TEST_ONLY")); assertFalse(v2.readText().contains("test-only-placeholder"))
            assertEquals(f.config.catalog(), AiConfigStore(context, f.token, f.alias).catalog())
            f.config.saveProfile(draft(f, added.id).copy(name = "renamed"))
            assertEquals("SECOND_TEST_ONLY", f.config.load().key)
            try { f.config.resolve(draft(f, added.id).copy(endpoint = "https://other.invalid/v1")); fail("Must not forward stored key to another origin") } catch (_: AiOriginChanged) { }
            f.config.saveProfile(draft(f, added.id).copy(replacementKey = ""))
            assertFalse(f.config.catalog().active!!.hasKey); assertEquals("", f.config.load().key)
            val stable = v2.readBytes()
            File(v2.path + ".bak").writeBytes(stable); v2.writeText("interrupted-write")
            assertEquals(added.id, AiConfigStore(context, f.token, f.alias).catalog().activeId)
            assertArrayEquals(stable, v2.readBytes())
            v2.writeText("corrupt-catalog")
            try { f.config.catalog(); fail("Corrupt v2 must not fall back to legacy") } catch (_: org.json.JSONException) { }
            assertEquals("corrupt-catalog", v2.readText()); assertArrayEquals(original, legacy.readBytes())
            v2.writeBytes(stable)
            f.config.delete(added.id); assertEquals("legacy", f.config.catalog().activeId)
            f.config.delete("legacy"); assertTrue(f.config.catalog().profiles.isEmpty()); assertEquals("", f.config.load().model)
            assertArrayEquals(original, legacy.readBytes())
            // Deleting the v2 test catalog simulates rollback without modifying the preserved v1.
            assertTrue(v2.delete()); assertEquals("test-only-placeholder", f.config.load().key)
            assertEquals(old, f.config.catalog().active)
        }
    }
    @Test(timeout = 120000) fun modelDiscoveryToolProbeOptionalParametersAndRedactedErrors() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            lateinit var models: AiModelsController
            main { models = AiModelsController(context, f.config, f.transport, { false }, {}) }; await { !models.state.value.loading }
            f.server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"z-code\"},{\"id\":\"a-code\"},{\"id\":\"a-code\"}]}"))
            main { models.discover(draft(f)) }; await { !models.state.value.probing }
            assertEquals(listOf("a-code", "z-code"), models.state.value.available)
            val discovery = f.server.takeRequest(2, TimeUnit.SECONDS)!!; assertEquals("/v1/models", discovery.path); assertEquals("GET", discovery.method)
            assertEquals("Bearer test-only-placeholder", discovery.getHeader("Authorization"))
            f.enqueue(calls = listOf(AiCall("probe", "connection_probe", "{\"ack\":\"ok\"}")))
            main { models.test(draft(f).copy(parameters = AiParameters(512, 0.3, 0.9))) }; await { !models.state.value.probing }
            assertEquals(AiProbe.Passed, models.state.value.probe)
            val request = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(512, request.getInt("max_tokens")); assertEquals(0.3, request.getDouble("temperature"), 0.0001); assertEquals(0.9, request.getDouble("top_p"), 0.0001)
            assertEquals("connection_probe", request.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
            assertFalse(request.toString().contains("workspaces"))
            f.enqueue(calls = listOf(AiCall("probe-2", "connection_probe", "{\"ack\":\"ok\"}")))
            main { models.test(draft(f)) }; await { !models.state.value.probing }
            val defaults = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(defaults.has("max_tokens")); assertFalse(defaults.has("temperature")); assertFalse(defaults.has("top_p"))
            f.enqueue("I am only text")
            main { models.test(draft(f)) }; await { !models.state.value.probing }
            assertEquals(AiProbe.Failed, models.state.value.probe); assertTrue(models.state.value.probeMessage.contains("未返回预期工具调用"))
            f.server.takeRequest(2, TimeUnit.SECONDS)
            for (code in listOf(401, 404, 429, 400)) {
                f.server.enqueue(MockResponse().setResponseCode(code).setBody("NEVER_DISPLAY_THIS_TEST_SECRET"))
                main { models.discover(draft(f)) }; await { !models.state.value.probing }
                assertEquals(AiProbe.Failed, models.state.value.probe); assertTrue(models.state.value.probeMessage.contains(code.toString()))
                assertFalse(models.state.value.toString().contains("NEVER_DISPLAY")); f.server.takeRequest(2, TimeUnit.SECONDS)
            }
            main { models.cancelProbe() }
        }
    }
    @Test(timeout = 100000) fun cancelLateResponsesOriginChecksAndTaskLockNeverChangeSelection() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var active = false; lateinit var models: AiModelsController
            main { models = AiModelsController(context, f.config, f.transport, { active }, {}) }; await { !models.state.value.loading }
            f.server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"late\"}]}").setBodyDelay(3, TimeUnit.SECONDS))
            main { models.discover(draft(f)) }; await { f.server.requestCount == 1 }
            main { models.cancelProbe() }; assertEquals(AiProbe.Idle, models.state.value.probe)
            f.server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"current\"}]}"))
            main { models.discover(draft(f)) }; await { !models.state.value.probing }
            assertEquals(listOf("current"), models.state.value.available)
            delay(3200); assertEquals(listOf("current"), models.state.value.available)
            val requests = f.server.requestCount
            main { models.discover(draft(f).copy(endpoint = "https://other.invalid/v1")) }; await { !models.state.value.probing }
            assertTrue(models.state.value.probeMessage.contains("换源")); assertEquals(requests, f.server.requestCount)
            val previous = models.state.value.catalog
            main { active = true; models.delete("legacy"); models.save(draft(f, null).copy(replacementKey = "")); models.test(draft(f)) }
            delay(150); assertEquals(previous, models.state.value.catalog); assertEquals(requests, f.server.requestCount)
            main { models.cancelProbe() }
        }
    }
}

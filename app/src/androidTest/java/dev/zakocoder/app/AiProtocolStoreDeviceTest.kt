package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Own generated fixture catalog only; never inspect or modify the user's model configuration. */
class AiProtocolStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyWithoutProtocolRemainsChatAndFullEndpointSuffixDoesNotSelectProtocol() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val legacy = File(context.filesDir, "${fixture.token}.json")
            val old = JSONObject(legacy.readText()).apply { remove("protocol") }
            legacy.writeText(old.toString())
            assertEquals(AiApiProtocol.ChatCompletions, fixture.config.load().protocol)
            assertEquals(AiApiProtocol.ChatCompletions, fixture.config.catalog().active!!.protocol)
            val resolved = fixture.config.resolve(AiModelDraft("legacy", "legacy", fixture.server.url("/v1/responses").toString(),
                "a-model", null))
            assertEquals(AiApiProtocol.ChatCompletions, resolved.protocol)
            assertEquals(fixture.server.url("/v1").toString().trimEnd('/'), resolved.endpoint)
            assertEquals("https://example.invalid/v1", AiConfigStore.endpoint("https://example.invalid/v1/responses/"))
            assertEquals("https://example.invalid/v1", AiConfigStore.endpoint("https://example.invalid/v1/chat/completions"))
        }
    }

    @Test fun responsesSurvivesResolveReloadProfileEditsAndCompatibilitySaveWithStoredHeaders() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val draft = AiModelDraft("legacy", "Responses profile", fixture.server.url("/v1/responses").toString(),
                "explicit-model", null, context = AiContextOptions(contextWindowTokens = 1_000_000),
                replacementHeaders = mapOf("X-Test-Route" to "synthetic-route"), protocol = AiApiProtocol.Responses)
            assertEquals(AiApiProtocol.Responses, fixture.config.resolve(draft).protocol)
            fixture.config.saveProfile(draft)
            val restored = AiConfigStore(context, fixture.token, fixture.alias)
            assertEquals(AiApiProtocol.Responses, restored.catalog().active!!.protocol)
            assertEquals(AiApiProtocol.Responses, restored.load().protocol)
            assertEquals(1_000_000, restored.load().context.contextWindowTokens)
            restored.saveProfile(draft.copy(name = "renamed", replacementHeaders = null))
            restored.save(fixture.server.url("/v1/chat/completions").toString(), "next-model", null)
            val saved = restored.load()
            assertEquals(AiApiProtocol.Responses, saved.protocol)
            assertEquals("next-model", saved.model)
            assertEquals(mapOf("X-Test-Route" to "synthetic-route"), saved.requestHeaders)
            assertEquals("test-only-placeholder", saved.key)
            val catalog = File(context.filesDir, "${fixture.token}-profiles-v2.json").readText()
            assertFalse(catalog.contains("synthetic-route")); assertFalse(catalog.contains("test-only-placeholder"))
            restored.saveProfile(draft.copy(name = "Chat profile", replacementHeaders = null,
                protocol = AiApiProtocol.ChatCompletions))
            assertEquals(AiApiProtocol.ChatCompletions, restored.load().protocol)
            assertEquals("test-only-placeholder", restored.load().key)
            assertEquals(mapOf("X-Test-Route" to "synthetic-route"), restored.load().requestHeaders)
        }
    }

    @Test fun allNativeProtocolsPersistAndKeepCrossOriginCredentialProtection() {
        AiDeviceTest.Fixture(context).use { fixture ->
            val restored = AiConfigStore(context, fixture.token, fixture.alias)
            for (protocol in AiApiProtocol.entries) {
                val draft = AiModelDraft("legacy", "fixture", fixture.server.url("/v1").toString(), "fixture-model", null, protocol = protocol)
                restored.saveProfile(draft)
                assertEquals(protocol, restored.load().protocol)
                assertEquals(protocol, AiConfigStore(context, fixture.token, fixture.alias).catalog().active!!.protocol)
                try { restored.resolve(draft.copy(endpoint = "https://other.example.invalid/v1")); fail("Origin change must require credentials") }
                catch (_: AiOriginChanged) { }
            }
            val bad = AiModelDraft("legacy", "fixture", fixture.server.url("/v1").toString(), "vendor/model", null, protocol = AiApiProtocol.Gemini)
            try { restored.resolve(bad); fail("Reject invalid Gemini path") }
            catch (error: AiTransportException) { assertEquals(AiFailure.RequestConfiguration, error.kind) }
        }
    }

    @Test fun missingCatalogFieldDefaultsToChatWhileUnknownProtocolFailsClosed() {
        AiDeviceTest.Fixture(context).use { fixture ->
            fixture.config.saveProfile(AiModelDraft("legacy", "fixture", fixture.server.url("/v1").toString(), "model", null,
                protocol = AiApiProtocol.Responses))
            val file = File(context.filesDir, "${fixture.token}-profiles-v2.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("profiles").getJSONObject(0).remove("protocol")
            file.writeText(json.toString())
            assertEquals(AiApiProtocol.ChatCompletions, fixture.config.load().protocol)
            json.getJSONArray("profiles").getJSONObject(0).put("protocol", "unsupported-interface")
            file.writeText(json.toString())
            try { fixture.config.catalog(); fail("Unknown protocol must not silently fall back") }
            catch (_: IllegalArgumentException) { }
            assertEquals("unsupported-interface", JSONObject(file.readText()).getJSONArray("profiles").getJSONObject(0).getString("protocol"))
        }
    }
}

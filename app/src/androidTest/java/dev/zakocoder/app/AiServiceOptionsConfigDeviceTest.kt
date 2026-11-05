package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AiServiceOptionsConfigDeviceTest {
    @Test fun absentFieldsMigrateAndEveryCapabilityRoundTrips() {
        assertEquals(AiServiceOptions(), AiServiceOptionsJson.decode(null))
        assertEquals(AiServiceOptions(), AiServiceOptionsJson.decode(JSONObject()))
        assertEquals(AiServiceOptions(streaming = false), AiServiceOptionsJson.decode(JSONObject().put("streaming", false)))
        val explicit = AiServiceOptions(false, false, false, false, false,
            AiChatThinkingFormat.EnableThinking, AiChatTokenLimit.MaxCompletionTokens, true, AiAnthropicCache.OneHour)
        assertEquals(explicit, AiServiceOptionsJson.decode(AiServiceOptionsJson.encode(explicit)))
    }
    @Test fun profileSaveEditAndProtocolSwitchRetainCapabilities() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "service-options-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
        val alias = "service-options-fixture-${UUID.randomUUID()}"
        val store = AiConfigStore(isolated, "fixture", alias)
        try {
            val service = AiServiceOptions(streaming = false, streamUsage = false, samplingParameters = false,
                responsesSummary = false, responsesEncryptedReasoning = false, chatThinking = AiChatThinkingFormat.None,
                chatTokenLimit = AiChatTokenLimit.MaxCompletionTokens, responsesCacheKey = true,
                anthropicCache = AiAnthropicCache.FiveMinutes)
            store.saveProfile(AiModelDraft(null, "Fixture", "https://example.invalid/v1", "fixture", "fixture-key",
                parameters = AiParameters(maxTokens = 100, service = service)))
            val saved = requireNotNull(store.catalog().active)
            assertEquals(service, saved.parameters.service)
            assertEquals(service, store.load().parameters.service)
            assertEquals(service, store.resolve(AiModelDraft(saved.id, saved.name, saved.endpoint, saved.model, null,
                parameters = saved.parameters)).parameters.service)
            store.saveProfile(AiModelDraft(saved.id, saved.name, saved.endpoint, saved.model, null,
                parameters = saved.parameters.copy(temperature = .3), protocol = AiApiProtocol.Anthropic))
            val edited = requireNotNull(store.catalog().active)
            assertEquals(service, edited.parameters.service)
            assertEquals(AiApiProtocol.Anthropic, edited.protocol)
            assertTrue(edited.hasKey)
            val legacyFile = File(directory, "fixture-profiles-v2.json")
            val legacy = JSONObject(legacyFile.readText())
            legacy.getJSONArray("profiles").getJSONObject(0).remove("service")
            legacyFile.writeText(legacy.toString())
            assertEquals(AiServiceOptions(), requireNotNull(store.catalog().active).parameters.service)
        } finally {
            directory.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}

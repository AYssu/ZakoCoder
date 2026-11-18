package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

/** Isolated metadata changes must retain credentials, overrides and old catalogs. */
class AiModelChoicesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test(timeout = 45000) fun savedChoicesSwitchModelsAndReasoningWithoutRewritingCredentials(): Unit = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val old = f.config.load()
            val options = old.context.copy(contextWindowTokens = 240000, compressionThresholdPercent = 85)
            f.config.saveProfile(AiModelDraft("legacy", "local fixture", old.endpoint, old.model, null,
                old.parameters.copy(seed = 17), options, protocol = old.protocol,
                modelChoices = listOf(old.model, "gpt-6.1-sol")))
            val before = f.config.load()
            val reloaded = AiConfigStore(context, f.token, f.alias)
            assertEquals(listOf(old.model, "gpt-6.1-sol"), reloaded.catalog().active!!.modelChoices)
            reloaded.selectModel("legacy", old.model)
            assertEquals(240000, reloaded.load().context.contextWindowTokens)
            reloaded.selectModel("legacy", "gpt-6.1-sol")
            assertEquals(AiModelContextWindows.defaultTokens("gpt-6.1-sol"), reloaded.load().context.contextWindowTokens)
            assertEquals(85, reloaded.load().context.compressionThresholdPercent)
            assertEquals(before.key, reloaded.load().key)
            assertEquals(before.parameters, reloaded.load().parameters)
            reloaded.setReasoning("legacy", AiThinking.Enabled, AiReasoningEffort.High)
            assertEquals(AiReasoningEffort.High, reloaded.load().parameters.reasoningEffort)
            assertEquals(17, reloaded.load().parameters.seed)
            reloaded.setReasoning("legacy", AiThinking.Disabled, null)
            assertEquals(AiThinking.Disabled, reloaded.load().parameters.thinking)
            assertNull(reloaded.load().parameters.reasoningEffort)
            try { reloaded.selectModel("legacy", "unknown-unsaved-model"); fail("unsaved choice") } catch (_: IllegalArgumentException) { }
            assertEquals("gpt-6.1-sol", reloaded.load().model)
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test(timeout = 45000) fun explicitDiscoveryPersistsAcrossReloadAndActiveTaskLocksChanges(): Unit = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var active = false
            lateinit var models: AiModelsController
            withContext(Dispatchers.Main) { models = AiModelsController(context, f.config, f.transport, { active }, {}) }
            withTimeout(15000) { while (models.state.value.loading) delay(30) }
            f.server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}"))
            withContext(Dispatchers.Main) { models.discoverSaved("legacy") }
            withTimeout(15000) { while (models.state.value.probing) delay(30) }
            assertEquals(listOf("model-a", "model-b"), models.state.value.catalog.active!!.modelChoices)
            assertEquals(listOf("model-a", "model-b"), AiConfigStore(context, f.token, f.alias).catalog().active!!.modelChoices)
            assertEquals(1, f.server.requestCount)
            val before = f.config.load()
            withContext(Dispatchers.Main) {
                active = true
                models.selectModel("legacy", "model-a")
                models.setReasoning("legacy", AiThinking.Enabled, AiReasoningEffort.High)
                models.discoverSaved("legacy")
            }
            delay(100)
            assertEquals(before, f.config.load())
            assertEquals(1, f.server.requestCount)
            withContext(Dispatchers.Main) { models.cancelProbe() }
        }
    }
}

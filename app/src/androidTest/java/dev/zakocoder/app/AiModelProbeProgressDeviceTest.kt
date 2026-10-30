package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AiModelProbeProgressDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(15000) { while (!check()) delay(20) }
    private fun draft(f: AiDeviceTest.Fixture) = AiModelDraft("legacy", "probe-check", f.server.url("/v1").toString(), "fixture-code", null)

    @Test(timeout = 60000) fun progressArrivesBeforeCompletionAndCancellationPreservesIt() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            lateinit var models: AiModelsController
            main { models = AiModelsController(context, f.config, f.transport, { false }, {}) }
            await { !models.state.value.loading }
            f.enqueue("Receiving a streamed test reply. ".repeat(20), calls = listOf(AiCall("probe", "connection_probe", "{\"ack\":\"ok\"}")), delayMillis = 35)
            main { models.test(draft(f)) }
            await { models.state.value.probeProgress?.preview?.isNotEmpty() == true }
            assertEquals(AiProbe.Testing, models.state.value.probe)
            val partial = models.state.value.probeProgress!!
            assertEquals(200, partial.status); assertTrue(partial.events > 0); assertNotNull(partial.firstEventMs)
            main { models.stopProbe() }
            assertEquals(AiProbe.Cancelled, models.state.value.probe)
            assertTrue(models.state.value.probeProgress!!.preview.isNotEmpty())
            delay(300)
            assertEquals(AiProbe.Cancelled, models.state.value.probe)
            main { models.cancelProbe() }
            assertNull(models.state.value.probeProgress)
            assertEquals(AiProbe.Idle, models.state.value.probe)
        }
    }

    @Test(timeout = 60000) fun toolArgumentsAreVisibleRedactedAndHttpFailuresNeverExposeBodies() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            lateinit var models: AiModelsController
            main { models = AiModelsController(context, f.config, f.transport, { false }, {}) }
            await { !models.state.value.loading }
            f.enqueue("test-only-placeholder", calls = listOf(AiCall("probe", "connection_probe", "{\"ack\":\"ok\"}")), delayMillis = 1)
            main { models.test(draft(f)) }; await { !models.state.value.probing }
            assertEquals(AiProbe.Passed, models.state.value.probe)
            val progress = models.state.value.probeProgress!!
            assertTrue(progress.preview.contains("connection_probe")); assertTrue(progress.preview.contains("ack"))
            assertTrue(progress.preview.contains("[REDACTED]")); assertFalse(progress.toString().contains("test-only-placeholder"))
            assertTrue(progress.argumentChars > 0)
            f.server.takeRequest(2, TimeUnit.SECONDS)
            f.server.enqueue(MockResponse().setResponseCode(401).setBody("NEVER_SHOW_HTTP_ERROR_BODY"))
            main { models.test(draft(f)) }; await { !models.state.value.probing }
            assertEquals(AiProbe.Failed, models.state.value.probe)
            assertEquals(401, models.state.value.probeProgress!!.status)
            assertFalse(models.state.value.toString().contains("NEVER_SHOW"))
            assertEquals("", models.state.value.probeProgress!!.preview)
            main { models.cancelProbe() }
        }
    }
}

package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiParametersTest {
    @Test fun disabledParametersLeaveProviderDefaultsUntouched() {
        assertEquals(AiParameters(null, null, null), AiParameters())
        AiParameters(1, 0.0, 0.001).validate(); AiParameters(32768, 2.0, 1.0).validate()
    }
    @Test fun invalidAndNonFiniteSamplingValuesAreRejected() {
        listOf(AiParameters(0), AiParameters(32769), AiParameters(temperature = Double.NaN), AiParameters(temperature = Double.POSITIVE_INFINITY),
            AiParameters(temperature = -0.1), AiParameters(topP = 0.0), AiParameters(topP = 1.01)).forEach { parameters ->
            assertThrows(IllegalArgumentException::class.java) { parameters.validate() }
        }
    }
    @Test fun editableInputsNeverPrintReplacementCredential() {
        val draft = AiModelDraft(null, "name", "https://example.invalid/v1", "model", "TEST_ONLY_VALUE")
        assertFalse(draft.toString().contains("TEST_ONLY_VALUE")); assertFalse(AiConfig(key = "TEST_ONLY_VALUE").toString().contains("TEST_ONLY_VALUE"))
        assertEquals("https://example.invalid/v1", AiConfigStore.endpoint(" https://example.invalid/v1/chat/completions/ "))
        assertEquals("https://example.invalid/v1", AiConfigStore.endpoint("https://example.invalid/v1/models"))
    }
    @Test fun reasoningAndContextOptionsValidateWithoutGuessingTokens() {
        AiParameters(thinking = AiThinking.Enabled, reasoningEffort = AiReasoningEffort.Max, frequencyPenalty = -2.0, presencePenalty = 2.0, seed = -3).validate()
        listOf(AiParameters(frequencyPenalty = Double.NaN), AiParameters(presencePenalty = 2.1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        assertEquals(128000, AiContextOptions().contextWindowTokens)
        AiContextOptions(32000, false, 50, 2000).validate(); AiContextOptions(256000, true, 95, 12000).validate()
        listOf(AiContextOptions(contextWindowTokens = 8191), AiContextOptions(compressionThresholdPercent = 96), AiContextOptions(summaryMaxChars = 12001)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
    }
    @Test fun customHeadersRejectTransportOverridesInjectionAndDuplicates() {
        AiRequestHeaders.validate(mapOf("X-Api-Key" to "fixture", "HTTP-Referer" to "https://example.invalid"))
        listOf(mapOf("Authorization" to "x"), mapOf("Host" to "other.invalid"), mapOf("Content-Length" to "2"),
            mapOf("X-A" to "ok\r\nInjected: yes"), mapOf("X A" to "x"), mapOf("X-A" to "a", "x-a" to "b"),
            mapOf("Cookie" to "a=b"), mapOf("X-A" to "界")).forEach {
            assertThrows(IllegalArgumentException::class.java) { AiRequestHeaders.validate(it) }
        }
        val draft = AiModelDraft(null, "x", "https://example.invalid", "x", null, replacementHeaders = mapOf("X-Key" to "HEADER_TEST_VALUE"))
        assertFalse(draft.toString().contains("HEADER_TEST_VALUE"))
        assertFalse(AiConfig(requestHeaders = mapOf("X-Key" to "HEADER_TEST_VALUE")).toString().contains("HEADER_TEST_VALUE"))
    }
}

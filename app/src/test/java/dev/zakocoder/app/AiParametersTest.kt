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
}

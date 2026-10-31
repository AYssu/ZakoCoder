package dev.zakocoder.app

import dev.zakocoder.app.ai.AiContextOptions
import dev.zakocoder.app.ai.AiParameters
import dev.zakocoder.app.ui.ModelInputValidation
import org.junit.Assert.*
import org.junit.Test

class ModelInputValidationTest {
    @Test fun numericHintsAgreeWithPersistedContractAtBoundaries() {
        listOf("", "-2", "0", "2", "2.01", "-2.01", "NaN", "Infinity", "x").forEach { value ->
            val persisted = runCatching { AiParameters(frequencyPenalty = value.takeIf(String::isNotBlank)?.toDouble()).validate() }.isSuccess
            assertEquals(value, persisted, ModelInputValidation.penalty(value))
        }
        listOf("", "-2147483648", "2147483647", "2147483648", "1.2", "NaN").forEach { value ->
            assertEquals(value, runCatching { value.takeIf(String::isNotBlank)?.toInt() }.isSuccess, ModelInputValidation.seed(value))
        }
        listOf("", "8191", "8192", "2097152", "2097153", "NaN").forEach { value ->
            assertEquals(value, runCatching { AiContextOptions(contextWindowTokens = value.toInt()).validate() }.isSuccess,
                ModelInputValidation.window(value))
        }
        listOf("49", "50", "95", "96", "x").forEach { value ->
            assertEquals(value, runCatching { AiContextOptions(compressionThresholdPercent = value.toInt()).validate() }.isSuccess,
                ModelInputValidation.threshold(value))
        }
        listOf("1999", "2000", "12000", "12001", "1.2").forEach { value ->
            assertEquals(value, runCatching { AiContextOptions(summaryMaxChars = value.toInt()).validate() }.isSuccess,
                ModelInputValidation.summary(value))
        }
    }
}

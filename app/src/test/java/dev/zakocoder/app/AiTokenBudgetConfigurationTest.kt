package dev.zakocoder.app

import dev.zakocoder.app.ai.AiConfig
import dev.zakocoder.app.ai.AiContextOptions
import dev.zakocoder.app.ai.AiFailure
import dev.zakocoder.app.ai.AiParameters
import dev.zakocoder.app.ai.AiTransportException
import dev.zakocoder.app.ai.tokenBudget
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiTokenBudgetConfigurationTest {
    @Test fun outputReserveThatLeavesNoInputCapacityFailsBeforeCompression() {
        val config = AiConfig(
            parameters = AiParameters(maxTokens = 8192),
            context = AiContextOptions(contextWindowTokens = 8192)
        )

        val failure = assertThrows(AiTransportException::class.java) {
            tokenBudget(config, JSONArray(), JSONArray())
        }
        assertEquals(AiFailure.ContextConfiguration, failure.kind)
        assertEquals("unusable_context_budget", failure.diagnostic)
    }
}

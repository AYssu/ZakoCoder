package dev.zakocoder.app

import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.ai.withRequestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTurnStatisticsStateTest {
    @Test fun lateIncompleteCallbackCannotReplaceCompleteRequest() {
        val terminal = AiRequestLog(
            id = "same", turn = 1, round = 2, started = 20, elapsed = 100,
            inputTokens = 10, model = "terminal", complete = true
        )
        val state = AiState()
            .withRequestLog(terminal)
            .withRequestLog(AiRequestLog(
                id = "same", turn = 1, round = 2, started = 20, elapsed = 200,
                outputTokens = 5, model = "late", complete = false
            ))
            .withRequestLog(AiRequestLog(
                id = "earlier", turn = 1, round = 1, started = 10, elapsed = 100,
                inputTokens = 1, outputTokens = 1, complete = true
            ))

        assertEquals(listOf("earlier", "same"), state.requests.map { it.id })
        val merged = state.requests.single { it.id == "same" }
        assertTrue(merged.complete)
        assertEquals("terminal", merged.model)
        assertEquals(10L, merged.inputTokens)
        assertEquals(5L, merged.outputTokens)
    }
}

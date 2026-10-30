package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiTurnUsageTest {
    @Test fun sumsRequestsWithinTurnAndIncludesToolRoundInput() {
        val result = turnUsage(listOf(
            AiRequestLog(turn = 1, inputTokens = 100, outputTokens = 50, elapsed = 2500, firstEvent = 500, firstText = 500),
            AiRequestLog(turn = 1, inputTokens = 200, outputTokens = 30, elapsed = 2000, firstEvent = 1000, firstText = 1000),
            AiRequestLog(turn = 2, inputTokens = 999, outputTokens = 999)), 1)
        assertEquals(300L, result.input); assertEquals(80L, result.output); assertEquals(380L, result.total)
        assertEquals(80.0 / 3, result.tokensPerSecond!!, 0.001); assertFalse(result.partial)
    }
    @Test fun missingUsageIsNotEstimatedFromCharacters() {
        val result = turnUsage(listOf(AiRequestLog(turn = 1, textChars = 4000, elapsed = 1000)), 1)
        assertNull(result.input); assertNull(result.output); assertNull(result.total); assertNull(result.tokensPerSecond)
        assertTrue(result.partial)
    }
    @Test fun speedIncludesReasoningAndEvictedRoundsAreMarkedPartial() {
        val result = turnUsage(listOf(AiRequestLog(turn = 1, round = 3, inputTokens = 100,
            outputTokens = 610, elapsed = 62000, firstEvent = 1000, firstText = 61000)), 1)
        assertEquals(10.0, result.tokensPerSecond!!, 0.001)
        assertTrue(result.partial)
        assertTrue(turnUsage(emptyList(), 4).partial)
    }

}

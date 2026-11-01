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

    @Test fun cachePercentUsesWeightedInputAcrossRounds() {
        val usage = turnUsage(listOf(
            AiRequestLog(turn = 1, inputTokens = 1000, cachedTokens = 900),
            AiRequestLog(turn = 1, inputTokens = 9000, cachedTokens = 100),
            AiRequestLog(turn = 2, inputTokens = 50, cachedTokens = 50)), 1)
        assertEquals(1000L, usage.cached)
        assertEquals(10.0, usage.cachePercent!!, 0.0001)
    }
    @Test fun unknownCacheNeverBecomesZeroOrMisleadingPercent() {
        val usage = turnUsage(listOf(AiRequestLog(turn = 1, inputTokens = 1000, cachedTokens = 900),
            AiRequestLog(turn = 1, inputTokens = 1000)), 1)
        assertNull(usage.cached); assertNull(usage.cachePercent)
        assertNull(turnUsage(emptyList(), 1).cachePercent)
    }
    @Test fun explicitZeroCacheIsKnownAndInvalidDenominatorRemainsUnknown() {
        val zero = turnUsage(listOf(AiRequestLog(turn = 1, inputTokens = 100, cachedTokens = 0)), 1)
        assertEquals(0L, zero.cached); assertEquals(0.0, zero.cachePercent!!, 0.001)
        assertNull(turnUsage(listOf(AiRequestLog(turn = 1, inputTokens = 0, cachedTokens = 0)), 1).cachePercent)
        assertNull(turnUsage(listOf(AiRequestLog(turn = 1, inputTokens = 50, cachedTokens = 100)), 1).cachePercent)
        assertNull(turnUsage(listOf(AiRequestLog(turn = 1, cachedTokens = 100)), 1).cachePercent)
    }
    @Test fun overflowingCacheCountRemainsUnknown() {
        val usage = turnUsage(listOf(AiRequestLog(turn = 1, cachedTokens = Long.MAX_VALUE),
            AiRequestLog(turn = 1, cachedTokens = 1)), 1)
        assertNull(usage.cached); assertNull(usage.cachePercent)
    }
}

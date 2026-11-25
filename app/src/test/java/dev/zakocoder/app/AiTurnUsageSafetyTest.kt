package dev.zakocoder.app

import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.turnUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTurnUsageSafetyTest {
    @Test fun negativeCountsAreFilteredAndMarkPartial() {
        val usage = turnUsage(listOf(
            AiRequestLog(turn = 1, inputTokens = -1, outputTokens = 5, cachedTokens = -1)
        ), 1)

        assertNull(usage.input)
        assertEquals(5L, usage.output)
        assertNull(usage.total)
        assertNull(usage.cached)
        assertTrue(usage.partial)
    }

    @Test fun overflowingTokenSumsAreUnknownInsteadOfWrapped() {
        val usage = turnUsage(listOf(
            AiRequestLog(turn = 1, inputTokens = Long.MAX_VALUE, outputTokens = Long.MAX_VALUE, cachedTokens = Long.MAX_VALUE),
            AiRequestLog(turn = 1, inputTokens = 1, outputTokens = 1, cachedTokens = 1)
        ), 1)

        assertNull(usage.input)
        assertNull(usage.output)
        assertNull(usage.cached)
        assertNull(usage.total)
        assertTrue(usage.partial)
    }
}

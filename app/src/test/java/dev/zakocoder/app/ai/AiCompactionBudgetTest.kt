package dev.zakocoder.app.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompactionBudgetTest {
    @Test fun fittingResultAboveSoftThresholdIsAccepted() {
        val budget = AiTokenBudget(
            inputTokens = 90_000,
            windowTokens = 100_000,
            outputReserveTokens = 10_000,
            thresholdPercent = 80
        )

        assertTrue(budget.shouldCompress)
        assertTrue(compactionResultFits(budget))
    }

    @Test fun resultBeyondHardWindowIsRejected() {
        val budget = AiTokenBudget(
            inputTokens = 90_001,
            windowTokens = 100_000,
            outputReserveTokens = 10_000,
            thresholdPercent = 80
        )

        assertFalse(budget.fits)
        assertFalse(compactionResultFits(budget))
    }
}

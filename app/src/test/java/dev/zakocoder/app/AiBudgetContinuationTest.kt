package dev.zakocoder.app

import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.ai.beginTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBudgetContinuationTest {
    @Test fun budgetContinuationPreservesReadOnlyRequestingState() {
        val next = AiState(planningMode = true, phase = AiPhase.Failed).beginTaskState(keepReadOnly = true)

        assertTrue(next.planningMode)
        assertEquals(AiPhase.Requesting, next.phase)
    }

    @Test fun ordinarySendClearsReadOnly() {
        val next = AiState(planningMode = true, phase = AiPhase.Failed).beginTaskState(keepReadOnly = false)

        assertFalse(next.planningMode)
        assertEquals(AiPhase.Requesting, next.phase)
    }
}

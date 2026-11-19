package dev.zakocoder.app.ui

import org.junit.Assert.*
import org.junit.Test

class HomeBackConfirmationTest {
    @Test fun firstBackStaysAndSecondWithinTwoSecondsLeaves() {
        val confirmation = HomeBackConfirmation()
        assertFalse(confirmation.accept(0))
        assertTrue(confirmation.accept(2_000))
        assertFalse(confirmation.accept(2_001))
    }

    @Test fun expiredConfirmationStartsANewPair() {
        val confirmation = HomeBackConfirmation()
        assertFalse(confirmation.accept(100))
        assertFalse(confirmation.accept(2_101))
        assertTrue(confirmation.accept(2_200))
    }

    @Test fun navigationOrBackgroundClearsThePendingConfirmation() {
        val confirmation = HomeBackConfirmation()
        assertFalse(confirmation.accept(100))
        confirmation.reset()
        assertFalse(confirmation.accept(200))
        assertTrue(confirmation.accept(300))
    }
}

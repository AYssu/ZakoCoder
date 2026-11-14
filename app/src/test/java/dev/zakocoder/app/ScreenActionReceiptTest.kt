package dev.zakocoder.app

import dev.zakocoder.app.screentest.ScreenActionReceipt
import dev.zakocoder.app.screentest.ScreenObservationFingerprint
import org.junit.Assert.*
import org.junit.Test

class ScreenActionReceiptTest {
    private val before = ScreenObservationFingerprint("old-token", "digest", false)

    @Test fun snapshotTokenChangesAloneDoNotProveAnyScreenChange() {
        val receipt = ScreenActionReceipt.action(before, before.copy(token = "new-token"))
        assertTrue(receipt.dispatched)
        assertEquals(false, receipt.observedChange)
        assertFalse(receipt.verified)
        assertEquals("unchanged_screen", receipt.unknownReason)
    }

    @Test fun observedChangeNeverClaimsTheUsersGoalWasVerified() {
        val receipt = ScreenActionReceipt.action(before, before.copy(digest = "different-content"))
        assertEquals(true, receipt.observedChange)
        assertFalse(receipt.verified)
        assertEquals("postcondition_not_specified", receipt.unknownReason)
    }

    @Test fun missingAndPartialEvidenceAreExplicitlyUnknown() {
        assertNull(ScreenActionReceipt.action(null, before).observedChange)
        assertEquals("missing_baseline", ScreenActionReceipt.action(null, before).unknownReason)
        assertNull(ScreenActionReceipt.action(before, null).observedChange)
        assertEquals("observation_unavailable", ScreenActionReceipt.action(before, null).unknownReason)
        assertNull(ScreenActionReceipt.action(before, before.copy(truncated = true)).observedChange)
        assertEquals("partial_observation", ScreenActionReceipt.action(before, before.copy(truncated = true)).unknownReason)
    }
}

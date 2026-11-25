package dev.zakocoder.app.projects

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectExportLimitTest {
    @Test fun acceptsExactBudgetAndRejectsTheNextByte() {
        assertTrue(exportWithinLimit(PROJECT_EXPORT_TOTAL_LIMIT_BYTES - 1, 1))
        assertFalse(exportWithinLimit(PROJECT_EXPORT_TOTAL_LIMIT_BYTES, 1))
    }

    @Test fun rejectsInvalidAndOverflowingCounts() {
        assertFalse(exportWithinLimit(-1, 1))
        assertFalse(exportWithinLimit(0, -1))
        assertFalse(exportWithinLimit(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}

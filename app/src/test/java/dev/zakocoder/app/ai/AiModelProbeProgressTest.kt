package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiModelProbeProgressTest {
    @Test fun presentationIsBoundedAndExcludesRequestDetails() {
        val progress = AiModelProbeProgress.from(AiRequestLog(status = 200, elapsed = 1200, events = 9,
            request = "private request", origin = "https://private.invalid", response = "x".repeat(5000)))
        assertEquals(4000, progress.preview.length)
        assertTrue(progress.truncated)
        assertEquals(200, progress.status)
        assertEquals(1200L, progress.elapsedMs)
        assertFalse(progress.toString().contains("private"))
    }
}

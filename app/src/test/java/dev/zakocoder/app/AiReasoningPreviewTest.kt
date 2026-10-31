package dev.zakocoder.app

import dev.zakocoder.app.ui.AiReasoningPreview
import org.junit.Assert.*
import org.junit.Test

class AiReasoningPreviewTest {
    @Test fun liveAndHistoricalPreviewsKeepTheirOriginalBoundedWindows() {
        val source = "begin-" + "x".repeat(20000) + "-latest"
        assertEquals(AiReasoningPreview.MAX_CHARS, AiReasoningPreview.of(source, true).length)
        assertTrue(AiReasoningPreview.of(source, true).endsWith("-latest"))
        assertTrue(AiReasoningPreview.of(source, false).startsWith("begin-"))
        assertEquals(AiReasoningPreview.MAX_CHARS, AiReasoningPreview.of(source, false).length)
        assertEquals("short", AiReasoningPreview.of("short", true))
        assertEquals("", AiReasoningPreview.of("", false))
    }
    @Test fun previewBoundariesNeverSplitSurrogatePairs() {
        val tail = "x😀" + "a".repeat(AiReasoningPreview.MAX_CHARS - 1)
        val preview = AiReasoningPreview.of(tail, true)
        assertFalse(preview.first().isLowSurrogate())
        assertTrue(preview.length <= AiReasoningPreview.MAX_CHARS)
        val head = "a".repeat(AiReasoningPreview.MAX_CHARS - 1) + "😀end"
        val previous = AiReasoningPreview.of(head, false)
        assertFalse(previous.last().isHighSurrogate())
        assertTrue(previous.length <= AiReasoningPreview.MAX_CHARS)
    }
}

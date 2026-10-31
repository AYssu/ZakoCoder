package dev.zakocoder.app

import dev.zakocoder.app.ui.AiCodePreview
import org.junit.Assert.*
import org.junit.Test

class AiCodePreviewTest {
    @Test fun longCodeHasStablePrefixAsStreamingAppendsAndCompleteCopySourceIsUnchanged() {
        val original = (1..72).joinToString("\n") { "val item$it = $it" } + "\n"
        val preview = AiCodePreview.of(original, false)
        assertEquals(12, preview.lines)
        assertEquals(72, preview.totalLines)
        assertTrue(preview.limited)
        assertFalse(preview.text.contains("item13"))
        assertEquals(preview.text, AiCodePreview.of(original + "val last = 73\n", false).text)
        assertEquals(original.removeSuffix("\n"), AiCodePreview.of(original, true).text)
        assertFalse(AiCodePreview.of(original, true).limited)
        assertTrue(original.endsWith("item72 = 72\n"))
    }
    @Test fun bothPreviewModesBoundNewlineHeavyAndSingleLineLayout() {
        val lines = "x\n".repeat(16000)
        assertEquals(12, AiCodePreview.of(lines, false).lines)
        assertEquals(120, AiCodePreview.of(lines, true).lines)
        assertTrue(AiCodePreview.of(lines, true).limited)
        val single = "x".repeat(64000)
        assertEquals(1600, AiCodePreview.of(single, false).text.length)
        assertEquals(4000, AiCodePreview.of(single, true).text.length)
        assertEquals(1, AiCodePreview.of(single, true).totalLines)
    }
    @Test fun truncationNeverSplitsEmojiOrCrLfAndEmptyCodeRemainsEmpty() {
        listOf(false to 1600, true to 4000).forEach { (expanded, limit) ->
            val emoji = "x".repeat(limit - 1) + "😀 rest"
            val preview = AiCodePreview.of(emoji, expanded)
            assertFalse(preview.text.last().isHighSurrogate())
            val crlf = "x".repeat(limit - 1) + "\r\nmore"
            assertFalse(AiCodePreview.of(crlf, expanded).text.endsWith("\r"))
        }
        assertEquals("", AiCodePreview.of("", false).text)
        assertEquals(0, AiCodePreview.of("", false).totalLines)
        assertFalse(AiCodePreview.of("", false).limited)
        assertEquals("first\r\nsecond", AiCodePreview.of("first\r\nsecond\r\n", false).text)
    }
}

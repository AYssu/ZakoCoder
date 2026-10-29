package dev.zakocoder.app

import dev.zakocoder.app.ui.AiTextPages
import org.junit.Assert.*
import org.junit.Test

class AiTextPagesTest {
    @Test fun pagesAreLosslessForLongTextEmojiAndMixedNewlines() {
        val text = "head\n" + "x".repeat(3994) + "😀\r\n" + "尾部\r\n🙂\r\n".repeat(12000) + "TAIL_SENTINEL"
        val ranges = AiTextPages.ranges(text)
        assertEquals(text, ranges.joinToString("") { text.substring(it) })
        assertTrue(ranges.size > 1)
        ranges.forEachIndexed { index, range ->
            val page = text.substring(range)
            assertTrue(page.length <= AiTextPages.PAGE_CHARS)
            assertFalse(page.first().isLowSurrogate())
            assertFalse(page.last().isHighSurrogate())
            assertTrue(page.count { it == '\n' } <= AiTextPages.PAGE_LINES)
            if (index > 0) assertFalse(text[range.first - 1] == '\r' && page.first() == '\n')
        }
        assertTrue(text.substring(ranges.last()).endsWith("TAIL_SENTINEL"))
    }

    @Test fun shortEmptyAndNewlineHeavyResponsesStayBounded() {
        assertEquals(emptyList<IntRange>(), AiTextPages.ranges(""))
        assertEquals(listOf(0..4), AiTextPages.ranges("short"))
        for (text in listOf("\n".repeat(70000), "\r".repeat(70000), "\r\n".repeat(70000), "字".repeat(70000))) {
            val pages = AiTextPages.ranges(text).map { text.substring(it) }
            assertEquals(text, pages.joinToString(""))
            assertTrue(pages.all { it.length <= AiTextPages.PAGE_CHARS && it.lineSequence().count() <= AiTextPages.PAGE_LINES + 1 })
        }
    }
}

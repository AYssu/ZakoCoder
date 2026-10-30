package dev.zakocoder.app.ui

import org.junit.Assert.*
import org.junit.Test

class StreamingTextPacerTest {
    @Test fun firstLiveChunkAnimatesOnceButHistoryAndRemountDoNot() {
        val entries = LiveEntryAnimations(listOf("history"))
        entries.observe(listOf("history", "live"), active = true)
        assertFalse(entries.consume("history"))
        assertTrue(entries.consume("live"))
        assertFalse(entries.consume("live"))
        entries.observe(listOf("history", "live"), active = true)
        assertFalse(entries.consume("live"))
        entries.observe(listOf("history", "live", "loaded-history"), active = false)
        assertFalse(entries.consume("loaded-history"))
        assertFalse(LiveEntryAnimations(listOf("live")).consume("live"))
    }

    @Test fun burstsAdvanceInSmallStepsAndCompleteByDeadline() {
        for (size in listOf(1, 32, 12000, 1000000)) {
            val text = "a".repeat(size)
            var visible = 0
            for (elapsed in 32L..480L step 32) {
                val next = StreamingTextPacer.atTime(text, 0, visible, elapsed)
                assertTrue(next > visible || visible == size)
                assertTrue(next <= size)
                if (size > 32 && elapsed == 32L) assertTrue(next < size / 4)
                visible = next
            }
            assertEquals(size, visible)
        }
    }

    @Test fun unicodeClustersAreNeverPartiallyExposed() {
        for (cluster in listOf("😀", "e\u0301", "👩🏽‍💻", "🇨🇳", "\r\n", "✈️")) {
            val text = "x${cluster}y"
            for (offset in 2 until 1 + cluster.length) assertEquals(text.length - 1, StreamingTextPacer.boundary(text, offset))
        }
    }

    @Test fun adjacentFlagsDoNotBecomeOneUnboundedCluster() {
        assertEquals(4, StreamingTextPacer.boundary("🇨🇳🇺🇸", 2))
        assertEquals(8, StreamingTextPacer.boundary("🇨🇳🇺🇸", 6))
    }

    @Test fun retainedPrefixAndFreshBurstDoNotReplay() {
        val text = "previous answer\n" + "新的内容".repeat(400)
        val initial = "previous answer\n".length
        val next = StreamingTextPacer.atTime(text, initial, initial, 32)
        assertTrue(next > initial)
        assertEquals(text.length, StreamingTextPacer.atTime(text, initial, next, 480))
        assertEquals(text.length, StreamingTextPacer.advance(text, text.length, 32))
    }
}

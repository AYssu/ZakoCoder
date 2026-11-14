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

}

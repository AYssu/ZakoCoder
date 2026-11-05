package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.AiCheckpointChange
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiConversationChangesCacheTest {
    @Test fun visibleTurnCardsShareMetadataAndRemountsReuseBoundedBodies() = runBlocking {
        var metadataReads = 0
        var bodyReads = 0
        val cache = AiConversationChangesCache({ metadataReads++; emptyList() }, { key ->
            bodyReads++; listOf(AiCheckpointChange("$key.kt", "a", "b"))
        })
        coroutineScope { repeat(3) { launch { cache.checkpoints(); cache.changes("turn-$it") } } }
        repeat(3) { cache.checkpoints(); cache.changes("turn-$it") }
        assertEquals(1, metadataReads)
        assertEquals(3, bodyReads)
        cache.changes("turn-3"); cache.changes("turn-4")
        cache.changes("turn-0")
        assertEquals("Least-recently-used body is evicted", 6, bodyReads)
    }

    @Test fun failureCanRetryAndCancelledReadsAreNotCached() = runBlocking {
        var attempts = 0
        var cancelled = false
        val cache = AiConversationChangesCache({ emptyList() }, {
            attempts++
            if (attempts == 1) error("unreadable snapshot")
            if (attempts == 2) { cancelled = true; throw CancellationException("discard") }
            emptyList()
        })
        try { cache.changes("a"); fail() } catch (_: IllegalStateException) { }
        try { cache.changes("a"); fail() } catch (_: CancellationException) { }
        assertTrue(cancelled)
        assertTrue(cache.changes("a").isEmpty())
        assertTrue(cache.changes("a").isEmpty())
        assertEquals(3, attempts)
    }
}

package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiSummaryCorrectionTest {
    @Test fun validSummaryDoesNotRequestCorrection() = runBlocking {
        val initial = AiReply("valid", emptyList())
        assertSame(initial, correctOversizedSummary(initial, 5) { error("Unexpected request") })
    }

    @Test fun oversizedSummaryGetsOneReplacementWithoutTruncatingOriginal() = runBlocking {
        val initial = AiReply("x".repeat(6333), emptyList())
        var requests = 0
        val summary = correctOversizedSummary(initial, 6000) { original ->
            requests++
            assertEquals(initial.text, original)
            AiReply("y".repeat(6000), emptyList())
        }
        assertEquals(1, requests)
        assertEquals(6000, summary.text.length)
        assertEquals(6333, initial.text.length)
    }

    @Test fun invalidInitialSummaryNeverInvokesCorrectionOrTools() = runBlocking {
        for (reply in listOf(AiReply("", emptyList()), AiReply("too long", listOf(AiCall("id", "write_file", "{}"))))) {
            assertCompaction { correctOversizedSummary(reply, 1) { error("Must not request") } }
        }
    }

    @Test fun correctionIsBoundedAndInvalidReplacementFailsHonestly() = runBlocking {
        for (reply in listOf(AiReply("still too long", emptyList()), AiReply("", emptyList()),
            AiReply("ok", listOf(AiCall("id", "write_file", "{}"))))) {
            var requests = 0
            assertCompaction { correctOversizedSummary(AiReply("original", emptyList()), 2) { requests++; reply } }
            assertEquals(1, requests)
        }
        assertCompaction { correctOversizedSummary(AiReply("original", emptyList()), 2) { throw java.io.IOException() } }
    }

    @Test fun cancellationIsNeverConvertedToCompaction() = runBlocking {
        val cancelled = CancellationException("stop")
        try {
            correctOversizedSummary(AiReply("original", emptyList()), 2) { throw cancelled }
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun correctionRetainsSpecificProviderFailure() = runBlocking {
        val billing = AiTransportException.http(402)
        try {
            correctOversizedSummary(AiReply("original", emptyList()), 2) { throw billing }
            fail("Expected provider failure")
        } catch (actual: AiTransportException) { assertSame(billing, actual) }
    }

    private suspend fun assertCompaction(block: suspend () -> Unit) {
        try { block(); fail("Expected compaction failure") }
        catch (error: AiBudgetException) { assertEquals(AiFailure.Compaction, error.kind) }
    }
}

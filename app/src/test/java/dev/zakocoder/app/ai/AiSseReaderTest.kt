package dev.zakocoder.app.ai

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class AiSseReaderTest {
    @Test fun multilineDataUsesEventBoundariesForEveryLegalLineEnding() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val input = "\uFEFF: heartbeat${ending}event: message${ending}data: {${ending}data:   \"answer\": \"中文\"${ending}data: }$ending$ending" +
                "id: ignored${ending}data: [DONE]$ending$ending"
            val reader = AiSseReader(Buffer().writeUtf8(input))
            assertEquals("{\n  \"answer\": \"中文\"\n}", reader.next())
            assertEquals("[DONE]", reader.next())
            assertNull(reader.next())
        }
    }

    @Test fun emptyDataAndUnknownFieldsDoNotMergeAdjacentEvents() {
        val reader = AiSseReader(Buffer().writeUtf8("event: ignored\n\ndata\n\ndata:\n\ndata:  preserved \n\n"))
        assertEquals("", reader.next())
        assertEquals("", reader.next())
        assertEquals(" preserved ", reader.next())
        assertNull(reader.next())
    }

    @Test fun utf8AndCrLfMayBeSplitAcrossNetworkReads() {
        val input = Buffer().writeUtf8("data: 中文🙂\r\ndata: second\r\n\r\ndata: next\n\n")
        val network = object : Source {
            override fun read(sink: Buffer, byteCount: Long) = input.read(sink, minOf(1, byteCount))
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val reader = AiSseReader(network)
        assertEquals("中文🙂\nsecond", reader.next())
        assertEquals("next", reader.next())
        assertNull(reader.next())
    }

    @Test fun eofDoesNotInventAnEventBoundaryOrSuccessfulTerminal() {
        for (input in listOf("data: [DONE]", "data: [DONE]\n", "data: {\n", "data: partial\r\n")) {
            try { AiSseReader(Buffer().writeUtf8(input)).next(); fail("Incomplete frame must fail: $input") }
            catch (_: EOFException) { }
        }
    }

    @Test fun completedFrameDoesNotReadAheadUntilTheResponseCloses() {
        val bytes = Buffer().writeUtf8("data: immediate\n\n")
        var reads = 0
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads++
                if (bytes.exhausted()) throw IOException("Response intentionally remains open")
                return bytes.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        assertEquals("immediate", AiSseReader(source).next())
        assertEquals(1, reads)
    }

    @Test fun eventBudgetIncludesAllDataLines() {
        val input = Buffer()
        repeat(9) { input.writeUtf8("data: " + "x".repeat(1024 * 1024) + "\n") }
        input.writeUtf8("\n")
        assertLimit(input)
    }

    @Test fun anOversizedLineCannotBypassTheEventBudgetAsAComment() {
        assertLimit(Buffer().writeUtf8(":" + "x".repeat(8 * 1024 * 1024) + "\n\n"))
    }

    @Test fun cancellationPropagatesBeforeReadingOrDispatching() {
        val cancellation = kotlinx.coroutines.CancellationException("fixture cancellation")
        val input = Buffer().writeUtf8("data: available\n\n")
        val size = input.size
        try { AiSseReader(input) { throw cancellation }.next(); fail("Expected cancellation") }
        catch (actual: kotlinx.coroutines.CancellationException) { assertSame(cancellation, actual) }
        assertEquals(size, input.size)
    }

    private fun assertLimit(input: Buffer) {
        try { AiSseReader(input).next(); fail("Expected bounded event rejection") }
        catch (failure: AiTransportException) {
            assertEquals(AiFailure.OutputLimit, failure.kind)
            assertEquals("response_event_limit", failure.diagnostic)
        }
    }
}

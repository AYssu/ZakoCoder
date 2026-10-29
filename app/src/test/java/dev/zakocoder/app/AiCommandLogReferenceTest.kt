package dev.zakocoder.app

import dev.zakocoder.app.ai.AiCommandLogReference
import org.junit.Assert.*
import org.junit.Test

class AiCommandLogReferenceTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun body(reference: dev.zakocoder.app.ai.AiReference) = reference.text.substringAfter('\n')
    @Test fun fullPageIncludesProvenanceWithinAttachmentBudget() {
        val log = "x".repeat(32000)
        val ref = AiCommandLogReference.create(id, log, 0, 16000, false, "test")
        assertTrue(ref.text.length <= 6000)
        assertTrue(body(ref).length in 5800..5999)
        assertEquals("[run_id=$id; retained_offset=0; retained_end=${body(ref).length}]", ref.text.substringBefore('\n'))
        assertEquals(log.take(body(ref).length), body(ref))
        assertEquals("command-$id", ref.path)
        assertEquals("error", ref.kind)
        assertTrue(ref.truncated)
    }
    @Test fun offsetAndEndIdentifyExactlyTheExcerptNotWholePage() {
        val log = "old page\n" + (1..7000).joinToString("\n") { "line $it" }
        val ref = AiCommandLogReference.create(id, log, 16000, 32000, false, "test")
        assertTrue(ref.text.length <= 6000)
        assertEquals(log.substring(16000, 16000 + body(ref).length), body(ref))
        assertEquals("[run_id=$id; retained_offset=16000; retained_end=${16000 + body(ref).length}]", ref.text.substringBefore('\n'))
        assertTrue(ref.truncated)
    }
    @Test fun completeShortLogIsNotMarkedTruncatedButRetainedTailIs() {
        assertFalse(AiCommandLogReference.create(id, "full log\n", 0, 9, false, "test").truncated)
        assertTrue(AiCommandLogReference.create(id, "full log\n", 0, 9, true, "test").truncated)
        val tail = AiCommandLogReference.create(id, "full log\n", 5, 9, false, "test")
        assertEquals("log\n", body(tail)); assertTrue(tail.truncated)
    }
    @Test fun tailReferenceKeepsFinalErrorAndAccurateOffsetsInsideBudget() {
        val log = "PAGE_HEAD_MARKER\n" + "x".repeat(20000) + "\nFINAL_ERROR_MARKER\n"
        val ref = AiCommandLogReference.create(id, log, log.length - 16000, log.length, true, "test", preferEnd = true)
        val excerpt = body(ref)
        assertTrue(ref.text.length <= 6000)
        assertTrue(excerpt.endsWith("FINAL_ERROR_MARKER\n"))
        assertFalse(excerpt.contains("PAGE_HEAD_MARKER"))
        val start = log.length - excerpt.length
        assertEquals(log.substring(start), excerpt)
        assertEquals("[run_id=$id; retained_offset=$start; retained_end=${log.length}]", ref.text.substringBefore('\n'))
        assertTrue(ref.truncated)
    }
    @Test fun shortTailIncludesCompleteLogWithoutInventingTruncation() {
        val log = "complete result\n"
        val ref = AiCommandLogReference.create(id, log, 0, log.length, false, "test", preferEnd = true)
        assertEquals(log, body(ref)); assertFalse(ref.truncated)
    }
}

package dev.zakocoder.app.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiTaskSessionTest {
    private fun limited(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (limit: AiTaskLimit) { assertEquals(code, limit.code) }
    }
    @Test fun defaultSessionContinuesPastFormerQuotasAndRecordsNewWork() = runBlocking {
        var time = 0L
        val session = AiTaskSession(initial = AiTaskUsage(requests = 80, tools = 240, tokens = 2_000_000), clock = { time })
        time = 7 * 24 * 60 * 60 * 1000L
        session.request()
        assertEquals("continued", session.modelRequest { "continued" })
        session.recordRequest("long-task", 500_000, 12_000, 0)
        session.tool()
        session.checkExecution()
        session.check()
        assertEquals(AiTaskUsage(requests = 81, tools = 241, tokens = 2_512_000, elapsedMs = time), session.snapshot())
    }
    @Test fun defaultSessionHasNoRequestOrToolAdmissionQuota() {
        val session = AiTaskSession()
        repeat(300) {
            session.request()
            session.recordRequest("request-$it", 1_000_000, 100, 0)
            session.tool()
        }
        assertEquals(300, session.snapshot().requests)
        assertEquals(300, session.snapshot().tools)
        assertEquals(300_030_000L, session.snapshot().tokens)
        session.check()
    }
    @Test fun unlimitedUsageCountersSaturateWithoutOverflowOrStopping() {
        var time = 0L
        val session = AiTaskSession(initial = AiTaskUsage(requests = Int.MAX_VALUE, tools = Int.MAX_VALUE,
            tokens = Long.MAX_VALUE - 1, estimatedRequests = Int.MAX_VALUE, elapsedMs = Long.MAX_VALUE - 1), clock = { time })
        time = 100
        session.request()
        session.tool()
        session.recordRequest("saturated", Long.MAX_VALUE, Long.MAX_VALUE, 0)
        session.check()
        assertEquals(Int.MAX_VALUE, session.snapshot().requests)
        assertEquals(Int.MAX_VALUE, session.snapshot().tools)
        assertEquals(Long.MAX_VALUE, session.snapshot().tokens)
        assertEquals(Long.MAX_VALUE, session.snapshot().elapsedMs)
        assertEquals(Int.MAX_VALUE - 1, session.snapshot().estimatedRequests)
    }
    @Test fun finalAdmittedRequestCanStillExecuteAndCommitItsTools() {
        val session = AiTaskSession(AiTaskLimits(requests = 1, tools = 2))
        session.request(); session.tool(); session.tool()
        assertEquals(2, session.snapshot().tools)
        limited("requests") { session.request() }
        limited("tools") { session.tool() }
    }
    @Test fun sharedAdmissionIsAtomicAcrossParallelChildren() = runBlocking {
        val session = AiTaskSession(AiTaskLimits(requests = 7))
        val accepted = (1..40).map { async(Dispatchers.Default) {
            try { session.request(); 1 } catch (_: AiTaskLimit) { 0 }
        } }.awaitAll().sum()
        assertEquals(7, accepted)
        assertEquals(7, session.snapshot().requests)
    }
    @Test fun actualUsageReplacesEstimatesAndRepeatedObservationsCannotDoubleCount() {
        val session = AiTaskSession(AiTaskLimits(tokens = 100))
        session.request(); session.request()
        session.recordRequest("parent", 20, 10, 80)
        session.recordRequest("parent", 20, 10, 80)
        session.recordRequest("child", null, 5, 65)
        assertEquals(100, session.snapshot().tokens)
        assertEquals(1, session.snapshot().estimatedRequests)
        limited("tokens") { session.request() }
    }
    @Test fun failureGuardStopsAfterThreeMatchingFailuresAndSuccessfulWorkResetsStreak() {
        val session = AiTaskSession()
        repeat(2) { session.toolResult("read:a", true) }; session.check()
        session.toolResult("read:a", false)
        repeat(2) { session.toolResult("read:a", true) }; session.check()
        session.toolResult("read:b", true); session.check()
        repeat(2) { session.toolResult("read:b", true) }
        limited("repeated_tool_failure") { session.request() }
    }
    @Test fun elapsedTimeUsesMonotonicClockAndRestoredUsage() {
        var time = 50L
        val session = AiTaskSession(AiTaskLimits(elapsedMs = 100), AiTaskUsage(elapsedMs = 40), { time })
        time += 59; session.check()
        time++; limited("elapsed") { session.tool() }
        assertEquals(100, session.snapshot().elapsedMs)
    }
    @Test fun requestDeadlineStopsHungModelAndUserCancellationRemainsCancellation() = runBlocking {
        val session = AiTaskSession(AiTaskLimits(elapsedMs = 40))
        try { session.modelRequest { delay(5000) }; fail("deadline") }
        catch (limit: AiTaskLimit) { assertEquals("elapsed", limit.code) }
        for (limits in listOf(AiTaskLimits(), AiTaskLimits(elapsedMs = 5_000))) {
            val child = launch { AiTaskSession(limits).modelRequest { awaitCancellation() } }
            yield(); child.cancelAndJoin(); assertTrue(child.isCancelled)
        }
    }
    @Test fun modelRequestPreservesNullableResultsWithAndWithoutExplicitDeadline() = runBlocking {
        assertNull(AiTaskSession().modelRequest<String?> { null })
        assertNull(AiTaskSession(AiTaskLimits(elapsedMs = 5_000)).modelRequest<String?> { null })
    }
    @Test fun modelTimeoutIsNotMisreportedAsTaskQuota() = runBlocking {
        for (limits in listOf(AiTaskLimits(), AiTaskLimits(elapsedMs = 5_000))) {
            try {
                AiTaskSession(limits).modelRequest { withTimeout(1) { delay(5_000) } }
                fail("Expected model timeout")
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
            }
        }
    }
    @Test fun splittingKeepsEveryCharacterAndNeverCutsEmojiInHalf() {
        val evidence = "goal\n" + "中😀文".repeat(1000) + "\nunfinished"
        for (size in listOf(2, 3, 17, 300)) {
            val chunks = mutableListOf<String>()
            var offset = 0
            while (offset < evidence.length) {
                val end = summaryChunkEnd(evidence, offset, 500) { it.length <= size }
                chunks += evidence.substring(offset, end)
                offset = end
            }
            assertEquals(evidence, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= size && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        }
    }
}

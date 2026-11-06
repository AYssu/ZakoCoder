package dev.zakocoder.app.ai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiTaskSessionTest {
    private fun limited(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (limit: AiTaskLimit) { assertEquals(code, limit.code) }
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
        val child = launch { AiTaskSession().modelRequest { awaitCancellation() } }
        yield(); child.cancelAndJoin(); assertTrue(child.isCancelled)
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

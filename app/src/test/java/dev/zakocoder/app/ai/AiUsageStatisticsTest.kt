package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiUsageStatisticsTest {
    private fun request(id: String, started: Long = 100, project: String = "p", model: String = "model",
        input: Long? = null, output: Long? = null, cache: Long? = null, complete: Boolean = true, failed: Boolean = false) =
        AiUsageRequest(id, project, "conversation", model, started, complete, failed, input, output, cache, 1200, 128000, false)
    @Test fun sameConversationIdNeverMixesProjectOwnedUsage() {
        val state = AiUsageState(loading = false, requests = listOf(request("same", project = "p1", input = 100),
            request("same", project = "p2", input = 900)), tools = listOf(
            AiUsageTool("same", "p1", "conversation", "model", 100, "Succeeded", true),
            AiUsageTool("same", "p2", "conversation", "model", 100, "Succeeded", true)))
        val summary = AiUsageStatistics.summarize(AiUsageStatistics.filter(state, projectId = "p1", conversationId = "conversation"))
        assertEquals(1, summary.requestCount)
        assertEquals(1, summary.toolCount)
        assertEquals(100L, summary.inputTokens)
    }
    @Test fun incompleteLedgerRemainsIncompleteAcrossEveryFilter() {
        val state = AiUsageState(loading = false, incomplete = true, requests = listOf(request("a")))
        assertTrue(AiUsageStatistics.filter(state, projectId = "missing").incomplete)
        assertTrue(AiUsageStatistics.filter(state, model = "missing", fromInclusive = 200).incomplete)
        assertTrue(AiUsageStatistics.filter(state, conversationId = "missing").incomplete)
    }
    @Test fun missingUsageRemainsUnknownAndZeroRemainsReported() {
        val unknown = AiUsageStatistics.summarize(listOf(request("a")), emptyList())
        assertNull(unknown.inputTokens); assertNull(unknown.totalTokens); assertNull(unknown.cacheRatio)
        val summary = AiUsageStatistics.summarize(listOf(request("a"), request("b", input = 0, output = 0, cache = 0)), emptyList())
        assertEquals(0L, summary.totalTokens); assertEquals(1, summary.inputReported); assertEquals(1, summary.cachedReported)
        assertNull(summary.cacheRatio)
    }
    @Test fun cacheRatioUsesOnlyRequestsWithBothProviderValues() {
        val summary = AiUsageStatistics.summarize(listOf(request("a", input = 100, cache = 60),
            request("b", input = 900), request("c", cache = 700)), emptyList())
        assertEquals(1000L, summary.inputTokens); assertEquals(760L, summary.cachedTokens)
        assertEquals(0.6, requireNotNull(summary.cacheRatio), 0.00001)
    }
    @Test fun filtersUseStartTimeAndAllSelectedDimensions() {
        val state = AiUsageState(loading = false, startedAt = 5, truncated = true,
            requests = listOf(request("a", 100), request("b", 200), request("c", 150, project = "other"),
                request("d", 160, model = "other")), tools = listOf(AiUsageTool("tool", "p", "conversation", "model", 199, "Denied", false)))
        val filtered = AiUsageStatistics.filter(state, 100, 200, "p", "model", "conversation")
        assertEquals(listOf("a"), filtered.requests.map { it.id }); assertEquals(1, filtered.tools.size)
        assertEquals(5L, filtered.startedAt); assertTrue(filtered.truncated)
    }
    @Test fun failuresCompactionsAndUnexecutedToolsRemainDistinguishable() {
        val summary = AiUsageStatistics.summarize(listOf(request("a", failed = true), request("b", complete = false),
            request("c", input = 100, output = 20).copy(compaction = true)),
            listOf(AiUsageTool("a", "p", "conversation", "model", 100, "Denied", false),
                AiUsageTool("b", "p", "conversation", "model", 101, "Failed", true)))
        assertEquals(3, summary.requestCount); assertEquals(1, summary.failedRequestCount); assertEquals(1, summary.pendingRequestCount)
        assertEquals(2, summary.toolCount); assertEquals(1, summary.executedToolCount)
        assertEquals(120L, summary.totalTokens); assertEquals(1200L, summary.latestEstimatedContextTokens)
        assertEquals(128000, summary.maxContextWindowTokens)
    }
    @Test fun overflowingTotalsAreUnknownWithoutReplacingValidIndividualMetrics() {
        val summary = AiUsageStatistics.summarize(listOf(request("a", input = Long.MAX_VALUE, output = 1)), emptyList())
        assertEquals(Long.MAX_VALUE, summary.inputTokens)
        assertEquals(1L, summary.outputTokens)
        assertNull(summary.totalTokens)
        val overflow = AiUsageStatistics.summarize(listOf(
            request("a", input = Long.MAX_VALUE, output = 1, cache = Long.MAX_VALUE),
            request("b", input = 1, output = Long.MAX_VALUE, cache = 1)), emptyList())
        assertNull(overflow.inputTokens); assertNull(overflow.outputTokens)
        assertNull(overflow.totalTokens); assertNull(overflow.cachedTokens)
        assertEquals(2, overflow.inputReported); assertEquals(2, overflow.cachedReported)
    }
    @Test fun impossibleCachePairsAreNotCountedAsUsableReports() {
        val invalid = AiUsageStatistics.summarize(listOf(request("a", input = 10, cache = 12),
            request("b", input = 0, cache = 1), request("c", input = 20, cache = -1)), emptyList())
        assertNull(invalid.cachedTokens); assertNull(invalid.cacheRatio)
        assertEquals(0, invalid.cachedReported)
        assertEquals(3, invalid.inputReported)
        val mixed = AiUsageStatistics.summarize(listOf(request("a", input = 10, cache = 12),
            request("b", input = 100, cache = 60), request("c", cache = 700)), emptyList())
        assertEquals(760L, mixed.cachedTokens); assertEquals(2, mixed.cachedReported)
        assertEquals(0.6, requireNotNull(mixed.cacheRatio), 0.00001)
    }
    @Test fun negativeUsageDoesNotSubtractFromReportedTotals() {
        val summary = AiUsageStatistics.summarize(listOf(request("a", input = -1, output = -2, cache = -3),
            request("b", input = 0, output = 0, cache = 0)), emptyList())
        assertEquals(0L, summary.totalTokens); assertEquals(0L, summary.cachedTokens)
        assertEquals(1, summary.inputReported); assertEquals(1, summary.outputReported)
        assertEquals(1, summary.cachedReported); assertNull(summary.cacheRatio)
    }
}

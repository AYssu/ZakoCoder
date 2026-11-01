package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AiUsageChartsTest {
    private val utc = ZoneId.of("UTC")
    private fun request(time: Long, input: Long? = null, output: Long? = null, model: String = "alpha") =
        AiUsageRequest("r-$time", "p", "c", model, time, true, false, input, output, null, 0, 100000, false)

    @Test fun monthBucketsMatchCalendarAndExcludeBoundaries() {
        val day = LocalDate.of(2026, 2, 14)
        val start = day.withDayOfMonth(1).atStartOfDay(utc).toInstant().toEpochMilli()
        val end = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(utc).toInstant().toEpochMilli()
        val state = AiUsageState(loading = false, requests = listOf(request(start - 1), request(start, 10, 2), request(end - 1), request(end)))
        val buckets = AiUsageCharts.buckets(state, day, false, utc)
        assertEquals(28, buckets.size)
        assertEquals(start, buckets.first().started)
        assertEquals(end, buckets.last().until)
        assertEquals(2, buckets.sumOf { it.summary.requestCount })
        assertEquals(12L, buckets.first().summary.totalTokens)
        assertNull(buckets.last().summary.totalTokens)
    }

    @Test fun dailyUsesLocalZoneAndDaylightSavingHoursWithoutDroppingOrDuplicatingRecords() {
        val zone = ZoneId.of("America/New_York")
        for ((day, count) in listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25)) {
            val empty = AiUsageCharts.buckets(AiUsageState(loading = false), day, true, zone)
            assertEquals(count, empty.size)
            val state = AiUsageState(loading = false, requests = empty.map { request(it.started, 1, 0) })
            val buckets = AiUsageCharts.buckets(state, day, true, zone)
            assertEquals(count, buckets.sumOf { it.summary.requestCount })
            assertTrue(buckets.all { it.summary.requestCount == 1 })
            assertTrue(buckets.zipWithNext().all { (a, b) -> a.until == b.started })
        }
    }

    @Test fun chartMissingOverflowAndZeroRemainDistinct() {
        val day = LocalDate.of(2026, 11, 2)
        val start = day.atStartOfDay(utc).toInstant().toEpochMilli()
        val state = AiUsageState(loading = false, requests = listOf(request(start), request(start + 3600000, 0, 0),
            request(start + 7200000, Long.MAX_VALUE, 1)))
        val buckets = AiUsageCharts.buckets(state, day, true, utc)
        assertNull(buckets[0].summary.totalTokens)
        assertEquals(0L, buckets[1].summary.totalTokens)
        assertNull(buckets[2].summary.totalTokens)
        assertEquals(0, buckets[3].summary.requestCount)
        assertNull(buckets[3].summary.totalTokens)
    }

    @Test fun chartsConsumeAlreadyFilteredLedgerAndToolsKeepTheirOwnTime() {
        val day = LocalDate.of(2026, 11, 2)
        val start = day.atStartOfDay(utc).toInstant().toEpochMilli()
        val state = AiUsageState(loading = false, requests = listOf(request(start, model = "alpha"), request(start + 1, model = "beta")),
            tools = listOf(AiUsageTool("t", "p", "c", "alpha", start + 3600000, "Denied", false)))
        val buckets = AiUsageCharts.buckets(AiUsageStatistics.filter(state, model = "alpha"), day, true, utc)
        assertEquals(1, buckets[0].summary.requestCount)
        assertEquals(0, buckets[0].summary.toolCount)
        assertEquals(1, buckets[1].summary.toolCount)
        assertEquals(0, buckets[1].summary.executedToolCount)
    }

    @Test fun modelDistributionUsesRequestsNotTokensAndStableTieOrder() {
        val state = AiUsageState(loading = false, requests = listOf(request(1, model = "b"), request(2, model = "a"), request(3, model = "a"), request(4, model = "c")))
        assertEquals(listOf(AiUsageModelCount("a", 2), AiUsageModelCount("b", 1), AiUsageModelCount("c", 1)), AiUsageCharts.models(state))
        assertTrue(AiUsageCharts.models(AiUsageState()).isEmpty())
    }
}

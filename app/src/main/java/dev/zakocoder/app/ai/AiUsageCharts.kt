package dev.zakocoder.app.ai

import java.time.LocalDate
import java.time.ZoneId

/** Local-calendar buckets. Missing provider usage stays unknown; no interpolation or generated samples. */
data class AiUsageBucket(val started: Long, val until: Long, val summary: AiUsageSummary)
data class AiUsageModelCount(val model: String, val requests: Int)

object AiUsageCharts {
    fun buckets(state: AiUsageState, date: LocalDate, daily: Boolean, zone: ZoneId): List<AiUsageBucket> {
        val first = if (daily) date else date.withDayOfMonth(1)
        val start = first.atStartOfDay(zone)
        val end = (if (daily) first.plusDays(1) else first.plusMonths(1)).atStartOfDay(zone)
        val ranges = mutableListOf<Pair<Long, Long>>()
        var cursor = start
        while (cursor < end) {
            val next = (if (daily) cursor.plusHours(1) else cursor.plusDays(1)).coerceAtMost(end)
            ranges += cursor.toInstant().toEpochMilli() to next.toInstant().toEpochMilli()
            cursor = next
        }
        // Partition once instead of rescanning the entire retained ledger for every bar.
        val requests = List(ranges.size) { mutableListOf<AiUsageRequest>() }
        val tools = List(ranges.size) { mutableListOf<AiUsageTool>() }
        fun index(time: Long): Int {
            if (ranges.isEmpty() || time < ranges.first().first || time >= ranges.last().second) return -1
            var low = 0
            var high = ranges.lastIndex
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (ranges[middle].first <= time) low = middle else high = middle - 1
            }
            return low
        }
        state.requests.forEach { request -> index(request.started).takeIf { it >= 0 }?.let { requests[it] += request } }
        state.tools.forEach { tool -> index(tool.started).takeIf { it >= 0 }?.let { tools[it] += tool } }
        return ranges.mapIndexed { index, (from, until) ->
            AiUsageBucket(from, until, AiUsageStatistics.summarize(requests[index], tools[index]))
        }
    }

    fun models(state: AiUsageState): List<AiUsageModelCount> = state.requests.groupingBy { it.model }.eachCount()
        .map { (model, count) -> AiUsageModelCount(model, count) }
        .sortedWith(compareByDescending<AiUsageModelCount> { it.requests }.thenBy { it.model })
}

package dev.zakocoder.app.ai

/** Numerical metadata only. No prompt, output, headers, credentials or tool arguments. */
data class AiUsageRequest(val id: String, val projectId: String, val conversationId: String, val model: String,
    val started: Long, val complete: Boolean, val failed: Boolean, val inputTokens: Long?, val outputTokens: Long?,
    val cachedTokens: Long?, val estimatedContextTokens: Long, val contextWindowTokens: Int, val compaction: Boolean)
data class AiUsageTool(val id: String, val projectId: String, val conversationId: String, val model: String,
    val started: Long, val status: String, val executed: Boolean)
data class AiUsageState(val loading: Boolean = true, val failed: Boolean = false, val startedAt: Long = 0,
    val retainedFrom: Long? = null, val requests: List<AiUsageRequest> = emptyList(), val tools: List<AiUsageTool> = emptyList(),
    val truncated: Boolean = false, val incomplete: Boolean = false)
data class AiUsageSummary(val requestCount: Int, val failedRequestCount: Int, val pendingRequestCount: Int,
    val toolCount: Int, val executedToolCount: Int, val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?,
    val cachedTokens: Long?, val cacheRatio: Double?, val inputReported: Int, val outputReported: Int,
    val cachedReported: Int, val latestEstimatedContextTokens: Long?, val maxContextWindowTokens: Int?)

object AiUsageStatistics {
    fun filter(state: AiUsageState, fromInclusive: Long = 0, untilExclusive: Long = Long.MAX_VALUE,
        projectId: String? = null, model: String? = null, conversationId: String? = null): AiUsageState {
        fun matches(time: Long, project: String, selectedModel: String, conversation: String) =
            time >= fromInclusive && time < untilExclusive && (projectId == null || projectId == project) &&
                (model == null || model == selectedModel) && (conversationId == null || conversationId == conversation)
        return state.copy(requests = state.requests.filter { matches(it.started, it.projectId, it.model, it.conversationId) },
            tools = state.tools.filter { matches(it.started, it.projectId, it.model, it.conversationId) })
    }
    fun summarize(requests: List<AiUsageRequest>, tools: List<AiUsageTool>): AiUsageSummary {
        // An overflow is unknown, never a fabricated saturated provider total.
        fun sum(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            var total = 0L
            for (value in values) {
                if (value > Long.MAX_VALUE - total) return null
                total += value
            }
            return total
        }
        val inputs = requests.mapNotNull { it.inputTokens?.takeIf { value -> value >= 0 } }
        val outputs = requests.mapNotNull { it.outputTokens?.takeIf { value -> value >= 0 } }
        // Coverage counts usable reports: a cache hit cannot exceed its paired input.
        // A cache-only report is still known, but cannot contribute a percentage.
        val cacheReports = requests.filter { request -> request.cachedTokens?.let { cache ->
            cache >= 0 && (request.inputTokens == null || request.inputTokens >= cache)
        } == true }
        val cached = cacheReports.map { requireNotNull(it.cachedTokens) }
        val comparable = cacheReports.filter { it.inputTokens != null }
        val denominator = comparable.sumOf { requireNotNull(it.inputTokens).toDouble() }
        return AiUsageSummary(requests.size, requests.count { it.failed }, requests.count { !it.complete }, tools.size,
            tools.count { it.executed }, sum(inputs), sum(outputs), sum(inputs + outputs), sum(cached),
            if (denominator > 0) (comparable.sumOf { requireNotNull(it.cachedTokens).toDouble() } / denominator).coerceIn(0.0, 1.0) else null,
            inputs.size, outputs.size, cached.size, requests.maxByOrNull { it.started }?.estimatedContextTokens?.takeIf { it > 0 },
            requests.maxOfOrNull { it.contextWindowTokens })
    }
    fun summarize(state: AiUsageState) = summarize(state.requests, state.tools)
}

package dev.zakocoder.app.ai

/** Usage belongs to a conversation turn, independently of its bounded diagnostic payloads. */
internal fun AiState.statisticsForTurn(turn: Int): AiTurnStatistics? =
    turnStatistics.firstOrNull { it.turn == turn } ?: requests.filter { it.turn == turn }
        .takeIf { it.isNotEmpty() }?.let { AiTurnStatistics.fromRequests(turn, it) }

internal fun AiState.retainedTurnStatistics(seal: Boolean = false): List<AiTurnStatistics> {
    val retained = turnStatistics.associateBy { it.turn }.toMutableMap()
    requests.groupBy { it.turn }.forEach { (turn, records) ->
        if (turn !in retained) retained[turn] = AiTurnStatistics.fromRequests(turn, records)
    }
    return retained.values.sortedBy { it.turn }.takeLast(512).map { if (seal) it.sealed() else it }
}

/** One pure reducer: a StateFlow CAS retry cannot increment usage twice or miss evicted requests. */
internal fun AiState.withRequestLog(record: AiRequestLog): AiState {
    val statistics = retainedTurnStatistics()
    val previous = statistics.firstOrNull { it.turn == record.turn } ?: AiTurnStatistics(record.turn)
    val existing = requests.firstOrNull { it.id == record.id }
    // Terminal observations are monotonic: late callbacks may only fill missing usage.
    val merged = if (existing?.complete == true) existing.copy(
        inputTokens = existing.inputTokens ?: record.inputTokens,
        outputTokens = existing.outputTokens ?: record.outputTokens,
        cachedTokens = existing.cachedTokens ?: record.cachedTokens
    ) else record
    return copy(
        turnStatistics = (statistics.filterNot { it.turn == record.turn } + previous.observe(record))
            .sortedBy { it.turn }.takeLast(512),
        requests = boundedRequests((requests.filterNot { it.id == record.id } + merged)
            .sortedWith(compareBy<AiRequestLog> { it.started }.thenBy { it.round }))
    )
}

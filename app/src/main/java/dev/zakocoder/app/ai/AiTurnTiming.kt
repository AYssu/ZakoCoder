package dev.zakocoder.app.ai

/** Wall-clock task bounds include tools and approvals; request throughput excludes them. */
data class AiTurnTiming(val turn: Int, val started: Long, val finished: Long? = null)

data class AiTurnUsage(val input: Long?, val output: Long?, val partial: Boolean, val outputSeconds: Double) {
    val total: Long? get() = if (input != null && output != null) input + output else null
    val tokensPerSecond: Double? get() = output?.takeIf { outputSeconds > 0 }?.div(outputSeconds)
}

fun turnUsage(requests: List<AiRequestLog>, turn: Int): AiTurnUsage {
    val records = requests.filter { it.turn == turn }
    fun sumKnown(select: (AiRequestLog) -> Long?): Long? = records.mapNotNull(select).takeIf { it.isNotEmpty() }?.sum()
    val seconds = records.filter { it.outputTokens != null }.sumOf {
        (it.elapsed - (it.firstEvent ?: 0)).coerceAtLeast(1)
    } / 1000.0
    return AiTurnUsage(sumKnown { it.inputTokens }, sumKnown { it.outputTokens },
        records.isEmpty() || records.any { it.inputTokens == null || it.outputTokens == null } ||
            records.map { it.round }.filter { it > 0 }.distinct().sorted().let { rounds ->
                rounds.isNotEmpty() && (rounds.first() != 1 || rounds.zipWithNext().any { (a, b) -> b != a + 1 })
            }, seconds)
}

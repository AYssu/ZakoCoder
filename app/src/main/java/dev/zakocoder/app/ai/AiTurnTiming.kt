package dev.zakocoder.app.ai

/** Wall-clock task bounds include tools and approvals; request throughput excludes them. */
data class AiTurnTiming(val turn: Int, val started: Long, val finished: Long? = null)

data class AiTurnUsage(val input: Long?, val output: Long?, val partial: Boolean, val outputSeconds: Double, val cached: Long? = null, val cachePercent: Double? = null) {
    val total: Long? get() = if (input != null && output != null && input >= 0 && output >= 0 && output <= Long.MAX_VALUE - input) input + output else null
    val tokensPerSecond: Double? get() = output?.takeIf { outputSeconds > 0 }?.div(outputSeconds)
}

fun turnUsage(requests: List<AiRequestLog>, turn: Int): AiTurnUsage {
    val records = requests.filter { it.turn == turn }
    fun sumKnown(select: (AiRequestLog) -> Long?): Long? {
        val values = records.mapNotNull(select).filter { it >= 0 }
        if (values.isEmpty()) return null
        return values.fold(0L as Long?) { sum, value ->
            sum?.takeIf { value <= Long.MAX_VALUE - it }?.plus(value)
        }
    }
    val invalidUsage = records.any { record ->
        listOf(record.inputTokens, record.outputTokens, record.cachedTokens).any { it != null && it < 0 }
    }
    val input = sumKnown { it.inputTokens }
    val output = sumKnown { it.outputTokens }
    val inputOverflow = input == null && records.any { (it.inputTokens ?: -1) >= 0 }
    val outputOverflow = output == null && records.any { (it.outputTokens ?: -1) >= 0 }
    val seconds = records.filter { it.outputTokens != null }.sumOf {
        (it.elapsed - (it.firstEvent ?: 0)).coerceAtLeast(1)
    } / 1000.0
    // Missing cache statistics are unknown, never zero. A percentage needs the complete
    // denominator for the same requests, including failed attempts that reported usage.
    val cached = records.takeIf { it.isNotEmpty() && it.all { r -> r.cachedTokens != null && r.cachedTokens >= 0 } }
        ?.map { requireNotNull(it.cachedTokens) }?.let { counts ->
            counts.fold(0L as Long?) { sum, count -> sum?.takeIf { count <= Long.MAX_VALUE - it }?.plus(count) }
        }
    val cacheInput = records.takeIf { it.isNotEmpty() && it.all { r ->
        r.inputTokens != null && r.inputTokens >= 0 && r.cachedTokens != null && r.cachedTokens in 0..r.inputTokens
    } }?.map { requireNotNull(it.inputTokens) }?.let { counts ->
        counts.fold(0L as Long?) { sum, count -> sum?.takeIf { count <= Long.MAX_VALUE - it }?.plus(count) }
    }
    val cacheOverflow = cached == null && records.isNotEmpty() &&
        records.all { it.cachedTokens != null && it.cachedTokens >= 0 }
    val cacheInputOverflow = cacheInput == null && records.isNotEmpty() &&
        records.all { it.inputTokens != null && it.inputTokens >= 0 && it.cachedTokens != null && it.cachedTokens in 0..it.inputTokens }
    val percent = if (cached != null && cacheInput != null && cacheInput > 0) cached.toDouble() / cacheInput * 100 else null
    return AiTurnUsage(input, output,
        invalidUsage || inputOverflow || outputOverflow || cacheOverflow || cacheInputOverflow ||
            records.isEmpty() || records.any { it.inputTokens == null || it.outputTokens == null } ||
            records.map { it.round }.filter { it > 0 }.distinct().sorted().let { rounds ->
                rounds.isNotEmpty() && (rounds.first() != 1 || rounds.zipWithNext().any { (a, b) -> b != a + 1 })
            }, seconds, cached, percent)
}

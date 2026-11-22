package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Durable numerical task usage, independent of the bounded request/response diagnostics. */
data class AiTurnStatistics private constructor(
    val turn: Int,
    val partial: Boolean,
    private val base: Totals,
    private val contributions: List<Contribution>,
    private val context: Context?
) {
    constructor(turn: Int, partial: Boolean = false) : this(turn, partial, Totals(), emptyList(), null)
    init {
        require(turn >= 0)
        require(contributions.map { it.id }.distinct().size == contributions.size)
        require(context == null || base.count > 0 || contributions.isNotEmpty())
    }

    val hasRequests: Boolean get() = base.count > 0 || contributions.isNotEmpty()
    val lastContextTokens: Long? get() = context?.tokens
    val contextWindowTokens: Int? get() = context?.capacity
    val compacted: Boolean get() = base.compacted || contributions.any { it.compaction }

    /** Pure upsert: StateFlow may evaluate this reducer more than once. */
    fun observe(record: AiRequestLog): AiTurnStatistics {
        require(record.turn == turn)
        val existing = contributions.firstOrNull { it.id == record.id }
        // Cancellation and socket callbacks can both publish the same terminal observation.
        if (existing?.complete == true) return this
        val contribution = Contribution.from(record)
        val candidate = Context.from(record)
        val nextContext = if (candidate != null && (context == null || candidate.newerThan(context) || candidate.id == context.id)) candidate else context
        return copy(contributions = contributions.filterNot { it.id == record.id } + contribution, context = nextContext)
    }

    fun usage(): AiTurnUsage {
        val totals = contributions.fold(base) { total, request -> total.add(request) }
        val input = totals.input.takeIf { totals.inputCount > 0 }
        val output = totals.output.takeIf { totals.outputCount > 0 }
        val cache = totals.cache.takeIf { totals.count > 0 && totals.cacheCount == totals.count }
        val denominator = totals.cacheInput.takeIf { totals.cacheInputCount == totals.count }
        val ratio = if (cache != null && denominator != null && denominator > 0) cache.toDouble() / denominator * 100 else null
        return AiTurnUsage(input, output,
            partial || totals.count == 0L || totals.inputCount < totals.count || totals.outputCount < totals.count ||
                (totals.inputCount > 0 && input == null) || (totals.outputCount > 0 && output == null) || totals.outputMillis == null,
            totals.outputMillis?.div(1000.0) ?: 0.0, cache, ratio)
    }

    /** Once callbacks for these IDs have ended, retain fixed-size totals and release per-ID metadata.
     * A later continuation may observe NEW request IDs; replay of sealed IDs is not supported. */
    fun sealed(): AiTurnStatistics = if (contributions.isEmpty()) this else
        copy(base = contributions.fold(base) { total, request -> total.add(request) }, contributions = emptyList())

    fun toJson(): JSONObject {
        // Validate the combined counters before history writes, not only after restoration.
        contributions.fold(base) { total, request -> total.add(request) }
        return JSONObject().put("turn", turn).put("partial", partial)
            .put("base", base.json()).put("requests", JSONArray(contributions.map { it.json() }))
            .put("context", context?.json() ?: JSONObject.NULL)
    }

    private data class Contribution(val id: String, val complete: Boolean, val input: Long?, val output: Long?,
        val cache: Long?, val outputMillis: Long, val compaction: Boolean) {
        init {
            require(validId(id) && outputMillis >= 0)
            require(listOf(input, output, cache).all { it == null || it >= 0 })
        }
        fun json() = JSONObject().put("id", id).put("complete", complete).put("input", input ?: JSONObject.NULL)
            .put("output", output ?: JSONObject.NULL).put("cache", cache ?: JSONObject.NULL)
            .put("outputMillis", outputMillis).put("compaction", compaction)
        companion object {
            fun from(record: AiRequestLog): Contribution {
                require(record.elapsed >= 0 && (record.firstEvent == null || record.firstEvent >= 0))
                return Contribution(record.id, record.complete, record.inputTokens, record.outputTokens,
                    record.cachedTokens, if (record.outputTokens == null) 0 else
                        (record.elapsed - (record.firstEvent ?: 0)).coerceAtLeast(1), record.compaction)
            }
            fun read(json: JSONObject) = Contribution(string(json, "id"), boolean(json, "complete"),
                nullableLong(json, "input"), nullableLong(json, "output"), nullableLong(json, "cache"),
                number(json, "outputMillis"), boolean(json, "compaction"))
        }
    }

    private data class Totals(val count: Long = 0, val inputCount: Long = 0, val input: Long? = 0,
        val outputCount: Long = 0, val output: Long? = 0, val cacheCount: Long = 0, val cache: Long? = 0,
        val cacheInputCount: Long = 0, val cacheInput: Long? = 0, val outputMillis: Long? = 0,
        val compacted: Boolean = false) {
        init {
            require(count >= 0 && listOf(inputCount, outputCount, cacheCount, cacheInputCount).all { it in 0..count })
            require(listOf(input, output, cache, cacheInput, outputMillis).all { it == null || it >= 0 })
            require(inputCount > 0 || input == 0L)
            require(outputCount > 0 || (output == 0L && outputMillis == 0L))
            require(cacheCount > 0 || cache == 0L)
            require(cacheInputCount > 0 || cacheInput == 0L)
            require(cacheInputCount <= minOf(inputCount, cacheCount))
            require(!compacted || count > 0)
        }
        fun add(request: Contribution): Totals {
            val validCacheInput = request.cache != null && request.input != null && request.cache <= request.input
            require(count < Long.MAX_VALUE)
            return Totals(count + 1, inputCount + if (request.input != null) 1 else 0,
                addKnown(input, request.input ?: 0), outputCount + if (request.output != null) 1 else 0,
                addKnown(output, request.output ?: 0), cacheCount + if (request.cache != null) 1 else 0,
                addKnown(cache, request.cache ?: 0), cacheInputCount + if (validCacheInput) 1 else 0,
                addKnown(cacheInput, if (validCacheInput) requireNotNull(request.input) else 0),
                addKnown(outputMillis, request.outputMillis), compacted || request.compaction)
        }
        fun json() = JSONObject().put("count", count).put("inputCount", inputCount).put("input", input ?: JSONObject.NULL)
            .put("outputCount", outputCount).put("output", output ?: JSONObject.NULL).put("cacheCount", cacheCount)
            .put("cache", cache ?: JSONObject.NULL).put("cacheInputCount", cacheInputCount)
            .put("cacheInput", cacheInput ?: JSONObject.NULL).put("outputMillis", outputMillis ?: JSONObject.NULL)
            .put("compacted", compacted)
        companion object {
            fun read(json: JSONObject) = Totals(number(json, "count"), number(json, "inputCount"), nullableLong(json, "input"),
                number(json, "outputCount"), nullableLong(json, "output"), number(json, "cacheCount"), nullableLong(json, "cache"),
                number(json, "cacheInputCount"), nullableLong(json, "cacheInput"), nullableLong(json, "outputMillis"), boolean(json, "compacted"))
        }
    }

    private data class Context(val started: Long, val round: Int, val id: String, val tokens: Long?, val capacity: Int?) {
        init {
            require(started >= 0 && round >= 0 && validId(id))
            require((tokens == null && capacity == null) || (tokens != null && tokens > 0 && capacity != null && capacity > 0))
        }
        fun newerThan(other: Context) = compareValuesBy(this, other, { it.started }, { it.round }, { it.id }) > 0
        fun json() = JSONObject().put("started", started).put("round", round).put("id", id)
            .put("tokens", tokens ?: JSONObject.NULL).put("capacity", capacity ?: JSONObject.NULL)
        companion object {
            fun from(record: AiRequestLog): Context? {
                if (record.compaction || record.agent.isNotEmpty()) return null
                val known = record.estimatedContextTokens > 0 && record.contextWindowTokens > 0
                return Context(record.started, record.round, record.id, record.estimatedContextTokens.takeIf { known }, record.contextWindowTokens.takeIf { known })
            }
            fun read(json: JSONObject): Context {
                val round = number(json, "round"); require(round <= Int.MAX_VALUE)
                val capacity = nullableLong(json, "capacity"); require(capacity == null || capacity <= Int.MAX_VALUE)
                return Context(number(json, "started"), round.toInt(), string(json, "id"), nullableLong(json, "tokens"), capacity?.toInt())
            }
        }
    }

    companion object {
        fun fromRequests(turn: Int, records: List<AiRequestLog>, partial: Boolean = false): AiTurnStatistics {
            val matching = records.filter { it.turn == turn }
            return matching.fold(AiTurnStatistics(turn, partial || turnUsage(matching, turn).partial)) { stats, record -> stats.observe(record) }
        }
        fun fromJson(json: JSONObject): AiTurnStatistics {
            val turn = number(json, "turn"); require(turn <= Int.MAX_VALUE.toLong())
            val requests = json.getJSONArray("requests")
            val contributions = (0 until requests.length()).map { Contribution.read(requests.getJSONObject(it)) }
            require(contributions.map { it.id }.distinct().size == contributions.size)
            require(json.has("context"))
            return AiTurnStatistics(turn.toInt(), boolean(json, "partial"), Totals.read(json.getJSONObject("base")), contributions,
                if (json.isNull("context")) null else Context.read(json.getJSONObject("context"))).also { statistics ->
                    statistics.contributions.fold(statistics.base) { total, request -> total.add(request) }
                }
        }
        private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.none { it.isWhitespace() || it.isISOControl() }
        private fun addKnown(sum: Long?, value: Long): Long? = sum?.takeIf { value <= Long.MAX_VALUE - it }?.plus(value)
        private fun number(json: JSONObject, name: String): Long {
            val raw = json.get(name)
            require(raw is Number)
            return requireNotNull(raw.toString().toLongOrNull()).also { require(it >= 0) }
        }
        private fun nullableLong(json: JSONObject, name: String): Long? {
            require(json.has(name))
            return if (json.isNull(name)) null else number(json, name)
        }
        private fun boolean(json: JSONObject, name: String): Boolean = requireNotNull(json.get(name) as? Boolean)
        private fun string(json: JSONObject, name: String): String = requireNotNull(json.get(name) as? String)
    }
}

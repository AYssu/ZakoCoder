package dev.zakocoder.app.memory

import java.util.Locale

/** User-confirmed, installation-wide preferences. These are data, never tool permissions. */
internal data class AgentMemory(
    val id: String,
    val title: String,
    val content: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean = true,
    val core: Boolean = false,
    val pending: Boolean = false,
)

internal data class MemoryContextSnapshot(val core: List<AgentMemory>, val relevant: List<AgentMemory>) {
    val entries: List<AgentMemory> get() = core + relevant

    /** Keep this in a separate data message, after the stable system instructions. */
    fun asContext(): String = entries.joinToString("\n\n") { "[${it.id}] ${it.title}\n${it.content}" }
}

internal object MemoryPolicy {
    const val SCHEMA = 1
    const val MAX_ENTRIES = 128
    const val MAX_TITLE = 80
    const val MAX_CONTENT = 2000
    const val MAX_SOURCE = 240
    const val MAX_FILE_BYTES = 512 * 1024
    const val MAX_CONTEXT_CHARS = 6000
    const val MAX_CORE_CHARS = 2400
    const val MAX_RELEVANT = 6
    const val MAX_QUERY_CHARS = 4000
    const val MAX_SEARCH_TERMS = 64
    private val tokenPattern = Regex("[\\p{L}\\p{N}_+.#-]+")

    fun validate(entry: AgentMemory) {
        require(entry.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(entry.title.isNotBlank() && entry.title.length <= MAX_TITLE)
        require(entry.content.isNotBlank() && entry.content.length <= MAX_CONTENT)
        require(entry.source.isNotBlank() && entry.source.length <= MAX_SOURCE)
        require(entry.createdAt >= 0 && entry.updatedAt >= entry.createdAt)
        require(!entry.pending || !entry.enabled)
        require(listOf(entry.title, entry.content, entry.source).none { '\u0000' in it })
    }

    fun search(entries: List<AgentMemory>, query: String): List<AgentMemory> {
        val text = query.trim().take(MAX_QUERY_CHARS).lowercase(Locale.ROOT)
        if (text.isEmpty()) return entries.sortedByDescending { it.updatedAt }
        val terms = queryTerms(text)
        return entries.map { it to score(it, text, terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AgentMemory, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .map { it.first }
    }

    fun context(entries: List<AgentMemory>, query: String): MemoryContextSnapshot {
        val available = entries.filter { it.enabled && !it.pending }
        var used = 0
        val core = available.filter { it.core }.sortedBy { it.id }.filter { entry ->
            val cost = cost(entry)
            if (used + cost <= MAX_CORE_CHARS) { used += cost; true } else false
        }
        val relevant = search(available.filterNot { it in core }, query).asSequence()
            .filter { query.isNotBlank() }
            .filter { entry ->
                val cost = cost(entry)
                if (used + cost <= MAX_CONTEXT_CHARS) { used += cost; true } else false
            }.take(MAX_RELEVANT).toList()
        return MemoryContextSnapshot(core, relevant)
    }

    private fun cost(entry: AgentMemory): Int = entry.id.length + entry.title.length + entry.content.length + 8

    /** Bound scoring even for a long unspaced request; retain both the opening and final intent. */
    internal fun queryTerms(query: String): Set<String> {
        val candidates = linkedSetOf<String>()
        tokenPattern.findAll(query.take(MAX_QUERY_CHARS)).forEach { match ->
            val word = match.value
            // Very long whole words cannot match a normal title and are costly to compare.
            if (word.length <= 80) candidates.add(word)
            // Chinese requests often have no spaces; use adjacent ideographs for local recall.
            if (word.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                word.windowed(2).filter { pair -> pair.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
                    .forEach(candidates::add)
            }
        }
        if (candidates.size <= MAX_SEARCH_TERMS) return candidates
        val half = MAX_SEARCH_TERMS / 2
        return (candidates.take(half) + candidates.toList().takeLast(MAX_SEARCH_TERMS - half)).toSet()
    }

    private fun score(entry: AgentMemory, query: String, terms: Set<String>): Int {
        val title = entry.title.lowercase(Locale.ROOT)
        val content = entry.content.lowercase(Locale.ROOT)
        return (if (query in title || query in content) 8 else 0) + terms.sumOf { term ->
            (if (term in title) 3 else 0) + (if (term in content) 1 else 0)
        }
    }
}

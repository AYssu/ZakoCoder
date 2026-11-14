package dev.zakocoder.app.ai

/** Search uses the displayed local transcript, never provider settings, request bodies or model context. */
enum class AiConversationSearchKind { User, Assistant, Reasoning, Tool, Error, Title }

data class AiConversationSearchHit(
    val projectId: String,
    val conversationId: String,
    val entryId: String,
    val title: String,
    val updated: Long,
    val kind: AiConversationSearchKind,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
)

data class AiConversationSearchResult(
    val hits: List<AiConversationSearchHit> = emptyList(),
    val scannedConversations: Int = 0,
    val unreadableConversations: Int = 0,
    /** True when a safety/read budget or the result limit prevents a complete presentation. */
    val partial: Boolean = false,
)

internal object AiConversationSearch {
    const val MAX_QUERY = 200
    const val DEFAULT_LIMIT = 100
    const val MAX_SCAN_BYTES = 256L * 1024 * 1024
    private const val SNIPPET = 180

    fun query(value: String): String = value.trim().replace(Regex("\\s+"), " ").also {
        require(it.length <= MAX_QUERY)
    }

    fun hit(projectId: String, conversationId: String, entryId: String, title: String, updated: Long,
            kind: AiConversationSearchKind, text: String, query: String): AiConversationSearchHit? {
        if (query.isEmpty()) return null
        val displayed = text.replace(Regex("\\s+"), " ")
        val found = displayed.indexOf(query, ignoreCase = true)
        if (found < 0) return null
        // Keep the matching phrase intact, including a long query, and do not split emoji pairs.
        var start = (found - 54).coerceAtLeast(0)
        if (start > 0 && displayed[start].isLowSurrogate()) start--
        var end = maxOf(start + SNIPPET, found + query.length + 24).coerceAtMost(displayed.length)
        if (end < displayed.length && displayed[end].isLowSurrogate()) end++
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < displayed.length) "…" else ""
        val matchStart = prefix.length + found - start
        return AiConversationSearchHit(projectId, conversationId, entryId, title, updated, kind,
            prefix + displayed.substring(start, end) + suffix, matchStart, matchStart + query.length)
    }
}

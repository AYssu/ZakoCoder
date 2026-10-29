package dev.zakocoder.app.ai

/** A bounded excerpt of retained command output; provenance shares the existing attachment budget. */
internal object AiCommandLogReference {
    fun create(runId: String, text: String, offset: Int, pageEnd: Int, truncated: Boolean, displayName: String, preferEnd: Boolean = false): AiReference {
        val pageStart = offset.coerceIn(0, text.length)
        val end = pageEnd.coerceIn(pageStart, text.length)
        fun header(from: Int, until: Int) = "[run_id=$runId; retained_offset=$from; retained_end=$until]\n"
        // Reserve the larger offsets, including for a tail excerpt that starts inside the page.
        val available = (6000 - header(if (preferEnd) end else pageStart, end).length).coerceAtLeast(0)
        val start = if (preferEnd) maxOf(pageStart, end - available) else pageStart
        val until = minOf(end, start + available)
        val excerpt = text.substring(start, until)
        return AiReference("command-$runId", header(start, until) + excerpt, "error",
            truncated = truncated || start > 0 || until < text.length, displayName = displayName)
    }
}

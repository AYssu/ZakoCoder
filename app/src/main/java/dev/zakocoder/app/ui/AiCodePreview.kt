package dev.zakocoder.app.ui

/** Bounds Text layout even for newline-heavy or single-line code. Copy always uses the source. */
internal object AiCodePreview {
    const val COLLAPSED_LINES = 12
    const val COLLAPSED_CHARS = 1600
    const val EXPANDED_LINES = 120
    const val EXPANDED_CHARS = 4000

    data class Preview(val text: String, val lines: Int, val totalLines: Int, val limited: Boolean)

    fun of(source: String, expanded: Boolean): Preview {
        val code = source.removeSuffix("\n").removeSuffix("\r")
        val maxChars = if (expanded) EXPANDED_CHARS else COLLAPSED_CHARS
        val maxLines = if (expanded) EXPANDED_LINES else COLLAPSED_LINES
        var end = 0
        var lines = if (code.isEmpty()) 0 else 1
        while (end < code.length && end < maxChars) {
            if (code[end] == '\n') {
                if (lines >= maxLines) break
                lines++
            }
            end++
        }
        if (end in 1 until code.length &&
            ((code[end - 1].isHighSurrogate() && code[end].isLowSurrogate()) ||
                (code[end - 1] == '\r' && code[end] == '\n'))) end--
        return Preview(code.take(end), lines, if (code.isEmpty()) 0 else code.count { it == '\n' } + 1, end < code.length)
    }
}

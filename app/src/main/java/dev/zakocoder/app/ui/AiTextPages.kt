package dev.zakocoder.app.ui

/** Lossless UTF-16 ranges with bounded layout work, including newline-heavy source. */
internal object AiTextPages {
    const val MARKDOWN_LIMIT = 64000
    const val PAGE_CHARS = 4000
    const val PAGE_LINES = 120

    fun ranges(text: String): List<IntRange> = buildList {
        var start = 0
        while (start < text.length) {
            var end = start
            var lines = 0
            while (end < text.length && end - start < PAGE_CHARS && lines < PAGE_LINES) {
                if (text[end] == '\n' || (text[end] == '\r' && text.getOrNull(end + 1) != '\n')) lines++
                end++
            }
            if (end < text.length && end > start &&
                ((text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) ||
                    (text[end - 1] == '\r' && text[end] == '\n'))) end--
            check(end > start)
            add(start until end)
            start = end
        }
    }
}

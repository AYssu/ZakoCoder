package dev.zakocoder.app.ai

/** Largest fitting evidence prefix. Never drop the middle or split a Unicode surrogate pair. */
internal fun summaryChunkEnd(text: String, start: Int, maxChars: Int, fits: (String) -> Boolean): Int {
    require(start in text.indices && maxChars >= 2)
    var low = 1
    var high = minOf(maxChars, text.length - start)
    var best = start
    while (low <= high) {
        val length = low + (high - low) / 2
        var end = start + length
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        if (end == start) { low = length + 1; continue }
        if (fits(text.substring(start, end))) { best = end; low = length + 1 } else high = length - 1
    }
    require(best > start) { "No evidence fits beside pinned summary memory" }
    return best
}

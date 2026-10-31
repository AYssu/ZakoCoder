package dev.zakocoder.app.ui

/** Fixed layout budget, without splitting a UTF-16 pair at either preview boundary. */
internal object AiReasoningPreview {
    const val MAX_CHARS = 12000
    fun of(source: String, streaming: Boolean): String {
        var start = if (streaming) (source.length - MAX_CHARS).coerceAtLeast(0) else 0
        var end = if (streaming) source.length else source.length.coerceAtMost(MAX_CHARS)
        if (start in 1 until source.length && source[start].isLowSurrogate() && source[start - 1].isHighSurrogate()) start++
        if (end in 1 until source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
        return source.substring(start, end)
    }
}

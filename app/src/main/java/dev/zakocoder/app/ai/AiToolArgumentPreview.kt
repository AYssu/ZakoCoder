package dev.zakocoder.app.ai

/** UI-only partial JSON reader. Scans without copying the complete argument buffer. */
internal fun toolArgumentLine(source: CharSequence): String {
    var position = 0
    fun string(keep: Boolean): String {
        val line = StringBuilder()
        var previous = ""
        while (position < source.length) {
            var c = source[position++]
            if (c == '"') break
            if (c == '\\') {
                if (position >= source.length) break
                c = when (val escaped = source[position++]) {
                    'n', 'r' -> '\n'
                    't' -> ' '
                    'u' -> {
                        if (position + 4 > source.length) break
                        var code = 0
                        var valid = true
                        repeat(4) {
                            val digit = source[position++].digitToIntOrNull(16)
                            if (digit == null) valid = false else code = code * 16 + digit
                        }
                        if (!valid) continue
                        code.toChar()
                    }
                    else -> escaped
                }
            }
            if (!keep) continue
            if (c == '\n') {
                if (line.isNotBlank()) previous = line.toString().trim()
                line.setLength(0)
            } else {
                line.append(c)
                if (line.length > 120) line.delete(0, line.length - 120)
            }
        }
        return if (line.isNotBlank()) line.toString().trim() else previous
    }
    var depth = 0
    var best = ""
    var priority = 0
    while (position < source.length) {
        when (source[position++]) {
            '{', '[' -> depth++
            '}', ']' -> depth--
            '"' -> {
                val key = string(keep = depth == 1)
                while (position < source.length && source[position].isWhitespace()) position++
                if (depth != 1 || position >= source.length || source[position] != ':') continue
                position++
                while (position < source.length && source[position].isWhitespace()) position++
                if (position >= source.length || source[position] != '"') continue
                position++
                val rank = when (key) { "command" -> 3; "content" -> 2; "path" -> 1; else -> 0 }
                val value = string(keep = rank > 0)
                if (rank > priority && value.isNotEmpty()) { priority = rank; best = value }
            }
        }
    }
    return best
}

/** Arguments used for execution remain untouched; only the displayed buffers are bounded. */
internal fun liveCallDraft(index: Int, name: String, arguments: CharSequence): AiCallDraft = AiCallDraft(
    index, name, arguments.subSequence(0, minOf(arguments.length, 64000)).toString(),
    if (arguments.length > 64000) arguments.subSequence(arguments.length - 1024, arguments.length).toString() else "",
    toolArgumentLine(arguments),
)

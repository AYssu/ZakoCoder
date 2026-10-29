package dev.zakocoder.app.mcp

/** Rejects duplicate keys, lenient JSON extensions and excessive nesting before org.json parsing. */
internal object McpJson {
    fun check(source: String) {
        class Reader {
            var at = 0
            fun space() { while (at < source.length && source[at] in " \r\n\t") at++ }
            fun expect(char: Char) { space(); require(at < source.length && source[at++] == char) }
            fun string(): String {
                space(); val start = at; expect('"')
                while (at < source.length) {
                    val char = source[at++]
                    require(char.code >= 32)
                    if (char == '"') return org.json.JSONArray("[${source.substring(start, at)}]").getString(0)
                    if (char == '\\') {
                        require(at < source.length)
                        when (source[at++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> { require(at + 4 <= source.length && source.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                            else -> error("escape")
                        }
                    }
                }
                error("string")
            }
            fun value(depth: Int) {
                require(depth <= 32); space(); require(at < source.length)
                when (source[at]) {
                    '{' -> {
                        at++; space(); val keys = mutableSetOf<String>()
                        if (at < source.length && source[at] == '}') { at++; return }
                        while (true) {
                            require(keys.add(string())); expect(':'); value(depth + 1); space()
                            require(at < source.length)
                            if (source[at++] == '}') return
                            require(source[at - 1] == ',')
                        }
                    }
                    '[' -> {
                        at++; space(); if (at < source.length && source[at] == ']') { at++; return }
                        while (true) {
                            value(depth + 1); space(); require(at < source.length)
                            if (source[at++] == ']') return
                            require(source[at - 1] == ',')
                        }
                    }
                    '"' -> string()
                    else -> {
                        val start = at
                        while (at < source.length && source[at] !in " \r\n\t,]}") at++
                        val token = source.substring(start, at)
                        require(token in setOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    }
                }
            }
        }
        try { Reader().apply { value(0); space(); require(at == source.length) } }
        catch (_: Exception) { throw McpException(McpProblem.InvalidConfig) }
    }
}

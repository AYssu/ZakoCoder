package dev.zakocoder.app.ai

/** Stable application actions; none of these tokens is a shell command or model instruction. */
internal enum class AiSlashCommandId {
    Help, New, Sessions, Rename, Compact, Context, Model, Thinking, Skill, Mcp, Permissions, Stop, Export, Import, Recap,
}

internal data class AiSlashCommandEntry(val id: AiSlashCommandId, val token: String)

internal sealed interface AiSlashParseResult {
    data object None : AiSlashParseResult
    data class Command(val id: AiSlashCommandId, val arguments: String) : AiSlashParseResult
    data class Skill(val name: String, val task: String) : AiSlashParseResult
    data class Unknown(val token: String) : AiSlashParseResult
    data class Invalid(val reason: Reason) : AiSlashParseResult
    enum class Reason { ControlCharacter, MissingSkillName, MalformedSkillName }
}

/** Pure parsing only. Callers own authorization, UI strings and skill identity resolution. */
internal object AiSlashCommands {
    val catalog: List<AiSlashCommandEntry> = listOf(
        AiSlashCommandEntry(AiSlashCommandId.Help, "help"),
        AiSlashCommandEntry(AiSlashCommandId.New, "new"),
        AiSlashCommandEntry(AiSlashCommandId.Sessions, "sessions"),
        AiSlashCommandEntry(AiSlashCommandId.Rename, "rename"),
        AiSlashCommandEntry(AiSlashCommandId.Compact, "compact"),
        AiSlashCommandEntry(AiSlashCommandId.Context, "context"),
        AiSlashCommandEntry(AiSlashCommandId.Model, "model"),
        AiSlashCommandEntry(AiSlashCommandId.Thinking, "thinking"),
        AiSlashCommandEntry(AiSlashCommandId.Skill, "skill"),
        AiSlashCommandEntry(AiSlashCommandId.Mcp, "mcp"),
        AiSlashCommandEntry(AiSlashCommandId.Permissions, "permissions"),
        AiSlashCommandEntry(AiSlashCommandId.Stop, "stop"),
        AiSlashCommandEntry(AiSlashCommandId.Export, "export"),
        AiSlashCommandEntry(AiSlashCommandId.Import, "import"),
        AiSlashCommandEntry(AiSlashCommandId.Recap, "recap"),
    )

    /** Accepts either a token prefix or a leading slash, retaining the stable catalog order. */
    fun suggestions(prefix: String): List<AiSlashCommandEntry> {
        val token = prefix.trimStart().removePrefix("/")
        if (token.any(Char::isWhitespace) || token.contains('/') || token.contains(':')) return emptyList()
        return catalog.filter { it.token.startsWith(token, ignoreCase = true) }
    }

    /**
     * Only a slash at the start of user input is interpreted. Absolute paths such as /usr/bin
     * remain ordinary text. Unknown slash actions must be handled locally, never sent as a task.
     * Argument separators are removed; subsequent text, line endings and trailing whitespace
     * are preserved, including multi-line skill tasks and compaction instructions.
     */
    fun parse(input: String): AiSlashParseResult {
        val text = input.trimStart()
        if (!text.startsWith('/')) return AiSlashParseResult.None
        if (text.any(::unsafeControl))
            return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.ControlCharacter)
        val tokenEnd = text.indexOfFirst(Char::isWhitespace).takeIf { it >= 0 } ?: text.length
        val token = text.substring(1, tokenEnd)
        if (text.regionMatches(1, "skill:", 0, 6, ignoreCase = true)) return parseSkill(text, 7)
        if (token.contains('/')) return AiSlashParseResult.None
        val command = catalog.firstOrNull { it.token.equals(token, ignoreCase = true) }
            ?: return AiSlashParseResult.Unknown(token)
        return AiSlashParseResult.Command(command.id, text.substring(skipWhitespace(text, tokenEnd)))
    }

    private fun parseSkill(text: String, start: Int): AiSlashParseResult {
        if (start >= text.length || text[start].isWhitespace())
            return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MissingSkillName)
        val quote = text[start].takeIf { it == '"' || it == '\'' }
        val name: String
        val end: Int
        if (quote == null) {
            end = text.indexOfFirstFrom(start, Char::isWhitespace)
            name = text.substring(start, end)
        } else {
            val result = StringBuilder()
            var cursor = start + 1
            var closed = false
            while (cursor < text.length) {
                val char = text[cursor++]
                if (char == quote) { closed = true; break }
                if (char == '\\') {
                    val escaped = text.getOrNull(cursor++)
                    if (escaped != quote && escaped != '\\')
                        return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MalformedSkillName)
                    result.append(escaped)
                } else result.append(char)
            }
            if (!closed || cursor < text.length && !text[cursor].isWhitespace())
                return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MalformedSkillName)
            name = result.toString()
            end = cursor
        }
        if (name.isBlank()) return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MissingSkillName)
        // This is an exact display-name lookup, not a filesystem path. Imported skill names
        // may legitimately contain slashes; execution resolves the matched internal identity.
        if (name != name.trim() || name.any { it == '\n' || it == '\r' || it == '\t' })
            return AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MalformedSkillName)
        return AiSlashParseResult.Skill(name, text.substring(skipWhitespace(text, end)))
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return length
    }

    private fun unsafeControl(char: Char): Boolean =
        char.isISOControl() && char !in "\t\r\n" ||
            char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069'
}

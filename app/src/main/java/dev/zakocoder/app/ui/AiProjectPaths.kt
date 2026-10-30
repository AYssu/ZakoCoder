package dev.zakocoder.app.ui

/** Lexical references only; callers must still resolve against the selected project's safe file API. */
internal object AiProjectPaths {
    data class Reference(val start: Int, val end: Int, val relative: String)

    fun resolve(value: String, projectId: String?): String? {
        if (projectId.isNullOrBlank() || value.isBlank() || value.length > 4096 || value.any { it.code < 32 }) return null
        var path = value.trim()
        if (path.any { it.code < 32 } || path.contains('\\') || path.contains('%') || path.contains('?') || path.contains('#')) return null
        path = path.replace(Regex(":\\d+(?::\\d+)?$"), "")
        val prefix = "/workspace/$projectId/"
        path = when {
            path.startsWith(prefix) -> path.removePrefix(prefix)
            path.startsWith("/") -> return null
            path.startsWith("./") -> path.removePrefix("./")
            else -> path
        }
        if (path.contains(':') || path.startsWith('~')) return null
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        // A bare word is prose, not a file reference. Explicit links can use dotted filenames.
        if (parts.size == 1 && !Regex("[^.\\s]+\\.[^.\\s]+").matches(path)) return null
        return path
    }

    fun references(text: String, projectId: String?): List<Reference> {
        if (projectId == null) return emptyList()
        return candidate.findAll(text).mapNotNull { match ->
            val start = match.range.first
            // Never turn a URL fragment or an external absolute path into a relative reference.
            if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "/_~:%")) return@mapNotNull null
            val raw = match.value.trimEnd('.', ',', ';', '!', '，', '。', '；', '！', '：')
            val relative = resolve(raw, projectId) ?: return@mapNotNull null
            Reference(start, start + raw.length, relative)
        }.toList()
    }

    private val candidate = Regex("(?:/workspace/[^\\s/`<>\\[\\]（）()，。；！：]+/|\\./)?[A-Za-z0-9_@-][A-Za-z0-9_@.\\-]*(?:/[A-Za-z0-9_@.\\-]+)+(?:/[A-Za-z0-9_@.\\-]+)*(:[0-9]+(?::[0-9]+)?)?|[A-Za-z0-9_@-][A-Za-z0-9_@.\\-]*\\.[A-Za-z][A-Za-z0-9_\\-]*(?::[0-9]+(?::[0-9]+)?)?")
}

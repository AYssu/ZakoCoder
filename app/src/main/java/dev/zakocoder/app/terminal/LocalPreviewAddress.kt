package dev.zakocoder.app.terminal

import java.net.URI
import java.util.Locale

/** No DNS, networking or storage. Candidates are origins, never output-supplied auth/query/path. */
object LocalPreviewAddress {
    private val web = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
    fun normalize(value: String, originOnly: Boolean = false): String? = try {
        val text = value.trim()
        if (text.length !in 1..2048 || text.any { it.isWhitespace() || it.isISOControl() }) null else {
            val uri = URI(text)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = when (uri.host?.lowercase(Locale.ROOT)) {
                "localhost" -> "localhost"
                "127.0.0.1", "0.0.0.0" -> "127.0.0.1"
                "[::1]", "::1", "[::]", "::" -> "[::1]"
                else -> null
            }
            if (scheme !in setOf("http", "https") || host == null || uri.rawUserInfo != null || uri.port !in 1..65535) null
            else {
                val suffix = if (originOnly) "/" else uri.rawPath.orEmpty().ifEmpty { "/" } +
                    uri.rawQuery?.let { "?$it" }.orEmpty() + uri.rawFragment?.let { "#$it" }.orEmpty()
                URI("$scheme://$host:${uri.port}$suffix").toASCIIString()
            }
        }
    } catch (_: java.net.URISyntaxException) { null }

    fun candidates(output: String): List<String> = web.findAll(output.takeLast(32768))
        .mapNotNull { normalize(it.value.trimEnd('.', ',', ';', ')', ']', '}'), originOnly = true) }
        .toList().asReversed().distinct().take(4)
}

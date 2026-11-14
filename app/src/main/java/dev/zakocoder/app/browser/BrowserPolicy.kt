package dev.zakocoder.app.browser

import java.net.URI
import java.util.Locale

/** Only explicit HTTP(S) pages, no URI credentials or privileged WebView schemes. */
internal object BrowserPolicy {
    data class Address(val url: String, val origin: String, val local: Boolean)

    fun address(raw: String): Address {
        require(raw.length in 1..8192 && raw.none { it.isWhitespace() || it.code < 32 || it == '\\' }) { "BROWSER_INVALID_URL" }
        val value = if (raw.contains("://")) raw else {
            // Only an omitted scheme is inferred. Unknown explicit schemes are rejected below.
            require(!raw.contains(':') || raw.substringBefore(':').let { it == "localhost" || it.contains('.') || it.startsWith('[') }) { "BROWSER_INVALID_URL" }
            val host = raw.substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
            (if (host == "localhost" || host == "127.0.0.1" || raw.startsWith("[::1]")) "http://" else "https://") + raw
        }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("BROWSER_INVALID_URL") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "BROWSER_UNSUPPORTED_SCHEME" }
        require(uri.rawUserInfo == null && uri.host != null && uri.rawAuthority != null) { "BROWSER_INVALID_URL" }
        val host = uri.host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        require(host.isNotEmpty() && (uri.port == -1 || uri.port in 1..65535)) { "BROWSER_INVALID_URL" }
        val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        val authority = (if (':' in host) "[$host]" else host) + (port?.let { ":$it" } ?: "")
        val origin = "$scheme://$authority"
        return Address(origin + (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: ""), origin,
            host == "localhost" || host == "127.0.0.1" || host == "::1")
    }
}

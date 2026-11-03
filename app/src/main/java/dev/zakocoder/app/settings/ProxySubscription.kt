package dev.zakocoder.app.settings

import java.net.URI
import java.security.MessageDigest

/** Connection protocols a subscription can describe; only [Http] and [Socks5] are usable today. */
enum class ProxyProtocol { Http, Socks5, Vmess, Ss, Trojan, Vless, Hysteria, Tuic, Other }

/**
 * One node decoded from a subscription.
 *
 * [supported] is the single source of truth for "can this node be written back to settings":
 * - HTTP is supported only without credentials, because this app's HTTP proxy path has no
 *   authentication support;
 * - SOCKS5 is supported with or without a full RFC 1929 username/password pair, but a lone
 *   username without a password is rejected rather than silently dropping half a credential;
 * - every other protocol is importable metadata only and never reported as usable.
 *
 * [proxyUrl] is the canonical app-setting URL. It contains URL-encoded credentials for SOCKS5 and
 * therefore must never be logged or shown; [toString] is redacted for that reason.
 */
data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    /**
     * URL this node would write into settings. Built once so [supported] can validate it with the
     * same parser that consumes the setting: this rejects IDN/ASCII-unsafe hosts and any credential
     * combination the settings layer would silently drop.
     */
    private val candidateProxyUrl: String? = when (protocol) {
        ProxyProtocol.Http -> if (username == null && password == null) "http://${formatHost(host)}:$port" else null
        ProxyProtocol.Socks5 -> {
            // RFC 1929 needs both halves; a lone username or lone password must never be downgraded
            // into an unauthenticated SOCKS5 endpoint.
            if (username == null && password == null) {
                "socks5://${formatHost(host)}:$port"
            } else if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                "socks5://${encodeUserInfo(username)}:${encodeUserInfo(password)}@${formatHost(host)}:$port"
            } else {
                null
            }
        }
        else -> null
    }

    val supported: Boolean = candidateProxyUrl?.let { candidate ->
        runCatching { NetworkProxy.parse(candidate) }.getOrNull() != null
    } ?: false

    /** URL accepted by [NetworkProxy.parse] when the node is supported; empty otherwise. */
    val proxyUrl: String = candidateProxyUrl?.takeIf { supported }.orEmpty()

    // A generated data-class toString would print the password; never allow that.
    override fun toString() =
        "ProxyNode(id=$id, name=$name, protocol=$protocol, host=$host, port=$port, hasCredentials=${username != null})"
}

enum class SubscriptionFormat { PlainLinks, Base64Links, ClashYaml }

/**
 * A decoded subscription. [nodes] keep their original order (used in stable ids).
 *
 * [maskedSource] is the only form of the source URL allowed in UI/logs: scheme + host + placeholder,
 * never the path/query that usually carries the subscription token.
 */
data class ProxySubscription(
    val sourceUrl: String,
    val fetchedAt: Long,
    val nodes: List<ProxyNode>,
    val format: SubscriptionFormat,
) {
    val supportedCount: Int get() = nodes.count { it.supported }

    val maskedSource: String
        get() {
            val uri = try {
                URI(sourceUrl)
            } catch (_: Exception) {
                return ""
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return ""
            val host = uri.host
            if (host.isNullOrEmpty()) return ""
            return "https://$host/•••"
        }

    override fun toString() =
        "ProxySubscription(maskedSource=$maskedSource, fetchedAt=$fetchedAt, nodes=${nodes.size}, format=$format)"
}

/**
 * Result of looking at a subscription link. Only [Airport] and [SingleNode] are importable; the
 * unsupported-only and unknown cases must stay explicit so the UI never offers a dead "save" action.
 */
sealed interface ProxyProbe {
    data class Airport(val subscription: ProxySubscription) : ProxyProbe
    data class SingleNode(val node: ProxyNode) : ProxyProbe

    /** Every decoded node is listed (so the UI can show which protocols were skipped) but unusable. */
    data class UnsupportedOnly(val nodes: List<ProxyNode>) : ProxyProbe {
        val total: Int get() = nodes.size
    }

    data object NotSubscription : ProxyProbe
}

/** Stable, UI-safe failure categories. Never carries provider text or the full subscription URL. */
enum class ProxySubscriptionError { InvalidLink, InsecureScheme, Unreachable, HttpStatus, TooLarge, UnsupportedContent, NotFound, Empty }

/**
 * Internal failure type used by the fetcher/parser boundary. The message is the enum name only so a
 * catch-all logger cannot leak a provider error body, response headers or the subscription URL.
 */
class ProxySubscriptionException(val reason: ProxySubscriptionError, cause: Throwable? = null) : Exception(reason.name, cause) {
    override fun toString() = "ProxySubscriptionException($reason)"
}

// ---- Shared helpers (plain JVM, no Android imports) ------------------------------------------

/** Digest input is deterministic across restarts and processes; 16 hex chars are ample for 512 ids. */
internal fun subscriptionNodeId(protocol: ProxyProtocol, host: String, port: Int, username: String?, name: String, index: Int): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$protocol|$host|$port|${username.orEmpty()}|$name|$index".toByteArray(Charsets.UTF_8),
    )
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

/** IPv6 literals need brackets when embedded in a URL; stored hosts are kept unbracketed. */
internal fun formatHost(host: String): String =
    if (host.contains(':') && !(host.startsWith("[") && host.endsWith("]"))) "[$host]" else host

internal fun stripHostBrackets(host: String): String = host.removeSurrounding("[", "]")

/**
 * Percent-encodes user info exactly like [NetworkProxy] does, so a node URL written back to
 * settings always parses on the other side. Unreserved characters stay readable; everything else
 * is UTF-8 percent-encoded.
 */
internal fun encodeUserInfo(value: String): String {
    val hex = "0123456789ABCDEF"
    return buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val character = code.toChar()
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "-._~") {
                append(character)
            } else {
                append('%').append(hex[code shr 4]).append(hex[code and 0x0f])
            }
        }
    }
}

/** Strict percent-decoder for one user-info component; returns null for malformed escapes. */
internal fun decodeUserInfo(value: String): String? {
    val bytes = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%') {
            if (index + 2 >= value.length) return null
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes.write(byte)
            index += 3
        } else {
            bytes.write(character.toString().toByteArray(Charsets.UTF_8))
            index++
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

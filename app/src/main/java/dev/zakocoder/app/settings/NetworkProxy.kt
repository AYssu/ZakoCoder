package dev.zakocoder.app.settings

import java.io.ByteArrayOutputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException

/**
 * Parses the single user-entered proxy setting and translates it for each consumer.
 *
 * Contract: the setting is either empty (proxy disabled) or a `http://host:port` endpoint or a
 * `socks5://[username:password@]host:port` endpoint. HTTP never accepts credentials; SOCKS5
 * accepts a username and password that are carried by RFC 1929. Paths, query, fragment and any
 * other scheme are rejected instead of silently ignored, so the UI can report one precise error.
 * This file is plain JVM code (no Android imports) so the mapping can be unit-tested without a
 * device.
 *
 * Credential handling is deliberately split:
 * - [Spec.endpoint] is the canonical value that is persisted and shown to the UI;
 * - [Spec.display] masks the password so it can be rendered in a settings row;
 * - [Spec.proxyUrl] is the only form containing URL-encoded credentials and stays internal
 *   (JVM proxy authentication and the guest `ALL_PROXY` variable).
 * - [Credentials.toString] and [Spec.toString] never include the password.
 *
 * SOCKS5 is carried by [java.net.Proxy] for Java/OkHttp requests and by `ALL_PROXY` inside the
 * guest. HTTP is carried by the same variables plus the apt address-config workaround. Guest
 * tools must not receive a fake `http_proxy` for a SOCKS5 endpoint: that would make them speak
 * HTTP proxy framing to a SOCKS listener.
 */
object NetworkProxy {
    /** RFC 1929 credential pair. Only SOCKS5 accepts these; HTTP proxies never do. */
    data class Credentials(val username: String, val password: String) {
        override fun toString() = "Credentials(<redacted>)"
    }

    data class Spec(
        val scheme: String,
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) {
        val isSocks: Boolean get() = scheme == "socks5"
        val hasCredentials: Boolean get() = username != null && password != null

        /** Canonical endpoint without user info or trailing path. This is what is persisted. */
        val endpoint: String get() = "$scheme://$host:$port"

        /** UI form: the password (when any) is replaced by `***`; username is URL-encoded. */
        val display: String get() = when {
            username == null -> endpoint
            password.isNullOrEmpty() -> "$scheme://${encodeUserInfo(username)}@$host:$port"
            else -> "$scheme://${encodeUserInfo(username)}:***@$host:$port"
        }

        /** Full URL with URL-encoded credentials. Internal only: JVM auth and guest environment. */
        val proxyUrl: String get() = when {
            username == null -> endpoint
            password.isNullOrEmpty() -> "$scheme://${encodeUserInfo(username)}@$host:$port"
            else -> "$scheme://${encodeUserInfo(username)}:${encodeUserInfo(password)}@$host:$port"
        }

        // A generated data-class toString would print the password; never allow that.
        override fun toString() = "Spec(scheme=$scheme, host=$host, port=$port, hasCredentials=$hasCredentials)"
    }

    private const val MAX_CREDENTIAL_LENGTH = 255
    private val supportedSchemes = setOf("http", "socks5")
    private val hexDigits = "0123456789ABCDEF"

    /**
     * Returns null for an empty/blank setting, otherwise the parsed endpoint (with optional SOCKS5
     * credentials).
     * Throws [IllegalArgumentException] for any non-blank value that is not an exact supported
     * endpoint (including missing port and port outside 1..65535, credentials on HTTP, or only
     * half of a SOCKS5 credential pair).
     */
    fun parse(raw: String): Spec? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("invalid proxy syntax")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme in supportedSchemes) { "unsupported proxy scheme" }
        require(uri.query == null && uri.fragment == null) { "proxy query/fragment is not supported" }
        require(uri.path.orEmpty() in setOf("", "/")) { "proxy path is not supported" }
        val host = uri.host
        require(host != null) { "proxy host is missing" }
        require(host.isNotEmpty()) { "proxy host is empty" }
        val port = uri.port
        require(port in 1..65535) { "proxy port is outside 1..65535" }
        val rawUserInfo = uri.rawUserInfo ?: return Spec(requireNotNull(scheme), host, port)
        require(scheme == "socks5") { "proxy credentials are only supported for socks5" }
        // Split the raw user info first so an encoded ':' inside the username stays unambiguous.
        val separator = rawUserInfo.indexOf(':')
        require(separator > 0) { "proxy user info is malformed" }
        val username = decodeUserInfo(rawUserInfo.substring(0, separator))
        val password = decodeUserInfo(rawUserInfo.substring(separator + 1))
        require(username.isNotEmpty()) { "proxy username is empty" }
        require(password.isNotEmpty()) { "proxy password is empty" }
        require(username.length <= MAX_CREDENTIAL_LENGTH && password.length <= MAX_CREDENTIAL_LENGTH) {
            "proxy credentials are too long"
        }
        require(!username.any(Char::isISOControl) && !password.any(Char::isISOControl)) {
            "proxy credentials contain control characters"
        }
        return Spec(requireNotNull(scheme), host, port, username, password)
    }

    /** Credential pair for an already parsed SOCKS5 URL, or null when none is configured. */
    fun credentials(raw: String): Credentials? {
        val spec = parse(raw) ?: return null
        if (!spec.hasCredentials) return null
        return Credentials(requireNotNull(spec.username), requireNotNull(spec.password))
    }

    /**
     * Combines a stored endpoint with encrypted-at-rest credentials into the internal full URL.
     * Blank input stays blank; credentials are ignored for HTTP and for a credential-less call.
     */
    fun withCredentials(raw: String, credentials: Credentials?): String {
        val spec = parse(raw) ?: return ""
        if (!spec.isSocks || credentials == null) return spec.endpoint
        return spec.copy(username = credentials.username, password = credentials.password).proxyUrl
    }

    /**
     * Java/OkHttp proxy handle. HTTP uses [Proxy.Type.HTTP]; SOCKS5 uses [Proxy.Type.SOCKS].
     *
     * DNS semantics for application requests: the target hostname is sent to the SOCKS5 proxy as
     * the CONNECT address and resolved by the proxy, not by the device. An independent on-device
     * probe of `HttpURLConnection` and of this project's OkHttp client observed the SOCKS5
     * request arriving with ATYP=3 (domain name), so no `socks5h`-style switch is needed for
     * application traffic. Guest tools differ: see [guestEnvironment].
     */
    fun javaProxy(raw: String): Proxy? {
        val spec = parse(raw) ?: return null
        val type = if (spec.isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress(spec.host, spec.port))
    }

    /**
     * Environment entries injected into guest processes. HTTP keeps the original five variables
     * (both lower/upper case, plus a loopback bypass). SOCKS5 only exports `ALL_PROXY`/`all_proxy`
     * and the same bypass: curl/git/Go understand it, while tools that only read `http_proxy`
     * intentionally fall back to a direct connection rather than mis-speaking to the proxy.
     *
     * For SOCKS5 the URL is [Spec.proxyUrl], so credentials are exported in cleartext inside the
     * guest environment (documented security trade-off, not a leak to the host log).
     *
     * DNS for guest tools is up to the tool parsing the variable, unlike this app's own requests
     * (see [javaProxy]). Only the `socks5h://` URL scheme asks the proxy to resolve the target
     * hostname; with the `socks5://` scheme this project exports, curl/git resolve it locally and
     * send the resulting IP over SOCKS5. This project deliberately does not export `socks5h`.
     */
    fun guestEnvironment(raw: String): List<String> {
        val spec = parse(raw) ?: return emptyList()
        val noProxy = "no_proxy=localhost,127.0.0.1"
        return if (spec.isSocks) listOf("ALL_PROXY=${spec.proxyUrl}", "all_proxy=${spec.proxyUrl}", noProxy)
        else listOf("http_proxy=${spec.endpoint}", "https_proxy=${spec.endpoint}", "HTTP_PROXY=${spec.endpoint}",
            "HTTPS_PROXY=${spec.endpoint}", noProxy)
    }

    /** apt applies `Acquire::Connect::AddrConfig "false"` only for HTTP proxies, never for SOCKS5. */
    fun aptAddrConfigWorkaround(raw: String): Boolean {
        val spec = parse(raw) ?: return false
        return !spec.isSocks
    }

    // ---- Global JDK SOCKS5 authentication (RFC 1929) ---------------------------------------

    private val authenticationLock = Any()

    @Volatile private var activeCredentials: Credentials? = null

    /**
     * Single application-wide authenticator. Both OkHttp and `HttpURLConnection` delegate SOCKS5
     * username/password authentication to the JDK global authenticator, so this is the only hook.
     *
     * Observed JDK/Android behaviour (probed, see `.local/dev/evidence-socks-auth.md`): the SOCKS
     * client calls the legacy `requestPasswordAuthentication` and the framework resets
     * RequestorType to SERVER, so requiring PROXY alone would silently break SOCKS5 auth. The
     * credential pair is therefore returned for proxy requests and for protocol "SOCKS5" only.
     * Server (origin) authentication requests never receive it.
     */
    private val authenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val credentials = activeCredentials ?: return null
            if (requestorType != RequestorType.PROXY && requestingProtocol != "SOCKS5") return null
            return PasswordAuthentication(credentials.username, credentials.password.toCharArray())
        }
    }

    /**
     * Installs the singleton authenticator (when credentials are present) and publishes the
     * current SOCKS5 credentials for the next connection. Calling it with null clears the
     * credentials so a stale pair can never be sent to a different endpoint.
     *
     * The same singleton instance is always used; `Authenticator.setDefault` is re-applied because
     * tests (and no production code) may restore another default between configurations and
     * `Authenticator.getDefault()` is not in the API-26 surface this project targets.
     */
    fun configureSocksAuthentication(credentials: Credentials?) {
        synchronized(authenticationLock) {
            activeCredentials = credentials
            if (credentials != null) Authenticator.setDefault(authenticator)
        }
    }

    private fun decodeUserInfo(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                require(index + 2 < value.length) { "invalid proxy credential encoding" }
                val encoded = value.substring(index + 1, index + 3)
                val byte = encoded.toIntOrNull(16) ?: throw IllegalArgumentException("invalid proxy credential encoding")
                bytes.write(byte)
                index += 3
            } else {
                bytes.write(character.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun encodeUserInfo(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val character = code.toChar()
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "-._~") {
                append(character)
            } else {
                append('%').append(hexDigits[code shr 4]).append(hexDigits[code and 0x0f])
            }
        }
    }
}

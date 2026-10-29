package dev.zakocoder.app.settings

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException

/**
 * Parses the single user-entered proxy setting and translates it for each consumer.
 *
 * Contract: the setting is either empty (proxy disabled) or a bare `http://host:port` /
 * `socks5://host:port` endpoint. Credentials, paths, query, fragment and any other scheme are
 * rejected instead of silently ignored, so the UI can report one precise error. This file is
 * plain JVM code (no Android imports) so the mapping can be unit-tested without a device.
 *
 * SOCKS5 is carried by [java.net.Proxy] for Java/OkHttp requests and by `ALL_PROXY` inside the
 * guest. HTTP is carried by the same variables plus the apt address-config workaround. Guest
 * tools must not receive a fake `http_proxy` for a SOCKS5 endpoint: that would make them speak
 * HTTP proxy framing to a SOCKS listener.
 */
object NetworkProxy {
    data class Spec(val scheme: String, val host: String, val port: Int) {
        val isSocks: Boolean get() = scheme == "socks5"
        /** Canonical endpoint without user info or trailing path, safe to place in a guest env var. */
        val endpoint: String get() = "$scheme://$host:$port"
    }

    private val supportedSchemes = setOf("http", "socks5")

    /**
     * Returns null for an empty/blank setting, otherwise the parsed endpoint.
     * Throws [IllegalArgumentException] for any non-blank value that is not an exact supported
     * endpoint (including missing port and port outside 1..65535).
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
        require(uri.userInfo == null) { "proxy credentials are not supported" }
        require(uri.query == null && uri.fragment == null) { "proxy query/fragment is not supported" }
        require(uri.path.orEmpty() in setOf("", "/")) { "proxy path is not supported" }
        val host = uri.host
        require(host != null) { "proxy host is missing" }
        require(host.isNotEmpty()) { "proxy host is empty" }
        val port = uri.port
        require(port in 1..65535) { "proxy port is outside 1..65535" }
        return Spec(requireNotNull(scheme), host, port)
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
     * DNS for guest tools is up to the tool parsing the variable, unlike this app's own requests
     * (see [javaProxy]). Only the `socks5h://` URL scheme asks the proxy to resolve the target
     * hostname; with the `socks5://` scheme this project exports, curl/git resolve it locally and
     * send the resulting IP over SOCKS5. This project deliberately does not export `socks5h`.
     */
    fun guestEnvironment(raw: String): List<String> {
        val spec = parse(raw) ?: return emptyList()
        val noProxy = "no_proxy=localhost,127.0.0.1"
        return if (spec.isSocks) listOf("ALL_PROXY=${spec.endpoint}", "all_proxy=${spec.endpoint}", noProxy)
        else listOf("http_proxy=${spec.endpoint}", "https_proxy=${spec.endpoint}", "HTTP_PROXY=${spec.endpoint}",
            "HTTPS_PROXY=${spec.endpoint}", noProxy)
    }

    /** apt applies `Acquire::Connect::AddrConfig "false"` only for HTTP proxies, never for SOCKS5. */
    fun aptAddrConfigWorkaround(raw: String): Boolean {
        val spec = parse(raw) ?: return false
        return !spec.isSocks
    }
}

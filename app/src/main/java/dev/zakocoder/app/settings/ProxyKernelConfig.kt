package dev.zakocoder.app.settings

/**
 * Generates an Xray-core JSON config for a VMess/Shadowsocks/Trojan node behind a loopback SOCKS5
 * inbound. Plain JVM (no Android import) so the exact JSON can be unit-tested; [ProxyKernel] only
 * writes it to a private file and never logs it.
 *
 * Security/validation contract:
 * - unsupported protocols, incomplete transports, malformed endpoints/ports and unknown
 *   network/TLS/security values return null instead of producing a config that fails later;
 * - every embedded string is length-checked and control characters are rejected, then `"` and `\`
 *   are JSON-escaped. Credentials therefore stay syntactically confined to their string literal;
 * - the generated inbound listens on 127.0.0.1 only with auth=noauth and UDP disabled, so it is a
 *   local bridge for the app's own requests rather than a new LAN-facing proxy.
 */
object ProxyKernelConfig {
    /** Returns the Xray config JSON, or null when [node] cannot be represented safely. */
    fun build(
        node: ProxyNode,
        listenPort: Int,
        username: String? = null,
        password: String? = null,
    ): String? {
        if (listenPort !in 1..65535) return null
        if (!validHost(node.host) || node.port !in 1..65535) return null
        val inboundSettings = when {
            username != null && password != null -> {
                val user = encode(username, 64) ?: return null
                val pass = encode(password, 64) ?: return null
                "{\"auth\":\"password\",\"accounts\":[{\"user\":$user,\"pass\":$pass}],\"udp\":false}"
            }
            username == null && password == null -> "{\"auth\":\"noauth\",\"udp\":false}"
            // Half a credential would silently downgrade authentication.
            else -> return null
        }
        val outbound = when (node.protocol) {
            ProxyProtocol.Vmess -> vmessOutbound(node)
            ProxyProtocol.Ss -> shadowsocksOutbound(node)
            ProxyProtocol.Trojan -> trojanOutbound(node)
            else -> null
        } ?: return null
        return "{" +
            "\"log\":{\"loglevel\":\"warning\"}," +
            "\"inbounds\":[{\"listen\":\"127.0.0.1\",\"port\":$listenPort,\"protocol\":\"socks\"," +
            "\"settings\":$inboundSettings,\"tag\":\"socks-in\"}]," +
            "\"outbounds\":[$outbound]" +
            "}"
    }

    private fun vmessOutbound(node: ProxyNode): String? {
        val vmess = node.transport?.vmess ?: return null
        if (vmess.network !in KERNEL_NETWORKS) return null
        val uuid = encode(vmess.uuid, 128) ?: return null
        val security = vmess.security.lowercase().ifEmpty { "auto" }
        if (security !in VMESS_SECURITY) return null
        val alterId = vmess.alterId
        if (alterId !in 0..65535) return null
        val tls = tlsMode(vmess.tls) ?: return null
        val address = encode(node.host, 255) ?: return null
        val stream = vmessStream(vmess, node.host, tls) ?: return null
        return "{\"protocol\":\"vmess\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":$address," +
            "\"port\":${node.port},\"users\":[{\"id\":$uuid,\"alterId\":$alterId,\"security\":\"$security\"}]}]}," +
            "\"streamSettings\":$stream}"
    }

    private fun shadowsocksOutbound(node: ProxyNode): String? {
        val shadowsocks = node.transport?.shadowsocks ?: return null
        // Unknown methods only fail after the kernel starts; fail closed at build time instead.
        if (!isSupportedSsMethod(shadowsocks.method)) return null
        val address = encode(node.host, 255) ?: return null
        val method = encode(shadowsocks.method, 64) ?: return null
        val password = encode(shadowsocks.password, 256) ?: return null
        return "{\"protocol\":\"shadowsocks\",\"tag\":\"proxy\",\"settings\":{\"servers\":[{\"address\":$address," +
            "\"port\":${node.port},\"method\":$method,\"password\":$password}]}}"
    }

    /** Methods Xray-core can consume; anything else is reported as an unsupported node. */
    fun isSupportedSsMethod(method: String): Boolean = method.trim().lowercase() in SS_METHODS

    private fun trojanOutbound(node: ProxyNode): String? {
        val trojan = node.transport?.trojan ?: return null
        val address = encode(node.host, 255) ?: return null
        val password = encode(trojan.password, 256) ?: return null
        val serverName = encode(trojan.sni?.takeIf { it.isNotEmpty() } ?: node.host, 255) ?: return null
        return "{\"protocol\":\"trojan\",\"tag\":\"proxy\",\"settings\":{\"servers\":[{\"address\":$address," +
            "\"port\":${node.port},\"password\":$password}]}," +
            "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":$serverName}}}"
    }

    private fun vmessStream(vmess: VmessTransport, address: String, tls: String): String? {
        val base = StringBuilder("{\"network\":\"${vmess.network}\",\"security\":\"$tls\"")
        if (tls == "tls") {
            val serverName = vmess.sni?.takeIf { it.isNotEmpty() } ?: address
            val encoded = encode(serverName, 255) ?: return null
            base.append(",\"tlsSettings\":{\"serverName\":$encoded}")
        }
        when (vmess.network) {
            "ws" -> {
                val path = encode(vmess.path ?: "/", 1024) ?: return null
                base.append(",\"wsSettings\":{\"path\":$path")
                vmess.host?.takeIf { it.isNotEmpty() }?.let { host ->
                    val encoded = encode(host, 255) ?: return null
                    base.append(",\"headers\":{\"Host\":$encoded}")
                }
                base.append("}")
            }
            "grpc" -> {
                vmess.path?.takeIf { it.isNotEmpty() }?.let { serviceName ->
                    val encoded = encode(serviceName, 1024) ?: return null
                    base.append(",\"grpcSettings\":{\"serviceName\":$encoded}")
                }
            }
        }
        base.append("}")
        return base.toString()
    }

    private fun tlsMode(value: String): String? = when (value.trim().lowercase()) {
        "", "none" -> "none"
        "tls" -> "tls"
        // reality/xtls are not generated by this version; fail closed instead of guessing.
        else -> null
    }

    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 255) return false
        return host.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '?' || it == '#' || it == '@' }
    }

    /** Length-check + reject control characters, then JSON-escape quotes/backslashes. */
    private fun encode(value: String, maxLength: Int): String? {
        if (value.isEmpty() || value.length > maxLength) return null
        if (value.any(Char::isISOControl)) return null
        val escaped = StringBuilder(value.length + 8)
        for (character in value) {
            when (character) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                else -> escaped.append(character)
            }
        }
        return "\"$escaped\""
    }

    private val KERNEL_NETWORKS = setOf("tcp", "ws", "grpc")
    private val VMESS_SECURITY = setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305")
    private val SS_METHODS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "none",
    )
}

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
 * - the generated inbound listens on 127.0.0.1 only with UDP disabled; ProxyKernel passes a
 *   per-start random RFC 1929 user/pass so it is a local bridge for this app only, not a proxy
 *   other apps on the device can borrow.
 */
object ProxyKernelConfig {
    /** Returns the Xray config JSON, or null when [node] cannot be represented safely. */
    fun build(
        node: ProxyNode,
        listenPort: Int,
        username: String? = null,
        password: String? = null,
        resolvedAddress: String? = null,
    ): String? {
        if (listenPort !in 1..65535) return null
        if (!validHost(node.host) || node.port !in 1..65535) return null
        val destination = resolvedAddress ?: node.host
        if (!validHost(destination)) return null
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
            ProxyProtocol.Vmess -> vmessOutbound(node, destination)
            ProxyProtocol.Vless -> vlessOutbound(node, destination)
            ProxyProtocol.Ss -> shadowsocksOutbound(node, destination)
            ProxyProtocol.Trojan -> trojanOutbound(node, destination)
            else -> null
        } ?: return null
        return "{" +
            "\"log\":{\"loglevel\":\"warning\"}," +
            "\"inbounds\":[{\"listen\":\"127.0.0.1\",\"port\":$listenPort,\"protocol\":\"socks\"," +
            "\"settings\":$inboundSettings,\"tag\":\"socks-in\"}]," +
            "\"outbounds\":[$outbound]" +
            "}"
    }

    private fun vmessOutbound(node: ProxyNode, destination: String): String? {
        val vmess = node.transport?.vmess ?: return null
        if (vmess.network !in KERNEL_NETWORKS) return null
        val uuid = encode(vmess.uuid, 128) ?: return null
        val security = vmess.security.lowercase().ifEmpty { "auto" }
        if (security !in VMESS_SECURITY) return null
        val alterId = vmess.alterId
        if (alterId !in 0..65535) return null
        val tls = tlsMode(vmess.tls) ?: return null
        val address = encode(destination, 255) ?: return null
        val stream = vmessStream(vmess, node.host, tls) ?: return null
        return "{\"protocol\":\"vmess\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":$address," +
            "\"port\":${node.port},\"users\":[{\"id\":$uuid,\"alterId\":$alterId,\"security\":\"$security\"}]}]}," +
            "\"streamSettings\":$stream}"
    }

    /** Keep support classification and generated wire configuration in agreement. */
    fun isSupportedVless(value: VlessTransport?): Boolean {
        val v = value ?: return false
        if (runCatching { java.util.UUID.fromString(v.uuid).toString().equals(v.uuid, ignoreCase = true) }.getOrDefault(false).not()) return false
        if (v.encryption != "none" || v.network !in VLESS_NETWORKS) return false
        if (v.security !in setOf("none", "tls", "reality")) return false
        if (v.flow !in setOf("", "xtls-rprx-vision")) return false
        if (v.flow.isNotEmpty() && (v.network !in setOf("tcp", "raw") || v.security == "none")) return false
        val bounded = listOf(
            v.serverName to 255, v.publicKey to 256, v.shortId to 16, v.fingerprint to 64,
            v.spiderX to 1024, v.host to 255, v.path to 1024, v.serviceName to 1024,
        )
        if (bounded.any { (text, limit) -> text != null && (text.length > limit || text.any(Char::isISOControl)) }) return false
        if (v.security != "reality" && listOf(v.publicKey, v.shortId, v.spiderX).any { !it.isNullOrEmpty() }) return false
        if (!v.fingerprint.isNullOrEmpty() && v.fingerprint.lowercase() !in VLESS_FINGERPRINTS) return false
        if (v.security == "none" && (!v.serverName.isNullOrEmpty() || !v.fingerprint.isNullOrEmpty())) return false
        when (v.network) {
            "tcp", "raw" -> if (listOf(v.host, v.path, v.serviceName).any { !it.isNullOrEmpty() }) return false
            "ws" -> {
                if (!v.serviceName.isNullOrEmpty()) return false
                if (!v.path.isNullOrEmpty()) {
                    if (!v.path.startsWith('/') || runCatching { java.net.URI(v.path) }.isFailure) return false
                }
            }
            "grpc" -> if (!v.host.isNullOrEmpty() || !v.path.isNullOrEmpty()) return false
        }
        if (v.security == "reality") {
            if (v.serverName.isNullOrBlank() || v.publicKey.isNullOrBlank()) return false
            if (!v.spiderX.isNullOrEmpty()) {
                if (!v.spiderX.startsWith('/')) return false
                // The pinned kernel assumes URL parsing succeeds. Reject malformed escapes here
                // rather than passing a subscription-controlled parse failure into its startup.
                if (runCatching { java.net.URI(v.spiderX) }.isFailure) return false
            }
            if (v.publicKey.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return false
            val key = runCatching { java.util.Base64.getUrlDecoder().decode(v.publicKey) }.getOrNull() ?: return false
            if (key.size != 32) return false
            val sid = v.shortId.orEmpty()
            if (sid.length % 2 != 0 || sid.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
        }
        return true
    }

    /** New kernels reject cleartext public VLESS. Classify without resolving subscription hosts. */
    fun isSupportedVlessEndpoint(value: VlessTransport?, host: String): Boolean {
        if (!isSupportedVless(value)) return false
        if (value?.security != "none") return true
        val address = stripHostBrackets(host).lowercase()
        if (address == "localhost" || address == "localhost.") return true // Included in Xray's built-in private-domain matcher.
        return privateLiteralAddress(address)
    }

    private fun privateLiteralAddress(address: String): Boolean {
        fun ipv4(text: String): List<Int>? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            return parts.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith('0'))) return null
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            }
        }
        fun privateV4(bytes: List<Int>) = bytes[0] == 10 || bytes[0] == 127 ||
            (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 169 && bytes[1] == 254)
        ipv4(address)?.let { return privateV4(it) }
        if (':' !in address || address.any { it !in "0123456789abcdef:." }) return false
        var ipv6 = address
        if ('.' in ipv6) {
            val suffix = ipv6.substringAfterLast(':')
            val v4 = ipv4(suffix) ?: return false
            ipv6 = ipv6.substringBeforeLast(':') + ":" +
                ((v4[0] shl 8) or v4[1]).toString(16) + ":" + ((v4[2] shl 8) or v4[3]).toString(16)
        }
        val halves = ipv6.split("::")
        if (halves.size > 2) return false
        fun words(text: String): List<Int>? {
            if (text.isEmpty()) return emptyList()
            return text.split(':').map {
                if (it.isEmpty() || it.length > 4) return null
                it.toIntOrNull(16) ?: return null
            }
        }
        val left = words(halves[0]) ?: return false
        val right = if (halves.size == 2) words(halves[1]) ?: return false else emptyList()
        val count = left.size + right.size
        if ((halves.size == 1 && count != 8) || (halves.size == 2 && count >= 8)) return false
        val all = left + List(8 - count) { 0 } + right
        if (all.take(5).all { it == 0 } && all[5] == 0xffff) {
            return privateV4(listOf(all[6] shr 8, all[6] and 255, all[7] shr 8, all[7] and 255))
        }
        return (all[0] and 0xfe00) == 0xfc00 || (all[0] and 0xffc0) == 0xfe80 ||
            (all.take(7).all { it == 0 } && all[7] == 1)
    }

    private fun vlessOutbound(node: ProxyNode, destination: String): String? {
        val v = node.transport?.vless ?: return null
        if (!isSupportedVlessEndpoint(v, node.host)) return null
        val address = encode(destination, 255) ?: return null
        val uuid = encode(v.uuid, 128) ?: return null
        val network = if (v.network == "raw") "tcp" else v.network
        val stream = StringBuilder("{\"network\":\"$network\",\"security\":\"${v.security}\"")
        if (v.security == "reality" || v.security == "tls") {
            val options = mutableListOf<String>()
            val sni = encode(v.serverName?.takeIf { it.isNotEmpty() } ?: node.host, 255) ?: return null
            options += "\"serverName\":$sni"
            v.fingerprint?.takeIf { it.isNotEmpty() }?.let { options += "\"fingerprint\":${encode(it, 64) ?: return null}" }
            if (v.security == "reality") {
                if (v.fingerprint.isNullOrEmpty()) options += "\"fingerprint\":\"chrome\""
                options += "\"publicKey\":${encode(v.publicKey!!, 256) ?: return null}"
                options += "\"shortId\":\"${v.shortId.orEmpty()}\""
                v.spiderX?.takeIf { it.isNotEmpty() }?.let { options += "\"spiderX\":${encode(it, 1024) ?: return null}" }
            }
            stream.append(",\"${v.security}Settings\":{${options.joinToString(",")}}")
        }
        when (network) {
            "ws" -> {
                val path = encode(v.path ?: "/", 1024) ?: return null
                stream.append(",\"wsSettings\":{\"path\":$path")
                v.host?.takeIf { it.isNotEmpty() }?.let { host ->
                    stream.append(",\"headers\":{\"Host\":${encode(host, 255) ?: return null}}")
                }
                stream.append("}")
            }
            "grpc" -> {
                v.serviceName?.takeIf { it.isNotEmpty() }?.let { serviceName ->
                    stream.append(",\"grpcSettings\":{\"serviceName\":${encode(serviceName, 1024) ?: return null}}")
                }
            }
        }
        stream.append("}")
        return "{\"protocol\":\"vless\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":$address," +
            "\"port\":${node.port},\"users\":[{\"id\":$uuid,\"encryption\":\"none\",\"flow\":\"${v.flow}\"}]}]}," +
            "\"streamSettings\":$stream}"
    }

    private fun shadowsocksOutbound(node: ProxyNode, destination: String): String? {
        val shadowsocks = node.transport?.shadowsocks ?: return null
        // Unknown methods only fail after the kernel starts; fail closed at build time instead.
        if (!isSupportedSsMethod(shadowsocks.method)) return null
        val address = encode(destination, 255) ?: return null
        val method = encode(shadowsocks.method, 64) ?: return null
        val password = encode(shadowsocks.password, 256) ?: return null
        return "{\"protocol\":\"shadowsocks\",\"tag\":\"proxy\",\"settings\":{\"servers\":[{\"address\":$address," +
            "\"port\":${node.port},\"method\":$method,\"password\":$password}]}}"
    }

    /** Methods Xray-core can consume; anything else is reported as an unsupported node. */
    fun isSupportedSsMethod(method: String): Boolean = method.trim().lowercase() in SS_METHODS

    private fun trojanOutbound(node: ProxyNode, destination: String): String? {
        val trojan = node.transport?.trojan ?: return null
        val address = encode(destination, 255) ?: return null
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

    // Supported fingerprint subset verified against Xray v26.10.31 (a4a3dff5b20c).
    private val VLESS_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android",
        "edge", "360", "qq", "random", "randomized",
        "randomizednoalpn", "hellofirefox_99", "hellofirefox_102", "hellofirefox_105", "hellofirefox_120",
        "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102",
        "hellochrome_106_shuffle", "hellochrome_120", "hellochrome_131", "helloios_13", "helloios_14",
        "helloedge_85", "helloedge_106", "hellosafari_16_0", "hello360_11_0", "helloqq_11_1",
        "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn", "hellofirefox_auto", "hellofirefox_55",
        "hellofirefox_56", "hellofirefox_63", "hellofirefox_65", "hellochrome_auto", "hellochrome_58",
        "hellochrome_62", "hellochrome_70", "hellochrome_72", "helloios_auto", "helloios_11_1",
        "helloios_12_1", "helloandroid_11_okhttp", "helloedge_auto", "hellosafari_auto", "hello360_auto",
        "hello360_7_5", "helloqq_auto", "hellochrome_100_psk", "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf",
        "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq",
        "hellofirefox_148", "hellochrome_133", "hellosafari_26_3",
    )
    private val KERNEL_NETWORKS = setOf("tcp", "ws", "grpc")
    private val VLESS_NETWORKS = setOf("tcp", "raw", "ws", "grpc")
    private val VMESS_SECURITY = setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305")
    private val SS_METHODS = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "none",
    )
}

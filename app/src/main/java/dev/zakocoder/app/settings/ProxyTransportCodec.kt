package dev.zakocoder.app.settings

/**
 * Flat, JSON-friendly representation of [NodeTransport] used by [EncryptedProxySubscriptionStore].
 *
 * This codec is pure JVM so the persistence round-trip can be unit-tested without Android
 * Keystore. It is the only place that decides which transport fields are safe/complete enough to
 * persist, and must stay aligned with [ProxyKernelConfig]'s string limits:
 * - required identity fields missing/blank (VMess uuid, SS method+password, Trojan password) or a
 *   protocol/field mismatch return null, so a corrupted cache degrades to "unsupported" instead of
 *   producing a half-generated config;
 * - every persisted string is length-checked and must not contain ISO control characters;
 * - optional fields are only emitted when non-empty to keep each node small.
 */
object ProxyTransportCodec {
    const val VMESS_UUID = "vmess.uuid"
    const val VMESS_ALTER_ID = "vmess.alterId"
    const val VMESS_SECURITY = "vmess.security"
    const val VMESS_NETWORK = "vmess.network"
    const val VMESS_TLS = "vmess.tls"
    const val VMESS_SNI = "vmess.sni"
    const val VMESS_HOST = "vmess.host"
    const val VMESS_PATH = "vmess.path"
    const val VMESS_HEADER_TYPE = "vmess.headerType"
    const val SS_METHOD = "ss.method"
    const val SS_PASSWORD = "ss.password"
    const val TROJAN_PASSWORD = "trojan.password"
    const val TROJAN_SNI = "trojan.sni"

    /** Flat key/value fields for persistence; empty/null/invalid transport -> empty map. */
    fun toFields(node: ProxyNode): Map<String, String> {
        val fields = when (node.protocol) {
            ProxyProtocol.Vmess -> node.transport?.vmess?.let(::vmessFields)
            ProxyProtocol.Vless -> node.transport?.vless?.let(::vlessFields)
            ProxyProtocol.Ss -> node.transport?.shadowsocks?.let(::shadowsocksFields)
            ProxyProtocol.Trojan -> node.transport?.trojan?.let(::trojanFields)
            else -> null
        } ?: return emptyMap()
        // Never persist a transport that would not decode back to the same usable shape.
        return if (fromFields(node.protocol, fields) == null) emptyMap() else fields
    }

    /** Rebuilds a transport from flat fields; null when the protocol/fields are incomplete/invalid. */
    fun fromFields(protocol: ProxyProtocol, fields: Map<String, String>): NodeTransport? {
        if (fields.isEmpty()) return null
        return when (protocol) {
            ProxyProtocol.Vmess -> {
                val uuid = required(fields, VMESS_UUID, MAX_UUID_CHARS) ?: return null
                val alterIdText = fields[VMESS_ALTER_ID]
                val alterId = if (alterIdText == null) 0 else alterIdText.trim().toIntOrNull() ?: return null
                if (alterId !in 0..65535) return null
                val security = (optionalField(fields, VMESS_SECURITY, MAX_SHORT_CHARS) ?: return null).ifEmpty { "auto" }
                val network = (optionalField(fields, VMESS_NETWORK, MAX_SHORT_CHARS) ?: return null).ifEmpty { "tcp" }
                if (network !in KERNEL_NETWORKS) return null
                val tls = optionalField(fields, VMESS_TLS, MAX_SHORT_CHARS) ?: return null
                val sniRaw = optionalField(fields, VMESS_SNI, MAX_SNI_CHARS) ?: return null
                val hostRaw = optionalField(fields, VMESS_HOST, MAX_HOST_CHARS) ?: return null
                val pathRaw = optionalField(fields, VMESS_PATH, MAX_PATH_CHARS) ?: return null
                val headerTypeRaw = optionalField(fields, VMESS_HEADER_TYPE, MAX_HEADER_CHARS) ?: return null
                NodeTransport(
                    vmess = VmessTransport(
                        uuid, alterId, security, network, tls,
                        sniRaw.ifEmpty { null }, hostRaw.ifEmpty { null },
                        pathRaw.ifEmpty { null }, headerTypeRaw.ifEmpty { null },
                    ),
                )
            }
            ProxyProtocol.Vless -> {
                val uuid = required(fields, "vless.uuid", 128) ?: return null
                fun field(key: String, max: Int = 255) = optionalField(fields, "vless.$key", max)
                val vless = VlessTransport(
                    uuid = uuid,
                    encryption = field("encryption", 64) ?: return null,
                    network = field("network", 16) ?: return null,
                    security = field("security", 16) ?: return null,
                    flow = field("flow", 64) ?: return null,
                    serverName = field("serverName")?.ifEmpty { null },
                    publicKey = field("publicKey", 256)?.ifEmpty { null },
                    shortId = field("shortId", 16)?.ifEmpty { null },
                    fingerprint = field("fingerprint", 64)?.ifEmpty { null },
                    spiderX = field("spiderX", 1024)?.ifEmpty { null },
                )
                // Optional malformed values must not disappear when loading a damaged cache.
                if (listOf("serverName" to 255, "publicKey" to 256, "shortId" to 16, "fingerprint" to 64, "spiderX" to 1024)
                        .any { (key, max) -> field(key, max) == null } || !ProxyKernelConfig.isSupportedVless(vless)) return null
                NodeTransport(vless = vless)
            }
            ProxyProtocol.Ss -> {
                val method = required(fields, SS_METHOD, MAX_METHOD_CHARS) ?: return null
                val password = required(fields, SS_PASSWORD, MAX_CREDENTIAL_CHARS) ?: return null
                NodeTransport(shadowsocks = ShadowsocksTransport(method, password))
            }
            ProxyProtocol.Trojan -> {
                val password = required(fields, TROJAN_PASSWORD, MAX_CREDENTIAL_CHARS) ?: return null
                val sniRaw = optionalField(fields, TROJAN_SNI, MAX_SNI_CHARS) ?: return null
                NodeTransport(trojan = TrojanTransport(password, sniRaw.ifEmpty { null }))
            }
            else -> null
        }
    }

    private fun vmessFields(vmess: VmessTransport): Map<String, String> = linkedMapOf<String, String>().apply {
        put(VMESS_UUID, vmess.uuid)
        put(VMESS_ALTER_ID, vmess.alterId.toString())
        put(VMESS_SECURITY, vmess.security)
        put(VMESS_NETWORK, vmess.network)
        put(VMESS_TLS, vmess.tls)
        putIfNotEmpty(VMESS_SNI, vmess.sni)
        putIfNotEmpty(VMESS_HOST, vmess.host)
        putIfNotEmpty(VMESS_PATH, vmess.path)
        putIfNotEmpty(VMESS_HEADER_TYPE, vmess.headerType)
    }

    private fun vlessFields(value: VlessTransport): Map<String, String> = linkedMapOf<String, String>().apply {
        put("vless.uuid", value.uuid)
        put("vless.encryption", value.encryption)
        put("vless.network", value.network)
        put("vless.security", value.security)
        put("vless.flow", value.flow)
        putIfNotEmpty("vless.serverName", value.serverName)
        putIfNotEmpty("vless.publicKey", value.publicKey)
        putIfNotEmpty("vless.shortId", value.shortId)
        putIfNotEmpty("vless.fingerprint", value.fingerprint)
        putIfNotEmpty("vless.spiderX", value.spiderX)
    }

    private fun shadowsocksFields(shadowsocks: ShadowsocksTransport): Map<String, String> = linkedMapOf(
        SS_METHOD to shadowsocks.method,
        SS_PASSWORD to shadowsocks.password,
    )

    private fun trojanFields(trojan: TrojanTransport): Map<String, String> = linkedMapOf<String, String>().apply {
        put(TROJAN_PASSWORD, trojan.password)
        putIfNotEmpty(TROJAN_SNI, trojan.sni)
    }

    private fun MutableMap<String, String>.putIfNotEmpty(key: String, value: String?) {
        if (!value.isNullOrEmpty()) put(key, value)
    }

    /** Required value: missing/blank/over-long/control character -> null. */
    private fun required(fields: Map<String, String>, key: String, maxLength: Int): String? {
        val value = fields[key] ?: return null
        if (value.isEmpty() || value.length > maxLength || value.any(Char::isISOControl)) return null
        return value
    }

    /**
     * Optional value: missing key -> "" (absent), present blank -> "", present but over-long or
     * containing a control character -> null (caller fails the whole transport closed).
     */
    private fun optionalField(fields: Map<String, String>, key: String, maxLength: Int): String? {
        if (!fields.containsKey(key)) return ""
        val value = fields[key] ?: return ""
        if (value.length > maxLength || value.any(Char::isISOControl)) return null
        return value
    }

    private val KERNEL_NETWORKS = setOf("tcp", "ws", "grpc")
    private const val MAX_UUID_CHARS = 255
    private const val MAX_CREDENTIAL_CHARS = 255
    private const val MAX_METHOD_CHARS = 64
    private const val MAX_SNI_CHARS = 255
    private const val MAX_HOST_CHARS = 255
    private const val MAX_PATH_CHARS = 1024
    private const val MAX_HEADER_CHARS = 64
    private const val MAX_SHORT_CHARS = 16
}

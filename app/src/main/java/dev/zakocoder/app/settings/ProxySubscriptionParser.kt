package dev.zakocoder.app.settings

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Decodes the three subscription shapes this app accepts: a plain list of proxy links, a
 * Base64-encoded list of the same links, and the Clash/Mihomo YAML `proxies:` subset.
 *
 * Contract and limits:
 * - input is rejected (returns null) above 2 MiB, when no node can be decoded, or for arbitrary
 *   HTML/junk. `max 512 nodes` is enforced by truncating the decoded list in input order, which
 *   keeps ids stable and avoids pathological UI lists;
 * - HTTP/SOCKS5 produce direct proxy settings; VMess/Shadowsocks/Trojan and VLESS TCP with
 *   supported security settings run through the optional kernel. Unsupported combinations stay
 *   visible without being silently downgraded; unknown schemes are ignored;
 * - node ids are a SHA-256 of the decoded identity plus its position, so re-fetching the same
 *   subscription reproduces ids across restarts while duplicate entries stay distinguishable;
 * - direct credentials and kernel handshake parameters are retained only for their matching
 *   protocol, redacted from diagnostic string representations, and never emitted in errors.
 *
 * This class is intentionally plain JVM (no Android imports, no org.json) so parsing is fully
 * unit-testable.
 */
class ProxySubscriptionParser {
    /** Decodes one link. Returns null for blank/unknown/malformed authority links. */
    fun parseLink(link: String, sourceUrl: String = ""): ProxyNode? {
        val raw = link.trim()
        if (raw.isEmpty() || raw.length > MAX_LINK_CHARS) return null
        if (raw.any(Char::isISOControl)) return null
        val marker = raw.indexOf("://")
        if (marker <= 0) return null
        val scheme = raw.substring(0, marker).lowercase()
        val rest = raw.substring(marker + 3)
        val fragmentIndex = rest.indexOf('#')
        val body = if (fragmentIndex >= 0) rest.substring(0, fragmentIndex) else rest
        val rawName = if (fragmentIndex >= 0) rest.substring(fragmentIndex + 1) else ""
        if (body.isEmpty()) return null
        return when (scheme) {
            "http" -> authorityNode(body, rawName, ProxyProtocol.Http)
            // The app's HTTP proxy path is plaintext-only; an https:// proxy link would otherwise be
            // silently downgraded to http:// when written back to settings, so list it as unsupported.
            "https" -> authorityUnsupported(body, rawName)
            "socks", "socks5" -> authorityNode(body, rawName, ProxyProtocol.Socks5)
            "vmess" -> vmessNode(body, rawName)
            "vless" -> vlessNode(body, rawName)
            "trojan" -> trojanNode(body, rawName)
            "ss" -> ssNode(body, rawName)
            // There is no SSR enum member; SSR is a different wire protocol from SS, so label it Other.
            "ssr" -> unsupportedNode(body, rawName, ProxyProtocol.Other)
            "socks4", "socks4a" -> authorityUnsupported(body, rawName)
            "hysteria", "hysteria2", "hy2" -> unsupportedNode(body, rawName, ProxyProtocol.Hysteria)
            "tuic" -> unsupportedNode(body, rawName, ProxyProtocol.Tuic)
            else -> null
        }
    }

    /** Decodes a fetched document. Returns null when the content is not a usable subscription. */
    fun parse(content: String, sourceUrl: String = ""): ProxySubscription? {
        if (content.isEmpty()) return null
        if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_CONTENT_BYTES) return null
        val text = content.trim().trimStart('\uFEFF').trim()
        if (text.isEmpty()) return null
        if (looksLikeClash(text)) return markNotices(parseClash(text, sourceUrl))
        if (text.contains("://")) return markNotices(parsePlainLinks(text, sourceUrl, SubscriptionFormat.PlainLinks))
        val decoded = decodeBase64Text(text) ?: return null
        if (looksLikeClash(decoded)) return markNotices(parseClash(decoded, sourceUrl))
        if (!decoded.contains("://")) return null
        return markNotices(parsePlainLinks(decoded, sourceUrl, SubscriptionFormat.Base64Links))
    }

    private fun markNotices(subscription: ProxySubscription?): ProxySubscription? = subscription?.copy(
        nodes = subscription.nodes.map { it.copy(subscriptionPlaceholder = isSubscriptionPlaceholder(it)) },
    )

    /** Classification entry point used by [ProxyCatalog]: it takes already-fetched content. */
    fun probe(link: String, content: String): ProxyProbe = classify(parse(content, link))

    /**
     * Airport = 2+ decoded nodes with at least one usable node; SingleNode = exactly one decoded
     * node and it is usable; UnsupportedOnly = every decoded node is importable metadata only.
     * All-unsupported wins over the node count so the UI never offers a dead "save" action.
     */
    fun classify(subscription: ProxySubscription?): ProxyProbe {
        if (subscription == null || subscription.nodes.isEmpty()) return ProxyProbe.NotSubscription
        if (subscription.nodes.none { it.supported }) return ProxyProbe.UnsupportedOnly(subscription.nodes)
        if (subscription.nodes.size == 1) return ProxyProbe.SingleNode(subscription.nodes.first())
        return ProxyProbe.Airport(subscription)
    }

    // ---- plain / base64 link lists --------------------------------------------------------

    private fun parsePlainLinks(text: String, sourceUrl: String, format: SubscriptionFormat): ProxySubscription? {
        val nodes = ArrayList<ProxyNode>(16)
        for (line in text.lineSequence()) {
            if (nodes.size >= MAX_NODES) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // A name may contain spaces ("#US 01"), so split on whitespace only when the same
            // line obviously carries several links.
            val tokens = if (countOccurrences(trimmed, "://") > 1) trimmed.split(WHITESPACE) else listOf(trimmed)
            for (token in tokens) {
                if (nodes.size >= MAX_NODES) break
                val node = parseLink(token, sourceUrl) ?: continue
                nodes += node.copy(id = subscriptionNodeId(node.protocol, node.host, node.port, node.username, node.name, nodes.size),
                    subscriptionPlaceholder = isSubscriptionPlaceholder(node))
            }
        }
        if (nodes.isEmpty()) return null
        return ProxySubscription(sourceUrl, 0L, nodes, format)
    }

    private fun countOccurrences(text: String, value: String): Int {
        var count = 0
        var index = text.indexOf(value)
        while (index >= 0) {
            count++
            index = text.indexOf(value, index + value.length)
        }
        return count
    }

    // ---- link decoding --------------------------------------------------------------------

    private fun authorityNode(body: String, rawName: String, protocol: ProxyProtocol): ProxyNode? {
        val authority = body.substringBefore('/').substringBefore('?')
        if (authority.isEmpty()) return null
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else null
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val (host, port) = parseHostPort(hostPort) ?: return null
        var username: String? = null
        var password: String? = null
        if (!userInfo.isNullOrEmpty()) {
            val colon = userInfo.indexOf(':')
            val decodedUser = decodeUserInfo(if (colon >= 0) userInfo.substring(0, colon) else userInfo) ?: return null
            if (decodedUser.isEmpty() || decodedUser.length > MAX_CREDENTIAL_CHARS) return null
            username = decodedUser
            if (colon >= 0) {
                val decodedPassword = decodeUserInfo(userInfo.substring(colon + 1)) ?: return null
                if (decodedPassword.length > MAX_CREDENTIAL_CHARS) return null
                password = decodedPassword
            }
        }
        val name = cleanName(rawName).ifEmpty { "${formatHost(host)}:$port" }
        return ProxyNode(
            id = subscriptionNodeId(protocol, host, port, username, name, 0),
            name = name, protocol = protocol, host = host, port = port, username = username, password = password,
        )
    }

    private fun authorityUnsupported(body: String, rawName: String): ProxyNode {
        val extracted = extractAuthorityServer(body)
        val host = stripHostBrackets(extracted?.host.orEmpty())
        val port = extracted?.port ?: 0
        val name = cleanName(rawName).ifEmpty {
            if (host.isEmpty()) "未命名节点" else "${formatHost(host)}:$port"
        }
        return ProxyNode(
            id = subscriptionNodeId(ProxyProtocol.Other, host, port, null, name, 0),
            name = name, protocol = ProxyProtocol.Other, host = host, port = port,
        )
    }

    private fun unsupportedNode(body: String, rawName: String, hint: ProxyProtocol): ProxyNode {
        val extracted = if (hint == ProxyProtocol.Other) extractSsr(body) else extractAuthorityServer(body)
        // "解析失败也计为 Other 不支持节点": keep the entry visible, but do not pretend a protocol.
        val protocol = if (extracted == null) ProxyProtocol.Other else hint
        val host = extracted?.host.orEmpty()
        val port = extracted?.port ?: 0
        val fallbackName = when {
            host.isEmpty() -> "未命名节点"
            else -> "${formatHost(host)}:$port"
        }
        val name = cleanName(rawName).ifEmpty { cleanName(extracted?.name.orEmpty()) }.ifEmpty { fallbackName }
        return ProxyNode(
            id = subscriptionNodeId(protocol, host, port, null, name, 0),
            name = name, protocol = protocol, host = stripHostBrackets(host), port = port,
        )
    }

    /** Fallback for a kernel protocol whose endpoint/credentials cannot be decoded at all. */
    private fun kernelFallback(rawName: String, hint: ProxyProtocol): ProxyNode {
        val name = cleanName(rawName).ifEmpty { "未命名节点" }
        // The entry stays visible as unsupported metadata; never invent a host/port for the kernel.
        return ProxyNode(
            id = subscriptionNodeId(ProxyProtocol.Other, "", 0, null, name, 0),
            name = name, protocol = ProxyProtocol.Other, host = "", port = 0,
        )
    }

    // ---- kernel protocols (VMess / Shadowsocks / Trojan) -----------------------------------

    private data class VmessFields(
        val server: String?, val port: Int?, val uuid: String, val alterId: Int, val security: String,
        val network: String, val tls: String, val sni: String?, val host: String?, val path: String?,
        val headerType: String?, val name: String?,
    )

    private fun vmessNode(body: String, rawName: String): ProxyNode {
        val decoded = decodeBase64Text(body)
        val fields = decoded?.let(::vmessFields)
        val server = fields?.server?.takeIf(::validHost)
        val port = fields?.port?.takeIf { it in 1..65535 }
        if (fields == null || server == null || port == null) return kernelFallback(rawName, ProxyProtocol.Vmess)
        val name = cleanName(rawName).ifEmpty { cleanDisplayName(fields.name.orEmpty()) }.ifEmpty { "${formatHost(server)}:$port" }
        val transport = if (fields.uuid.isNotEmpty() && fields.network in KERNEL_NETWORKS) {
            NodeTransport(
                vmess = VmessTransport(
                    uuid = fields.uuid, alterId = fields.alterId, security = fields.security,
                    network = fields.network, tls = fields.tls, sni = fields.sni, host = fields.host,
                    path = fields.path, headerType = fields.headerType,
                ),
            )
        } else null
        return ProxyNode(
            id = subscriptionNodeId(ProxyProtocol.Vmess, server, port, null, name, 0),
            name = name, protocol = ProxyProtocol.Vmess, host = server, port = port, transport = transport,
        )
    }

    private fun vmessFields(json: String): VmessFields? {
        var validEscapes = true
        fun field(pattern: Regex): String? = pattern.find(json)?.groupValues?.get(1)?.let { raw ->
            unescapeJson(raw).also { if (it == null) validEscapes = false }
        }
        val alterId = field(vmessAlterId)?.toIntOrNull() ?: 0
        val network = field(vmessNetwork)?.trim()?.lowercase().orEmpty().ifEmpty { "tcp" }
        val tls = field(vmessTls)?.trim()?.lowercase().orEmpty()
        val security = field(vmessSecurity)?.trim()?.lowercase().orEmpty().ifEmpty { "auto" }
        return VmessFields(
            server = field(vmessServer),
            port = field(vmessPort)?.toIntOrNull(),
            uuid = field(vmessId).orEmpty(),
            alterId = alterId.coerceIn(0, 65535),
            security = security,
            network = network,
            tls = tls,
            sni = field(vmessSni)?.ifEmpty { null },
            host = field(vmessHost)?.ifEmpty { null },
            path = field(vmessPath)?.ifEmpty { null },
            headerType = field(vmessType)?.ifEmpty { null },
            name = field(vmessName),
        ).takeIf { validEscapes }
    }

    private fun ssNode(body: String, rawName: String): ProxyNode {
        val at = body.lastIndexOf('@')
        var hostPort: Pair<String, Int>? = null
        var credentialText: String? = null
        if (at >= 0) {
            hostPort = parseHostPort(body.substring(at + 1).substringBefore('/').substringBefore('?'))
            credentialText = body.substring(0, at)
        } else {
            val decoded = decodeBase64Text(body)
            if (decoded != null) {
                val decodedAt = decoded.lastIndexOf('@')
                if (decodedAt >= 0) {
                    hostPort = parseHostPort(decoded.substring(decodedAt + 1).substringBefore('/').substringBefore('?'))
                    credentialText = decoded.substring(0, decodedAt)
                } else {
                    hostPort = parseHostPort(decoded.substringBefore('/').substringBefore('?'))
                }
            }
        }
        val (host, port) = hostPort ?: return kernelFallback(rawName, ProxyProtocol.Ss)
        // SIP002 user info is normally Base64(method:password); percent-encoded plain text is also seen.
        val credentials = credentialText?.let { decodeBase64Text(it) ?: decodeUserInfo(it) }
        val colon = credentials?.indexOf(':') ?: -1
        val method = if (colon > 0) credentials!!.substring(0, colon).trim() else ""
        val password = if (colon >= 0) credentials!!.substring(colon + 1) else ""
        // Xray-core Shadowsocks plugins (v2ray-plugin, obfs, ...) are not generated by this app.
        val usesPlugin = body.contains("plugin=", ignoreCase = true)
        val transport = if (!usesPlugin && method.isNotEmpty() && password.isNotEmpty()) {
            NodeTransport(shadowsocks = ShadowsocksTransport(method, password))
        } else null
        val name = cleanName(rawName).ifEmpty { "${formatHost(host)}:$port" }
        return ProxyNode(
            id = subscriptionNodeId(ProxyProtocol.Ss, host, port, null, name, 0),
            name = name, protocol = ProxyProtocol.Ss, host = host, port = port, transport = transport,
        )
    }

    private fun vlessNode(body: String, rawName: String): ProxyNode {
        val authority = body.substringBefore('?').substringBefore('/')
        val at = authority.lastIndexOf('@')
        val (host, port) = parseHostPort(authority.substring(at + 1)) ?: return kernelFallback(rawName, ProxyProtocol.Vless)
        val name = cleanName(rawName).ifEmpty { "${formatHost(host)}:$port" }
        val query = body.substringAfter('?', "")
        val fields = LinkedHashMap<String, String>()
        var valid = at > 0 && body.substringBefore('?').substringAfter('/', "").isEmpty()
        for (pair in query.split('&').filter { it.isNotEmpty() }) {
            val key = decodeUserInfo(pair.substringBefore('='))?.lowercase()
            val value = decodeUserInfo(pair.substringAfter('=', ""))
            if (key == null || value == null || fields.put(key, value) != null || key !in VLESS_LINK_KEYS) valid = false
        }
        if (fields.containsKey("sni") && fields.containsKey("servername") && fields["sni"] != fields["servername"]) valid = false
        val uuid = decodeUserInfo(authority.substringBefore('@')).orEmpty()
        val network = fields["type"].orEmpty().lowercase().ifEmpty { "tcp" }
        if (fields["headertype"].orEmpty().lowercase() !in setOf("", "none")) valid = false
        // Some exporters emit defaults for every possible transport. Accept only values that
        // have no effect on raw TCP; never discard a real path/host or disable TLS verification.
        if (listOf("host", "path", "servicename").any { !fields[it].isNullOrEmpty() }) valid = false
        if (fields["quicsecurity"].orEmpty().lowercase() !in setOf("", "none")) valid = false
        if (fields["insecure"].orEmpty().lowercase() !in setOf("", "0", "false")) valid = false
        val transport = if (valid) NodeTransport(vless = VlessTransport(
            uuid = uuid,
            encryption = fields["encryption"].orEmpty().ifEmpty { "none" },
            network = network,
            security = fields["security"].orEmpty().lowercase().ifEmpty { "none" },
            flow = fields["flow"].orEmpty(),
            serverName = fields["sni"] ?: fields["servername"],
            publicKey = fields["pbk"], shortId = fields["sid"],
            fingerprint = fields["fp"], spiderX = fields["spx"],
        )) else null
        return ProxyNode(subscriptionNodeId(ProxyProtocol.Vless, host, port, null, name, 0), name,
            ProxyProtocol.Vless, host, port, transport = transport)
    }

    private fun trojanNode(body: String, rawName: String): ProxyNode {
        val queryIndex = body.indexOf('?')
        val authority = if (queryIndex >= 0) body.substring(0, queryIndex) else body
        val query = if (queryIndex >= 0) body.substring(queryIndex + 1) else ""
        val at = authority.lastIndexOf('@')
        val passwordText = if (at >= 0) authority.substring(0, at) else null
        val hostPortText = if (at >= 0) authority.substring(at + 1) else authority
        val (host, port) = parseHostPort(hostPortText.substringBefore('/')) ?: return kernelFallback(rawName, ProxyProtocol.Trojan)
        val password = passwordText?.let(::decodeUserInfo)?.takeIf { it.isNotEmpty() }
        val sni = queryParameter(query, "sni") ?: queryParameter(query, "peer")
        // Trojan over ws/grpc needs streamSettings the config generator does not produce.
        val transportType = queryParameter(query, "type")?.trim()?.lowercase().orEmpty()
        val streamSupported = transportType.isEmpty() || transportType == "tcp"
        val transport = password?.takeIf { streamSupported }?.let { NodeTransport(trojan = TrojanTransport(it, sni)) }
        val name = cleanName(rawName).ifEmpty { "${formatHost(host)}:$port" }
        return ProxyNode(
            id = subscriptionNodeId(ProxyProtocol.Trojan, host, port, null, name, 0),
            name = name, protocol = ProxyProtocol.Trojan, host = host, port = port, transport = transport,
        )
    }

    private fun queryParameter(query: String, key: String): String? {
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val name = if (separator >= 0) pair.substring(0, separator) else pair
            if (!name.equals(key, ignoreCase = true)) continue
            val value = if (separator >= 0) decodeUserInfo(pair.substring(separator + 1)) else ""
            return value?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private data class Extracted(val host: String, val port: Int, val name: String = "")

    private fun extractAuthorityServer(body: String): Extracted? {
        // Third-party links usually carry a path and query after the authority; keep only the
        // authority so the unsupported row can still show host/port/name.
        val withoutSuffix = body.substringBefore('?').substringBefore('/')
        val at = withoutSuffix.lastIndexOf('@')
        val authority = if (at >= 0) withoutSuffix.substring(at + 1) else withoutSuffix
        val (host, port) = parseHostPort(authority) ?: return null
        return Extracted(host, port)
    }

    private fun extractSsr(body: String): Extracted? {
        val decoded = decodeBase64Text(body) ?: return null
        val parts = decoded.split(':')
        if (parts.size < 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (port !in 1..65535 || !validHost(host)) return null
        return Extracted(host, port)
    }

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val host: String
        val portText: String
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) return null
            host = text.substring(1, end)
            val remainder = text.substring(end + 1)
            if (!remainder.startsWith(":")) return null
            portText = remainder.substring(1)
        } else {
            val colon = text.lastIndexOf(':')
            if (colon <= 0 || colon == text.length - 1) return null
            host = text.substring(0, colon)
            portText = text.substring(colon + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535 || !validHost(host)) return null
        return stripHostBrackets(host) to port
    }

    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_CHARS) return false
        return host.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '?' || it == '#' || it == '@' }
    }

    // ---- Clash/Mihomo YAML subset ---------------------------------------------------------

    private fun looksLikeClash(text: String): Boolean =
        text.lineSequence().any { line -> CLASH_PROXIES_KEY.matches(line.trim()) }

    private fun parseClash(text: String, sourceUrl: String): ProxySubscription? {
        val lines = text.lines()
        val start = lines.indexOfFirst { CLASH_PROXIES_KEY.matches(it.trim()) }
        if (start < 0) return null
        val keyIndent = indentOf(lines[start])
        val entries = ArrayList<Map<String, String>>()
        var current: MutableMap<String, String>? = null
        var nestedKey: String? = null
        var nestedIndent = -1
        fun flush() {
            current?.let { entries += it }
            current = null
            nestedKey = null
        }
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                index++
                continue
            }
            val indent = indentOf(line)
            if (indent <= keyIndent && !trimmed.startsWith("-")) break
            if (trimmed.startsWith("-")) {
                flush()
                var content = stripYamlComment(trimmed.removePrefix("-").trim())
                if (content.startsWith("{")) {
                    // Inline mapping may be wrapped across lines; consume until braces balance.
                    while (braceDepth(content) > 0 && index + 1 < lines.size) {
                        index++
                        content += " " + stripYamlComment(lines[index].trim())
                    }
                    parseInlineEntry(content)?.let { entries += it }
                } else {
                    current = LinkedHashMap()
                    current!!.putAll(parseFieldLine(content))
                }
            } else {
                val parsed = parseFieldLine(stripYamlComment(trimmed))
                if (nestedKey != null && indent > nestedIndent) {
                    parsed.forEach { (key, value) -> current?.put("$nestedKey.$key", value) }
                } else {
                    nestedKey = null
                    current?.putAll(parsed)
                    if (parsed.size == 1 && parsed.values.first().isEmpty()) {
                        nestedKey = parsed.keys.first()
                        nestedIndent = indent
                    }
                }
            }
            index++
        }
        flush()
        if (entries.isEmpty()) return null
        val nodes = ArrayList<ProxyNode>()
        for (fields in entries) {
            if (nodes.size >= MAX_NODES) break
            clashNode(fields, nodes.size)?.let { nodes += it }
        }
        if (nodes.isEmpty()) return null
        return ProxySubscription(sourceUrl, 0L, nodes, SubscriptionFormat.ClashYaml)
    }

    private fun clashNode(fields: Map<String, String>, index: Int): ProxyNode? {
        var protocol = clashProtocol(fields["type"].orEmpty())
        // Clash can express TLS-to-proxy as `type: http, tls: true`; the app only speaks plaintext
        // HTTP proxies, so it must be listed as unsupported instead of downgraded to http://.
        val clashTls = fields["tls"]?.trim()?.lowercase().orEmpty()
        if (protocol == ProxyProtocol.Http && (clashTls == "true" || clashTls == "tls")) {
            protocol = ProxyProtocol.Other
        }
        val server = stripHostBrackets(fields["server"].orEmpty().trim())
        if (!validHost(server)) return null
        val port = fields["port"]?.trim()?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val name = cleanDisplayName(fields["name"].orEmpty())
            .ifEmpty { "${formatHost(server)}:$port" }
        val carriesCredentials = protocol == ProxyProtocol.Http || protocol == ProxyProtocol.Socks5
        val username = if (carriesCredentials) fields["username"]?.trim()?.take(MAX_CREDENTIAL_CHARS)?.ifEmpty { null } else null
        val password = if (carriesCredentials) fields["password"]?.trim()?.take(MAX_CREDENTIAL_CHARS)?.ifEmpty { null } else null
        return ProxyNode(
            id = subscriptionNodeId(protocol, server, port, username, name, index),
            name = name, protocol = protocol, host = server, port = port, username = username, password = password,
            transport = clashTransport(protocol, fields),
        )
    }

    /** Extracts the same kernel transport parameters the plain-link parsers recover. */
    private fun clashTransport(protocol: ProxyProtocol, fields: Map<String, String>): NodeTransport? {
        // Clash nests ws/grpc/h2/reality/plugin options; the flat parser above cannot represent
        // them, so fail closed instead of marking such nodes usable when their config would fail.
        if (protocol == ProxyProtocol.Vless) return clashVlessTransport(fields)
        if (fields.keys.any { it.lowercase() in CLASH_UNSUPPORTED_OPTIONS }) return null
        return clashTransportFlat(protocol, fields)
    }

    private fun clashVlessTransport(fields: Map<String, String>): NodeTransport? {
        // Only raw TCP is represented here; nested options for other transports stay unsupported.
        if (fields.keys.any { it.substringBefore('.') in CLASH_UNSUPPORTED_OPTIONS - "reality-opts" }) return null
        if (fields["skip-cert-verify"].equals("true", ignoreCase = true) || fields.containsKey("alpn") || fields.containsKey("packet-encoding")) return null
        val reality = linkedMapOf<String, String>()
        fields["reality-opts"]?.takeIf { it.isNotEmpty() }?.let {
            if (!it.startsWith('{') || !it.endsWith('}')) return null
            reality.putAll(parseFieldLine(it.substring(1, it.length - 1)))
        }
        fields.filterKeys { it.startsWith("reality-opts.") }.forEach { (key, value) -> reality[key.substringAfter('.')] = value }
        if (reality.keys.any { it !in setOf("public-key", "short-id", "spider-x") }) return null
        val tls = normClashTls(fields["tls"]) ?: return null
        val security = if (fields.containsKey("reality-opts")) {
            if (tls != "tls") return null
            "reality"
        } else if (tls == "tls") "tls" else "none"
        return NodeTransport(vless = VlessTransport(
            uuid = fields["uuid"].orEmpty(),
            encryption = fields["encryption"].orEmpty().ifEmpty { "none" },
            network = fields["network"].orEmpty().lowercase().ifEmpty { "tcp" },
            security = security, flow = fields["flow"].orEmpty(),
            serverName = fields["servername"] ?: fields["sni"],
            publicKey = reality["public-key"], shortId = reality["short-id"],
            fingerprint = fields["client-fingerprint"], spiderX = reality["spider-x"],
        ))
    }

    private fun clashTransportFlat(protocol: ProxyProtocol, fields: Map<String, String>): NodeTransport? {
        return when (protocol) {
        ProxyProtocol.Vmess -> {
            val uuid = fields["uuid"]?.trim().orEmpty()
            val network = fields["network"]?.trim()?.lowercase().orEmpty().ifEmpty { "tcp" }
            if (uuid.isEmpty() || network !in KERNEL_NETWORKS) {
                null
            } else {
                NodeTransport(
                    vmess = VmessTransport(
                        uuid = uuid,
                        alterId = (fields["alterid"]?.trim()?.toIntOrNull() ?: 0).coerceIn(0, 65535),
                        security = fields["cipher"]?.trim()?.lowercase().orEmpty()
                            .ifEmpty { fields["security"]?.trim()?.lowercase().orEmpty().ifEmpty { "auto" } },
                        network = network,
                        // Clash writes TLS as a boolean; ProxyKernelConfig only accepts ""/none/tls.
                        tls = normClashTls(fields["tls"]) ?: return null,
                        sni = fields["servername"]?.trim()?.ifEmpty { null } ?: fields["sni"]?.trim()?.ifEmpty { null },
                        host = fields["host"]?.trim()?.ifEmpty { null },
                        path = fields["path"]?.trim()?.ifEmpty { null },
                        // In Clash, the top-level `type` is the proxy type (vmess), not tcp header
                        // obfuscation; leave headerType empty so normal TCP VMess stays usable.
                        headerType = null,
                    ),
                )
            }
        }
        ProxyProtocol.Ss -> {
            val method = fields["cipher"]?.trim().orEmpty().ifEmpty { fields["method"]?.trim().orEmpty() }
            val password = fields["password"]?.trim().orEmpty()
            if (method.isEmpty() || password.isEmpty()) null else NodeTransport(shadowsocks = ShadowsocksTransport(method, password))
        }
        ProxyProtocol.Trojan -> {
            val network = fields["network"]?.trim()?.lowercase().orEmpty()
            val password = fields["password"]?.trim().orEmpty()
            if (password.isEmpty() || (network.isNotEmpty() && network != "tcp")) {
                null
            } else {
                NodeTransport(
                    trojan = TrojanTransport(
                        password = password,
                        sni = fields["sni"]?.trim()?.ifEmpty { null } ?: fields["servername"]?.trim()?.ifEmpty { null },
                    ),
                )
            }
        }
        else -> null
        }
    }

    /** Clash TLS booleans to the config generator's vocabulary; null for unsupported modes. */
    private fun normClashTls(raw: String?): String? = when (raw?.trim()?.lowercase().orEmpty()) {
        "", "false", "none" -> ""
        "true", "tls" -> "tls"
        else -> null
    }

    private fun clashProtocol(type: String): ProxyProtocol = when (type.trim().lowercase()) {
        "http" -> ProxyProtocol.Http
        // https proxy traffic needs a TLS tunnel to the proxy itself, which this app does not implement.
        "https" -> ProxyProtocol.Other
        "socks", "socks5", "socks5h" -> ProxyProtocol.Socks5
        "vmess" -> ProxyProtocol.Vmess
        "ss" -> ProxyProtocol.Ss
        "trojan" -> ProxyProtocol.Trojan
        "vless" -> ProxyProtocol.Vless
        "hysteria", "hysteria2", "hy2" -> ProxyProtocol.Hysteria
        "tuic" -> ProxyProtocol.Tuic
        else -> ProxyProtocol.Other
    }

    private fun parseInlineEntry(text: String): Map<String, String>? {
        val open = text.indexOf('{')
        val close = text.lastIndexOf('}')
        if (open < 0 || close <= open) return null
        return parseFieldLine(stripYamlComment(text.substring(open + 1, close)))
    }

    private fun parseFieldLine(content: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (field in splitTopLevel(content)) {
            val colon = topLevelColon(field) ?: continue
            val key = field.substring(0, colon).trim().lowercase()
            if (key.isEmpty()) continue
            fields[key] = unquoteYaml(field.substring(colon + 1).trim())
        }
        return fields
    }

    private fun splitTopLevel(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in text.indices) {
            val character = text[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (character == '\\' && quote == '"') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    result += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += text.substring(start)
        return result
    }

    private fun topLevelColon(text: String): Int? {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in text.indices) {
            val character = text[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (character == '\\' && quote == '"') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                ':' -> if (depth == 0) return index
            }
        }
        return null
    }

    private fun stripYamlComment(text: String): String {
        var quote: Char? = null
        var escaped = false
        for (index in text.indices) {
            val character = text[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (character == '\\' && quote == '"') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '#' -> if (index == 0 || text[index - 1].isWhitespace()) return text.substring(0, index)
            }
        }
        return text.trim()
    }

    private fun unquoteYaml(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') return unescapeDoubleQuoted(value.substring(1, value.length - 1))
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') return value.substring(1, value.length - 1).replace("''", "'")
        return value
    }

    private fun unescapeDoubleQuoted(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index + 1 >= value.length) {
                result.append(character)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                'n' -> result.append('\n')
                't' -> result.append('\t')
                'r' -> result.append('\r')
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'u' -> {
                    val hex = if (index + 5 < value.length) value.substring(index + 2, index + 6) else ""
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        result.append(code.toChar())
                        index += 4
                    } else {
                        result.append(escaped)
                    }
                }
                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private fun braceDepth(text: String): Int {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (character in text) {
            if (quote != null) {
                if (escaped) escaped = false
                else if (character == '\\' && quote == '"') escaped = true
                else if (character == quote) quote = null
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{' -> depth++
                '}' -> depth--
            }
        }
        return depth
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

    // ---- names, ids, base64 ---------------------------------------------------------------

    private fun cleanName(raw: String): String {
        val decoded = decodeUserInfo(raw) ?: raw
        return cleanDisplayName(decoded)
    }

    // JSON strings are already decoded; a literal percent sequence in ps is not URL encoding.
    private fun cleanDisplayName(value: String): String {
        val cleaned = value.filter { !it.isISOControl() }.trim()
        var end = minOf(cleaned.length, MAX_NAME_CHARS)
        if (end > 0 && end < cleaned.length && cleaned[end - 1].isHighSurrogate() && cleaned[end].isLowSurrogate()) end--
        return cleaned.substring(0, end)
    }

    /** Decode one JSON string payload once; malformed escapes must not become usable transport. */
    private fun unescapeJson(value: String): String? {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.code < 0x20) return null
            if (character != '\\') {
                result.append(character)
                continue
            }
            if (index >= value.length) return null
            when (value[index++]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (index + 4 > value.length) return null
                    val hex = value.substring(index, index + 4)
                    if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                    result.append(hex.toInt(16).toChar())
                    index += 4
                }
                else -> return null
            }
        }
        // JSON unicode pairs represent one supplementary code point; reject isolated surrogates.
        index = 0
        while (index < result.length) {
            val character = result[index++]
            if (character.isHighSurrogate()) {
                if (index >= result.length || !result[index++].isLowSurrogate()) return null
            } else if (character.isLowSurrogate()) return null
        }
        return result.toString()
    }

    private fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot { it.isWhitespace() }
        if (compact.length < 8) return null
        if (compact.any { !BASE64_ALPHABET.contains(it) }) return null
        val bytes = decodeBase64Bytes(compact) ?: return null
        return decodeUtf8(bytes)
    }

    private fun decodeBase64Bytes(value: String): ByteArray? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        val standard = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
        if (standard != null) return standard
        return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private companion object {
        val CLASH_UNSUPPORTED_OPTIONS = setOf(
            "ws-opts", "grpc-opts", "http-opts", "h2-opts", "reality-opts", "plugin-opts", "plugin",
        )

        const val MAX_CONTENT_BYTES = 2 * 1024 * 1024
        const val MAX_NODES = 512
        const val MAX_NAME_CHARS = 80
        const val MAX_HOST_CHARS = 255
        const val MAX_CREDENTIAL_CHARS = 255
        const val MAX_LINK_CHARS = 16384
        const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/-_="
        val WHITESPACE = Regex("\\s+")
        val CLASH_PROXIES_KEY = Regex("(?i)^proxies\\s*:\\s*(#.*)?$")
        val VLESS_LINK_KEYS = setOf("encryption", "type", "security", "flow", "sni", "servername", "pbk", "sid", "fp", "spx", "headertype", "host", "path", "servicename", "quicsecurity", "insecure")
        val KERNEL_NETWORKS = setOf("tcp", "ws", "grpc")

        val vmessServer = Regex("\"add\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessPort = Regex("\"port\"\\s*:\\s*\"?(\\d{1,5})\"?")
        val vmessName = Regex("\"ps\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessId = Regex("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessAlterId = Regex("\"aid\"\\s*:\\s*\"?(\\d{1,6})\"?")
        val vmessSecurity = Regex("\"scy\"\\s*:\\s*\"([^\"]*)\"")
        val vmessNetwork = Regex("\"net\"\\s*:\\s*\"([^\"]*)\"")
        val vmessTls = Regex("\"tls\"\\s*:\\s*\"([^\"]*)\"")
        val vmessSni = Regex("\"sni\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessHost = Regex("\"host\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessPath = Regex("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val vmessType = Regex("\"type\"\\s*:\\s*\"([^\"]*)\"")
    }
}

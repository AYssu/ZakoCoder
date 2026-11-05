package dev.zakocoder.app.settings

import org.junit.Assert.*
import org.junit.Test

class VlessSubscriptionTest {
    private val parser = ProxySubscriptionParser()
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private val link get() = "vless://$uuid@edge.example:443?encryption=none&type=tcp&security=reality&flow=xtls-rprx-vision&sni=www.example.com&pbk=$key&sid=0123&fp=chrome&spx=%2Ftest%3Fa%3D1#Test"

    @Test fun resolvedEndpointKeepsRealityIdentity() {
        val node = requireNotNull(parser.parseLink(link))
        val json = requireNotNull(ProxyKernelConfig.build(node, 10808, resolvedAddress = "192.0.2.20"))
        assertTrue(json.contains("\"address\":\"192.0.2.20\""))
        assertTrue(json.contains("\"serverName\":\"www.example.com\""))
        assertTrue(json.contains("\"publicKey\":\"$key\""))
        assertFalse(json.contains("\"address\":\"edge.example\""))
    }

    @Test fun resolvedEndpointKeepsDefaultTlsNameAndWebSocketHost() {
        val transports = listOf(
            ProxyProtocol.Trojan to NodeTransport(trojan = TrojanTransport("fixture", null)),
            ProxyProtocol.Vmess to NodeTransport(vmess = VmessTransport(uuid, 0, "auto", "ws", "tls", null, "ws.example", "/socket", null)),
            ProxyProtocol.Vless to NodeTransport(vless = VlessTransport(uuid, security = "tls")),
            ProxyProtocol.Vless to NodeTransport(vless = VlessTransport(uuid, network = "ws", security = "tls", host = "ws.example", path = "/socket")),
        )
        for ((protocol, transport) in transports) {
            val node = ProxyNode("fixture", "Fixture", protocol, "edge.example", 443, transport = transport)
            val json = requireNotNull(ProxyKernelConfig.build(node, 10808, resolvedAddress = "192.0.2.20"))
            assertTrue(json.contains("\"address\":\"192.0.2.20\""))
            assertTrue(json.contains("\"serverName\":\"edge.example\""))
            if (json.contains("\"network\":\"ws\"")) assertTrue(json.contains("ws.example"))
        }
    }

    @Test fun webSocketAndGrpcLinksKeepTransportSettings() {
        val wsLink = link
            .replace("type=tcp", "type=ws")
            .replace("security=reality", "security=tls")
            .replace("&flow=xtls-rprx-vision", "")
            .replace("&pbk=$key", "")
            .replace("&sid=0123", "")
            .replace("&spx=%2Ftest%3Fa%3D1", "")
            .replace("#Test", "&host=cdn.example.com&path=%2Fsocket#WS")
        val wsNode = requireNotNull(parser.parseLink(wsLink))
        assertTrue(wsNode.supported)
        assertEquals("ws", wsNode.transport?.vless?.network)
        assertEquals("cdn.example.com", wsNode.transport?.vless?.host)
        assertEquals("/socket", wsNode.transport?.vless?.path)
        val wsJson = requireNotNull(ProxyKernelConfig.build(wsNode, 10808))
        assertTrue(wsJson.contains("\"network\":\"ws\""))
        assertTrue(wsJson.contains("\"wsSettings\":{\"path\":\"/socket\",\"headers\":{\"Host\":\"cdn.example.com\"}}"))
        val wsFields = ProxyTransportCodec.toFields(wsNode)
        assertEquals(wsNode.transport, ProxyTransportCodec.fromFields(ProxyProtocol.Vless, wsFields))

        val grpcLink = link.replace("type=tcp", "type=grpc")
            .replace("&flow=xtls-rprx-vision", "&mode=gun&serviceName=grpc-service")
            .replace("#Test", "#gRPC")
        val grpcNode = requireNotNull(parser.parseLink(grpcLink))
        assertTrue(grpcNode.supported)
        assertEquals("grpc", grpcNode.transport?.vless?.network)
        assertEquals("grpc-service", grpcNode.transport?.vless?.serviceName)
        val grpcJson = requireNotNull(ProxyKernelConfig.build(grpcNode, 10808))
        assertTrue(grpcJson.contains("\"network\":\"grpc\""))
        assertTrue(grpcJson.contains("\"grpcSettings\":{\"serviceName\":\"grpc-service\"}"))
        val grpcFields = ProxyTransportCodec.toFields(grpcNode)
        assertEquals(grpcNode.transport, ProxyTransportCodec.fromFields(ProxyProtocol.Vless, grpcFields))
    }

    @Test fun webSocketRealityIsRejectedAtUrlClashCacheAndConfig() {
        val wsReality = link
            .replace("type=tcp", "type=ws")
            .replace("&flow=xtls-rprx-vision", "")
            .replace("#Test", "&host=cdn.example.com&path=%2Fsocket#WS")
        val node = requireNotNull(parser.parseLink(wsReality))
        assertFalse(node.supported)
        assertFalse(ProxyKernelConfig.isSupportedVless(requireNotNull(node.transport?.vless)))
        assertNull(ProxyKernelConfig.build(node, 10808))
        assertTrue(ProxyTransportCodec.toFields(node).isEmpty())

        val clash = "proxies:\n  - {name: WS, type: vless, server: edge.example, port: 443, uuid: $uuid, network: ws, tls: true, servername: www.example.com, client-fingerprint: chrome, ws-opts: {path: /socket}, reality-opts: {public-key: $key, short-id: '0123'}}"
        val clashNode = requireNotNull(parser.parse(clash)).nodes.single()
        assertFalse(clashNode.supported)
        assertNull(ProxyKernelConfig.build(clashNode, 10808))

        val cached = mapOf(
            "vless.uuid" to uuid,
            "vless.encryption" to "none",
            "vless.network" to "ws",
            "vless.security" to "reality",
            "vless.flow" to "",
            "vless.serverName" to "www.example.com",
            "vless.publicKey" to key,
            "vless.shortId" to "0123",
            "vless.fingerprint" to "chrome",
            "vless.host" to "cdn.example.com",
            "vless.path" to "/socket",
        )
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, cached))
    }

    @Test fun clashWebSocketAndGrpcOptionsArePreserved() {
        val ws = "proxies:\n  - {name: WS, type: vless, server: edge.example, port: 443, uuid: $uuid, network: ws, tls: true, servername: www.example.com, ws-opts: {path: /socket, headers: {Host: cdn.example.com}}}"
        val wsNode = requireNotNull(parser.parse(ws)).nodes.single()
        assertTrue(wsNode.supported)
        assertEquals(VlessTransport(uuid, network = "ws", security = "tls", serverName = "www.example.com",
            host = "cdn.example.com", path = "/socket"), wsNode.transport?.vless)

        val grpc = "proxies:\n  - {name: gRPC, type: vless, server: edge.example, port: 443, uuid: $uuid, network: grpc, tls: true, servername: www.example.com, grpc-opts: {grpc-service-name: grpc-service}}"
        val grpcNode = requireNotNull(parser.parse(grpc)).nodes.single()
        assertTrue(grpcNode.supported)
        assertEquals("grpc-service", grpcNode.transport?.vless?.serviceName)
        assertTrue(requireNotNull(ProxyKernelConfig.build(grpcNode, 10808)).contains("grpcSettings"))

        val block = """
            proxies:
              - name: WS block
                type: vless
                server: edge.example
                port: 443
                uuid: $uuid
                network: ws
                tls: true
                servername: www.example.com
                ws-opts:
                  path: /socket
                  headers:
                    Host: cdn.example.com
        """.trimIndent()
        assertEquals(wsNode.transport, requireNotNull(parser.parse(block)).nodes.single().transport)
    }

    @Test fun urlRealityVisionRetainsAllHandshakeFieldsAndGeneratesConfig() {
        val node = requireNotNull(parser.parseLink(link))
        assertTrue(node.supported)
        val v = requireNotNull(node.transport?.vless)
        assertEquals("/test?a=1", v.spiderX)
        assertEquals("0123", v.shortId)
        assertEquals("chrome", v.fingerprint)
        val json = requireNotNull(ProxyKernelConfig.build(node, 10808, "local-user", "local-password"))
        listOf("\"protocol\":\"vless\"", "\"encryption\":\"none\"", "\"flow\":\"xtls-rprx-vision\"",
            "\"publicKey\":\"$key\"", "\"shortId\":\"0123\"", "\"spiderX\":\"/test?a=1\"").forEach { assertTrue(it, json.contains(it)) }
        assertFalse(node.toString().contains(uuid))
        assertFalse(node.transport.toString().contains(key))
    }

    @Test fun unsupportedVariantsFailClosed() {
        listOf(
            link.replace("type=tcp", "type=ws"),
            link.replace("security=reality", "security=xtls"),
            link.replace("encryption=none", "encryption=unknown"),
            link.replace("xtls-rprx-vision", "xtls-rprx-origin"),
            link.replace("sid=0123", "sid=123"),
            link.replace("sid=0123", "sid=zz"),
            link.replace("pbk=$key", "pbk=not-a-key"),
            link.replace("fp=chrome", "fp=%0A"),
            "$link&unknown=value".replace("#Test", ""),
            link.replace("&spx=", "&alpn=h2&spx="),
        ).forEach { raw ->
            val node = requireNotNull(parser.parseLink(raw))
            assertFalse(raw.substringBefore('@'), node.supported)
            assertNull(ProxyKernelConfig.build(node, 10808))
        }
    }

    @Test fun clashBlockAndInlineRealityOptionsMatchUrl() {
        val block = """
            proxies:
              - name: Test
                type: vless
                server: edge.example
                port: 443
                uuid: $uuid
                network: tcp
                tls: true
                flow: xtls-rprx-vision
                servername: www.example.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: $key
                  short-id: '0123'
                  spider-x: /test?a=1
        """.trimIndent()
        val inline = "proxies:\n  - {name: Test, type: vless, server: edge.example, port: 443, uuid: $uuid, network: tcp, tls: true, flow: xtls-rprx-vision, servername: www.example.com, client-fingerprint: chrome, reality-opts: {public-key: $key, short-id: '0123', spider-x: /test?a=1}}"
        val expected = requireNotNull(parser.parseLink(link)).transport
        listOf(block, inline).forEach { yaml ->
            val node = requireNotNull(parser.parse(yaml)).nodes.single()
            assertTrue(node.supported)
            assertEquals(expected, node.transport)
        }
        val unsupported = block.replace("short-id: '0123'", "short-id: '0123'\n      mldsa65-verify: unsupported")
        assertFalse(requireNotNull(parser.parse(unsupported)).nodes.single().supported)
    }

    @Test fun flatCacheRoundTripPreservesVlessWithoutChangingOldSchema() {
        val node = requireNotNull(parser.parseLink(link))
        val fields = ProxyTransportCodec.toFields(node)
        assertEquals(node.transport, ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields + ("vless.spiderX" to "\n")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, emptyMap()))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields + ("vless.security" to "future")))
    }

    @Test fun plainTcpAndTlsWorkButVisionWithoutSecurityDoesNot() {
        val simple = "vless://$uuid@127.0.0.1:443?encryption=none&type=tcp"
        assertTrue(requireNotNull(parser.parseLink(simple)).supported)
        assertTrue(requireNotNull(parser.parseLink("$simple&security=tls&sni=www.example.com")).supported)
        assertFalse(requireNotNull(parser.parseLink("$simple&flow=xtls-rprx-vision")).supported)
    }
    @Test fun malformedSpiderXIsRejectedAtUrlClashAndCacheBoundaries() {
        val node = requireNotNull(parser.parseLink(link))
        val transport = requireNotNull(node.transport?.vless)
        listOf("/bad%zz", "/truncated%", "/bad%2").forEach { spider ->
            val encoded = encodeUserInfo(spider)
            val url = link.replace("spx=%2Ftest%3Fa%3D1", "spx=$encoded")
            val parsed = requireNotNull(parser.parseLink(url))
            assertFalse(parsed.supported)
            assertNull(ProxyKernelConfig.build(parsed, 10808))
            val yaml = "proxies:\n  - {name: Test, type: vless, server: edge.example, port: 443, uuid: $uuid, tls: true, servername: www.example.com, reality-opts: {public-key: $key, short-id: '0123', spider-x: '$spider'}}"
            assertFalse(requireNotNull(parser.parse(yaml)).nodes.single().supported)
            val fields = ProxyTransportCodec.toFields(node) + ("vless.spiderX" to spider)
            assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields))
            assertFalse(ProxyKernelConfig.isSupportedVless(transport.copy(spiderX = spider)))
        }
        val valid = transport.copy(spiderX = "/test%20path?a=hello%20world&b=%2Fnext")
        assertTrue(ProxyKernelConfig.isSupportedVless(valid))
        val cached = ProxyTransportCodec.toFields(node.copy(transport = NodeTransport(vless = valid)))
        assertEquals(valid, ProxyTransportCodec.fromFields(ProxyProtocol.Vless, cached)?.vless)
    }

    @Test fun cleartextVlessIsRestrictedToStrictPrivateLiteralsAndLocalhost() {
        val clear = VlessTransport(uuid)
        val privateHosts = listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2", "127.0.0.2", "169.254.1.2",
            "localhost", "localhost.", "::1", "fe80::1234", "fc00::1", "fdff::1", "::ffff:192.168.1.2", "::ffff:c0a8:102")
        val publicHosts = listOf("edge.example", "example.local", "localhost.example", "8.8.8.8", "172.32.0.1", "0.0.0.0", "::",
            "2001:4860:4860::8888", "::ffff:8.8.8.8", "127.0.0.1.example", "127.0.0.1.", "0127.0.0.1", "127.1", ":::1", "fe80::1%eth0")
        val template = requireNotNull(parser.parseLink(link))
        privateHosts.forEach { host ->
            val node = template.copy(host = host, transport = NodeTransport(vless = clear))
            assertTrue(host, node.supported)
            assertNotNull(host, ProxyKernelConfig.build(node, 10808))
        }
        publicHosts.forEach { host ->
            val node = template.copy(host = host, transport = NodeTransport(vless = clear))
            assertFalse(host, node.supported)
            assertNull(host, ProxyKernelConfig.build(node, 10808))
        }
        assertTrue(template.supported)
        val tls = template.copy(transport = NodeTransport(vless = VlessTransport(uuid, security = "tls")))
        assertTrue(tls.supported)
        assertNotNull(ProxyKernelConfig.build(tls, 10808))
        // Persistence validates transport independently; endpoint eligibility remains a node concern.
        val fields = ProxyTransportCodec.toFields(template.copy(transport = NodeTransport(vless = clear)))
        assertEquals(clear, ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields)?.vless)
    }

    @Test fun newPinnedKernelFingerprintNamesRemainSelectable() {
        val original = requireNotNull(parser.parseLink(link))
        listOf("hellofirefox_148", "hellochrome_133", "hellosafari_26_3").forEach { name ->
            val transport = requireNotNull(original.transport?.vless).copy(fingerprint = name)
            val node = original.copy(transport = NodeTransport(vless = transport))
            assertTrue(node.supported)
            assertTrue(requireNotNull(ProxyKernelConfig.build(node, 10808)).contains("\"fingerprint\":\"$name\""))
        }
    }

    @Test fun tcpRealityExporterEmptyDefaultsAreAcceptedWithoutDroppingActiveOptions() {
        val exported = "vless://$uuid@edge.example:443?encryption=none&flow=xtls-rprx-vision&fp=ios&headerType=none&host=&insecure=0&path=&pbk=$key&quicSecurity=none&security=reality&serviceName=&sid=0123&sni=www.example.com&type=tcp#Exported"
        val node = requireNotNull(parser.parseLink(exported))
        assertTrue(node.supported)
        assertEquals("ios", node.transport?.vless?.fingerprint)
        assertNotNull(ProxyKernelConfig.build(node, 10808))
        listOf(
            "host=" to "host=cdn.example",
            "path=" to "path=%2Fws",
            "serviceName=" to "serviceName=grpc-service",
            "quicSecurity=none" to "quicSecurity=aes-128-gcm",
            "insecure=0" to "insecure=1",
            "insecure=0" to "insecure=true",
        ).forEach { (old, replacement) ->
            val unsupported = requireNotNull(parser.parseLink(exported.replace(old, replacement)))
            assertFalse(replacement, unsupported.supported)
            assertNull(ProxyKernelConfig.build(unsupported, 10808))
        }
        assertTrue(requireNotNull(parser.parseLink(exported.replace("insecure=0", "insecure=false"))).supported)
    }

}

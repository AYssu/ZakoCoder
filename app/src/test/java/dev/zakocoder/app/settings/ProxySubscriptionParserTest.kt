package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxySubscriptionParserTest {
    private val parser = ProxySubscriptionParser()

    private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private val vmessJson = """{"v":"2","ps":"VM 节点","add":"198.51.100.30","port":"443","id":"test-uuid"}"""

    private val mixedPlainContent = listOf(
        "socks5://alice:p%40ss@203.0.113.10:1080#HK%2001",
        "socks://198.51.100.7:1081#Direct",
        "http://192.0.2.5:8080#Local%20HTTP",
        "https://198.51.100.9:8443#TLS HTTP",
        "trojan://pw@203.0.113.20:443#Trojan%20Node",
        "vmess://${base64(vmessJson)}",
    ).joinToString("\n")

    @Test fun parsesPlainTextLinkListIncludingNamesAndCredentials() {
        val subscription = parser.parse(mixedPlainContent, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(SubscriptionFormat.PlainLinks, subscription.format)
        assertEquals(6, subscription.nodes.size)
        // https:// proxy links are listed but unsupported; Trojan/VMess are now usable via the kernel.
        assertEquals(5, subscription.supportedCount)

        val socks = subscription.nodes[0]
        assertEquals(ProxyProtocol.Socks5, socks.protocol)
        assertEquals("HK 01", socks.name)
        assertEquals("203.0.113.10", socks.host)
        assertEquals(1080, socks.port)
        assertEquals("alice", socks.username)
        assertEquals("p@ss", socks.password)
        assertTrue(socks.supported)
        assertEquals("socks5://alice:p%40ss@203.0.113.10:1080", socks.proxyUrl)

        val socksAlias = subscription.nodes[1]
        assertEquals(ProxyProtocol.Socks5, socksAlias.protocol)
        assertNull(socksAlias.username)
        assertEquals("socks5://198.51.100.7:1081", socksAlias.proxyUrl)

        // http:// is the plaintext HTTP proxy this app supports; https:// requires a TLS tunnel to
        // the proxy and must never be downgraded into "http://" when saved.
        assertEquals(ProxyProtocol.Http, subscription.nodes[2].protocol)
        assertEquals("http://192.0.2.5:8080", subscription.nodes[2].proxyUrl)
        assertEquals(ProxyProtocol.Other, subscription.nodes[3].protocol)
        assertFalse(subscription.nodes[3].supported)
        assertEquals("", subscription.nodes[3].proxyUrl)
        assertEquals("198.51.100.9", subscription.nodes[3].host)
        assertEquals(8443, subscription.nodes[3].port)
        assertEquals("TLS HTTP", subscription.nodes[3].name)

        val trojan = subscription.nodes[4]
        assertEquals(ProxyProtocol.Trojan, trojan.protocol)
        assertTrue("Trojan is supported through the Xray kernel", trojan.supported)
        assertTrue(trojan.viaKernel)
        assertEquals("", trojan.proxyUrl)
        assertEquals("203.0.113.20", trojan.host)
        assertEquals(443, trojan.port)
        assertEquals("pw", trojan.transport!!.trojan!!.password)

        val vmess = subscription.nodes[5]
        assertEquals(ProxyProtocol.Vmess, vmess.protocol)
        assertTrue("VMess is supported through the Xray kernel", vmess.supported)
        assertTrue(vmess.viaKernel)
        assertEquals("", vmess.proxyUrl)
        assertEquals("VM 节点", vmess.name)
        assertEquals("198.51.100.30", vmess.host)
        assertEquals(443, vmess.port)
        val vmessTransport = vmess.transport!!.vmess!!
        assertEquals("test-uuid", vmessTransport.uuid)
        assertEquals(0, vmessTransport.alterId)
        assertEquals("auto", vmessTransport.security)
        assertEquals("tcp", vmessTransport.network)
    }

    @Test fun parsesStandardAndUrlSafeBase64ListsWithWhitespaceAndNoPadding() {
        val links = listOf(
            "socks5://alice:pw@203.0.113.10:1080#A",
            "http://192.0.2.5:8080#B",
            "vmess://${base64(vmessJson)}",
        ).joinToString("\n")

        val standard = parser.parse(Base64.getEncoder().encodeToString(links.toByteArray(Charsets.UTF_8)))
        requireNotNull(standard)
        assertEquals(SubscriptionFormat.Base64Links, standard.format)
        assertEquals(3, standard.nodes.size)

        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(links.toByteArray(Charsets.UTF_8))
        val wrapped = urlSafe.chunked(37).joinToString("\n   ")
        val tolerant = parser.parse(wrapped)
        requireNotNull(tolerant)
        assertEquals(SubscriptionFormat.Base64Links, tolerant.format)
        assertEquals(3, tolerant.nodes.size)
        assertEquals("A", tolerant.nodes[0].name)
    }

    @Test fun parsesClashInlineAndBlockProxyEntries() {
        val content = """
            port: 7890
            proxies:
              - {name: "HK, 01", type: socks5, server: 203.0.113.1, port: 1080, username: alice, password: pw}
              - name: "JP 02"
                type: ss
                server: 203.0.113.2
                port: 8388
                cipher: aes-128-gcm
                password: ss-pw
              - name: vmess-node
                type: vmess
                server: 203.0.113.3
                port: 443
            proxy-groups:
              - name: auto
                type: select
                proxies: [HK 01]
        """.trimIndent()
        val subscription = parser.parse(content, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(SubscriptionFormat.ClashYaml, subscription.format)
        assertEquals(3, subscription.nodes.size)
        assertEquals(2, subscription.supportedCount)

        val hk = subscription.nodes[0]
        assertEquals("HK, 01", hk.name)
        assertEquals(ProxyProtocol.Socks5, hk.protocol)
        assertTrue(hk.supported)
        assertEquals("socks5://alice:pw@203.0.113.1:1080", hk.proxyUrl)

        assertEquals(ProxyProtocol.Ss, subscription.nodes[1].protocol)
        assertTrue(subscription.nodes[1].supported)
        assertEquals("aes-128-gcm", subscription.nodes[1].transport!!.shadowsocks!!.method)
        assertEquals("ss-pw", subscription.nodes[1].transport!!.shadowsocks!!.password)
        assertEquals(ProxyProtocol.Vmess, subscription.nodes[2].protocol)
        assertFalse("VMess without a uuid stays unusable", subscription.nodes[2].supported)
        assertNull(subscription.nodes[2].transport)
    }

    @Test fun clashSkipsEntriesWithInvalidServerOrPort() {
        val content = """
            proxies:
              - {name: "bad port", type: socks5, server: 192.0.2.1, port: 70000}
              - {name: "bad server", type: http, server: "", port: 8080}
              - {name: "bad port text", type: http, server: 192.0.2.2, port: abc}
              - {name: "ok", type: http, server: 192.0.2.3, port: 8080}
        """.trimIndent()
        val subscription = parser.parse(content)
        requireNotNull(subscription)
        assertEquals(1, subscription.nodes.size)
        assertEquals("ok", subscription.nodes.single().name)
    }

    @Test fun rejectsEmptyJunkAndOversizedContent() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("   \n\t "))
        assertNull(parser.parse("<html><body>not a subscription</body></html>"))
        assertNull(parser.parse("just some free text\nwithout any links"))

        val oversized = "http://192.0.2.5:8080#x\n" + "x".repeat(2 * 1024 * 1024)
        assertNull(parser.parse(oversized))
    }

    @Test fun truncatesAtFiveHundredTwelveNodesAndKeepsIdsStable() {
        val content = (0 until 600).joinToString("\n") { index ->
            "http://10.0.0.${index % 250}:${8080 + index}#node$index"
        }
        val first = parser.parse(content)
        requireNotNull(first)
        assertEquals(512, first.nodes.size)
        val second = parser.parse(content)
        requireNotNull(second)
        assertEquals(first.nodes.map { it.id }, second.nodes.map { it.id })
        assertEquals(first.nodes.size, first.nodes.map { it.id }.distinct().size)
    }

    @Test fun duplicateLinksGetDistinctStableIds() {
        val content = "http://192.0.2.5:8080#dup\nhttp://192.0.2.5:8080#dup"
        val subscription = parser.parse(content)
        requireNotNull(subscription)
        assertEquals(2, subscription.nodes.size)
        assertNotEquals(subscription.nodes[0].id, subscription.nodes[1].id)
        assertEquals(subscription.nodes.map { it.id }, parser.parse(content)!!.nodes.map { it.id })
    }

    @Test fun rejectsMalformedAuthorityLinksAndKeepsOnlyValidLines() {
        listOf(
            "http://192.0.2.5:0#zero",
            "http://192.0.2.5:65536#too-high",
            "http://192.0.2.5#missing-port",
            "http://:8080#missing-host",
            "socks5://[::1#broken-ipv6",
            "not-a-link",
            "ftp://192.0.2.5:21#unsupported-scheme",
        ).forEach { assertNull(it, parser.parseLink(it)) }

        val mixed = listOf("http://192.0.2.5:0#invalid", "http://192.0.2.6:8080#valid").joinToString("\n")
        val subscription = parser.parse(mixed)
        requireNotNull(subscription)
        assertEquals(1, subscription.nodes.size)
        assertEquals("valid", subscription.nodes.single().name)
    }

    @Test fun supportsIpv6AndSocksPartialCredentials() {
        val ipv6 = parser.parseLink("socks5://[2001:db8::1]:1080#v6")
        requireNotNull(ipv6)
        assertEquals("2001:db8::1", ipv6.host)
        assertTrue(ipv6.supported)
        assertEquals("socks5://[2001:db8::1]:1080", ipv6.proxyUrl)

        val partial = parser.parseLink("socks5://only-user@192.0.2.5:1080#partial")
        requireNotNull(partial)
        assertEquals("only-user", partial.username)
        assertNull(partial.password)
        assertFalse("A lone SOCKS5 username is not usable without a password", partial.supported)
        assertEquals("", partial.proxyUrl)
    }

    @Test fun httpCredentialsAreParsedButNeverSupported() {
        val node = parser.parseLink("http://alice:pw@192.0.2.5:8080#http-auth")
        requireNotNull(node)
        assertEquals(ProxyProtocol.Http, node.protocol)
        assertEquals("alice", node.username)
        assertEquals("pw", node.password)
        assertFalse(node.supported)
        assertEquals("", node.proxyUrl)
        assertEquals("http://192.0.2.5:8080", parser.parseLink("http://192.0.2.5:8080#plain")!!.proxyUrl)
    }

    @Test fun unsupportedProtocolsDegradeToOtherWhenTheyCannotBeDecoded() {
        val node = parser.parseLink("vmess://!!!not-base64!!!")
        requireNotNull(node)
        assertEquals(ProxyProtocol.Other, node.protocol)
        assertFalse(node.supported)
        assertEquals("", node.host)
        assertEquals(0, node.port)
        assertEquals("未命名节点", node.name)
    }

    @Test fun truncatesLongNamesToEightyChars() {
        val node = parser.parseLink("http://192.0.2.5:8080#" + "名".repeat(100))
        requireNotNull(node)
        assertEquals(80, node.name.length)
    }

    @Test fun masksSourceUrlAndRedactsCredentialsFromToString() {
        val subscription = parser.parse(mixedPlainContent, "https://sub.example.com/api/v1/client/token-value?token=redacted")
        requireNotNull(subscription)
        assertEquals("https://sub.example.com/•••", subscription.maskedSource)
        assertFalse(subscription.toString().contains("token-value"))
        assertFalse(subscription.toString().contains("redacted"))
        assertFalse(subscription.maskedSource.contains("token-value"))

        val socks = subscription.nodes[0]
        assertFalse(socks.toString().contains("p@ss"))
        assertFalse(socks.toString().contains("alice"))

        assertEquals("", testSubscription(emptyList(), sourceUrl = "http://insecure.example.com/a").maskedSource)
        assertEquals("", testSubscription(emptyList(), sourceUrl = "not a url").maskedSource)
    }

    @Test fun httpsProxyLinkIsUnsupportedInsteadOfBeingDowngradedToPlainHttp() {
        val node = parser.parseLink("https://198.51.100.9:8443#TLS")
        requireNotNull(node)
        assertEquals(ProxyProtocol.Other, node.protocol)
        assertFalse(node.supported)
        assertEquals("", node.proxyUrl)
        assertEquals("198.51.100.9", node.host)
        assertEquals(8443, node.port)
        assertEquals("TLS", node.name)
    }

    @Test fun clashHttpsProxyTypeIsUnsupported() {
        val content = """
            proxies:
              - {name: "tls node", type: https, server: 198.51.100.9, port: 8443}
        """.trimIndent()
        val subscription = parser.parse(content, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(1, subscription.nodes.size)
        assertEquals(ProxyProtocol.Other, subscription.nodes[0].protocol)
        assertFalse(subscription.nodes[0].supported)
    }

    @Test fun utf8ByteOrderMarkPrefixIsIgnored() {
        val subscription = parser.parse("\uFEFF" + mixedPlainContent, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(6, subscription.nodes.size)
    }

    @Test fun unsupportedLinkWithPathKeepsAuthorityForDisplay() {
        val node = parser.parseLink("vless://uuid@203.0.113.20:443/ws?path=%2F#VLESS")
        requireNotNull(node)
        assertEquals(ProxyProtocol.Vless, node.protocol)
        assertEquals("203.0.113.20", node.host)
        assertEquals(443, node.port)
        assertFalse(node.supported)
    }

    @Test fun socks5NodeWithOnlyPasswordIsNeverAdvertisedAsUsable() {
        val node = ProxyNode("id", "x", ProxyProtocol.Socks5, "203.0.113.5", 1080, password = "p")
        assertFalse(node.supported)
        assertEquals("", node.proxyUrl)
        val withUserOnly = ProxyNode("id2", "x", ProxyProtocol.Socks5, "203.0.113.5", 1080, username = "u")
        assertFalse(withUserOnly.supported)
        assertEquals("", withUserOnly.proxyUrl)
    }

    @Test fun idnHostIsNotAdvertisedAsSupported() {
        val node = ProxyNode("id", "IDN", ProxyProtocol.Socks5, "\u4F8B\u5B50.\u4E2D\u56FD", 1080)
        assertFalse(node.supported)
        assertEquals("", node.proxyUrl)
    }

    @Test fun parsesKernelTransportsFromLinksAndMarksViaKernel() {
        val fullVmessJson = """{"v":"2","ps":"VM WS","add":"198.51.100.30","port":"443","id":"uuid-1","aid":"2",""" +
            """"scy":"aes-128-gcm","net":"ws","tls":"tls","sni":"sni.example.com","host":"cdn.example.com","path":"/ws","type":"none"}"""
        val vmess = parser.parseLink("vmess://${base64(fullVmessJson)}")
        requireNotNull(vmess)
        assertEquals(ProxyProtocol.Vmess, vmess.protocol)
        assertTrue(vmess.viaKernel)
        assertTrue(vmess.supported)
        assertEquals("A kernel node has no direct proxy URL", "", vmess.proxyUrl)
        val vm = requireNotNull(vmess.transport?.vmess)
        assertEquals("uuid-1", vm.uuid)
        assertEquals(2, vm.alterId)
        assertEquals("aes-128-gcm", vm.security)
        assertEquals("ws", vm.network)
        assertEquals("tls", vm.tls)
        assertEquals("sni.example.com", vm.sni)
        assertEquals("cdn.example.com", vm.host)
        assertEquals("/ws", vm.path)
        assertEquals("none", vm.headerType)

        val ss = parser.parseLink("ss://${base64("aes-256-gcm:pa:ss")}@198.51.100.40:8388#SS")
        requireNotNull(ss)
        assertEquals(ProxyProtocol.Ss, ss.protocol)
        assertTrue(ss.viaKernel)
        assertEquals("aes-256-gcm", ss.transport!!.shadowsocks!!.method)
        assertEquals("pa:ss", ss.transport!!.shadowsocks!!.password)

        val trojan = parser.parseLink("trojan://p%40ss@198.51.100.50:443?sni=edge.example.com#T")
        requireNotNull(trojan)
        assertEquals(ProxyProtocol.Trojan, trojan.protocol)
        assertTrue(trojan.viaKernel)
        assertEquals("p@ss", trojan.transport!!.trojan!!.password)
        assertEquals("edge.example.com", trojan.transport!!.trojan!!.sni)

        // Missing key material keeps the protocol but makes the kernel node unusable.
        val incompleteVmess = parser.parseLink("vmess://${base64("""{"add":"198.51.100.31","port":"443","ps":"no-id"}""")}")
        requireNotNull(incompleteVmess)
        assertEquals(ProxyProtocol.Vmess, incompleteVmess.protocol)
        assertFalse(incompleteVmess.viaKernel)
        assertFalse(incompleteVmess.supported)
        val incompleteSs = parser.parseLink("ss://onlymethod@198.51.100.41:8388#no-pw")
        requireNotNull(incompleteSs)
        assertEquals(ProxyProtocol.Ss, incompleteSs.protocol)
        assertFalse(incompleteSs.viaKernel)
    }

    @Test fun parsesClashKernelTransports() {
        val content = """
            proxies:
              - {name: vm, type: vmess, server: 198.51.100.60, port: 443, uuid: uuid-9, alterId: 0, cipher: auto, network: ws, tls: tls, servername: a.example, path: /ws, host: c.example}
              - {name: ss, type: ss, server: 198.51.100.61, port: 8388, cipher: chacha20-ietf-poly1305, password: ss-pw}
              - name: tr
                type: trojan
                server: 198.51.100.62
                port: 443
                password: tr-pw
                sni: tr.example
        """.trimIndent()
        val subscription = parser.parse(content, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(3, subscription.nodes.size)
        assertEquals(3, subscription.supportedCount)
        val vm = requireNotNull(subscription.nodes[0].transport?.vmess)
        assertEquals("uuid-9", vm.uuid)
        assertEquals("ws", vm.network)
        assertEquals("tls", vm.tls)
        assertEquals("a.example", vm.sni)
        assertEquals("/ws", vm.path)
        assertEquals("c.example", vm.host)
        assertEquals("chacha20-ietf-poly1305", subscription.nodes[1].transport!!.shadowsocks!!.method)
        assertEquals("ss-pw", subscription.nodes[1].transport!!.shadowsocks!!.password)
        assertEquals("tr-pw", subscription.nodes[2].transport!!.trojan!!.password)
        assertEquals("tr.example", subscription.nodes[2].transport!!.trojan!!.sni)
    }

    @Test fun transportToStringRedactsSecrets() {
        val vmess = VmessTransport("uuid-secret", 0, "auto", "tcp", "", null, null, null, null)
        val ss = ShadowsocksTransport("aes-256-gcm", "ss-secret")
        val trojan = TrojanTransport("tr-secret", null)
        assertFalse(vmess.toString().contains("uuid-secret"))
        assertFalse(ss.toString().contains("ss-secret"))
        assertFalse(trojan.toString().contains("tr-secret"))
        assertFalse(NodeTransport(vmess = vmess, shadowsocks = ss, trojan = trojan).toString().contains("secret"))
    }

    @Test fun clashTlsHttpIsUnsupportedInsteadOfDowngradedToPlainHttp() {
        val yaml = """
            proxies:
              - {name: "tls-http", type: http, server: 192.0.2.1, port: 443, tls: true}
              - {name: "plain-http", type: http, server: 192.0.2.2, port: 8080}
        """.trimIndent()
        val subscription = parser.parse(yaml, "https://sub.example.com/api")
        requireNotNull(subscription)
        assertEquals(ProxyProtocol.Other, subscription.nodes[0].protocol)
        assertFalse(subscription.nodes[0].supported)
        assertEquals("", subscription.nodes[0].proxyUrl)
        assertEquals(ProxyProtocol.Http, subscription.nodes[1].protocol)
        assertTrue(subscription.nodes[1].supported)
        assertEquals("http://192.0.2.2:8080", subscription.nodes[1].proxyUrl)
    }

    @Test fun clashTcpVmessKeepsHeaderTypeOutOfTheProtocolType() {
        val yaml = """
            proxies:
              - {name: "tcp-vmess", type: vmess, server: 198.51.100.1, port: 443, uuid: 11111111-1111-1111-1111-111111111111, alterId: 0, cipher: auto, network: tcp, tls: false}
        """.trimIndent()
        val subscription = parser.parse(yaml, "https://sub.example.com/api")
        requireNotNull(subscription)
        val node = subscription.nodes.single()
        assertTrue("plain TCP VMess must be usable", node.viaKernel)
        assertEquals(null, node.transport?.vmess?.headerType)
        assertEquals("", node.transport?.vmess?.tls)
        requireNotNull(ProxyKernelConfig.build(node, 10808))
    }

    @Test fun clashBooleanTlsIsNormalizedForTheConfigGenerator() {
        val yaml = """
            proxies:
              - {name: "tls-vmess", type: vmess, server: 198.51.100.1, port: 443, uuid: 11111111-1111-1111-1111-111111111111, alterId: 0, cipher: auto, network: tcp, tls: true, servername: edge.example.com}
        """.trimIndent()
        val subscription = parser.parse(yaml, "https://sub.example.com/api")
        requireNotNull(subscription)
        val node = subscription.nodes.single()
        assertTrue(node.viaKernel)
        assertEquals("tls", node.transport?.vmess?.tls)
        requireNotNull(ProxyKernelConfig.build(node, 10808))
    }
}

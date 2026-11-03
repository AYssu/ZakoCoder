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
        // https:// proxy links are listed but unsupported (this app speaks plaintext HTTP only).
        assertEquals(3, subscription.supportedCount)

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
        assertFalse(trojan.supported)
        assertEquals("", trojan.proxyUrl)
        assertEquals("203.0.113.20", trojan.host)
        assertEquals(443, trojan.port)

        val vmess = subscription.nodes[5]
        assertEquals(ProxyProtocol.Vmess, vmess.protocol)
        assertFalse(vmess.supported)
        assertEquals("VM 节点", vmess.name)
        assertEquals("198.51.100.30", vmess.host)
        assertEquals(443, vmess.port)
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
        assertEquals(1, subscription.supportedCount)

        val hk = subscription.nodes[0]
        assertEquals("HK, 01", hk.name)
        assertEquals(ProxyProtocol.Socks5, hk.protocol)
        assertTrue(hk.supported)
        assertEquals("socks5://alice:pw@203.0.113.1:1080", hk.proxyUrl)

        assertEquals(ProxyProtocol.Ss, subscription.nodes[1].protocol)
        assertFalse(subscription.nodes[1].supported)
        assertEquals(ProxyProtocol.Vmess, subscription.nodes[2].protocol)
        assertFalse(subscription.nodes[2].supported)
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
        val node = parser.parseLink("trojan://pw@203.0.113.20:443/ws?path=%2F#Trojan")
        requireNotNull(node)
        assertEquals(ProxyProtocol.Trojan, node.protocol)
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
}

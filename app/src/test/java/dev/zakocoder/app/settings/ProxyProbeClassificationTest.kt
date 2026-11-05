package dev.zakocoder.app.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification ("是不是机场节点") and the no-network guarantee for link validation. All network is
 * faked; the parser exercises the real production code.
 */
class ProxyProbeClassificationTest {
    private val parser = ProxySubscriptionParser()

    @Test fun twoOrMoreNodesWithUsableNodesIsAnAirport() {
        val probe = parser.probe("https://sub.example.com/api", "http://192.0.2.5:8080#A\nhttp://192.0.2.6:8080#B")
        assertTrue(probe is ProxyProbe.Airport)
        val airport = probe as ProxyProbe.Airport
        assertEquals(2, airport.subscription.nodes.size)
        assertEquals(2, airport.subscription.supportedCount)
    }

    @Test fun exactlyOneUsableNodeIsASingleNode() {
        val probe = parser.probe("https://sub.example.com/api", "socks5://alice:pw@192.0.2.5:1080#Only")
        assertTrue(probe is ProxyProbe.SingleNode)
        val node = (probe as ProxyProbe.SingleNode).node
        assertEquals("Only", node.name)
        assertEquals(ProxyProtocol.Socks5, node.protocol)
        assertTrue(node.supported)
    }

    @Test fun allUnsupportedNodesAreReportedAsUnsupportedOnly() {
        val content = listOf(
            "vmess://eyJhZGQiOiIxOTIuMC4yLjUiLCJwb3J0IjoiNDQzIiwicHMiOiJWIn0=",
            "vless://uuid@192.0.2.6:443#VLESS",
        ).joinToString("\n")
        val probe = parser.probe("https://sub.example.com/api", content)
        assertTrue(probe is ProxyProbe.UnsupportedOnly)
        assertEquals(2, (probe as ProxyProbe.UnsupportedOnly).total)
    }

    @Test fun oneUnsupportedNodeIsStillUnsupportedOnly() {
        val probe = parser.probe("https://sub.example.com/api", "vless://uuid@192.0.2.6:443#VLESS")
        assertTrue(probe is ProxyProbe.UnsupportedOnly)
        assertEquals(1, (probe as ProxyProbe.UnsupportedOnly).total)
    }

    @Test fun mixedProtocolsStayAnAirportWithUsableCount() {
        val content = "http://192.0.2.5:8080#A\nvless://uuid@192.0.2.6:443#VLESS"
        val probe = parser.probe("https://sub.example.com/api", content)
        assertTrue(probe is ProxyProbe.Airport)
        assertEquals(1, (probe as ProxyProbe.Airport).subscription.supportedCount)
    }

    @Test fun kernelProtocolWithCompleteTransportCountsAsUsable() {
        val content = "trojan://pw@192.0.2.6:443#T\nvless://uuid@192.0.2.7:443#VLESS"
        val probe = parser.probe("https://sub.example.com/api", content)
        assertTrue(probe is ProxyProbe.Airport)
        val airport = probe as ProxyProbe.Airport
        assertEquals(1, airport.subscription.supportedCount)
        assertTrue(airport.subscription.nodes[0].viaKernel)
    }

    @Test fun subscriptionNoticesDoNotInflateUsableVlessNodeCount() {
        val result = parser.probe("https://sub.example.com/api", metadataAndVlessFixture())
        assertTrue(result is ProxyProbe.Airport)
        val subscription = (result as ProxyProbe.Airport).subscription
        assertEquals(6, subscription.nodes.size)
        assertEquals(1, subscription.supportedCount)
        assertTrue(subscription.nodes.take(5).all { it.subscriptionPlaceholder && !it.supported && !it.viaKernel })
        val real = subscription.nodes.last()
        assertEquals(ProxyProtocol.Vless, real.protocol)
        assertTrue(real.supported)
        assertTrue(real.viaKernel)
        assertEquals("xtls-rprx-vision", real.transport!!.vless!!.flow)
    }

    @Test fun emptyJunkAndNoKnownNodesAreNotASubscription() {
        listOf("", "   ", "<html>not a subscription</html>", "hello world\nno links here")
            .forEach { assertSame(ProxyProbe.NotSubscription, parser.probe("https://sub.example.com/api", it)) }
    }

    @Test fun insecureOrMalformedLinkIsRejectedWithoutFetching() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher()
        val store = FakeProxySubscriptionStore()
        val catalog = ProxyCatalog(fetcher, store)

        catalog.updateLink("http://sub.example.com/api")
        catalog.probe()
        assertEquals(ProxySubscriptionError.InsecureScheme, catalog.state.value.error)
        assertNull(catalog.state.value.probe)

        catalog.updateLink("not a link at all")
        catalog.probe()
        assertEquals(ProxySubscriptionError.InvalidLink, catalog.state.value.error)

        catalog.updateLink("   ")
        catalog.probe()
        assertEquals(ProxySubscriptionError.InvalidLink, catalog.state.value.error)

        assertEquals("No link validation may touch the network", 0, fetcher.calls)
        assertEquals(0, store.saves)
    }
}

/** Synthetic names/identities only; no downloaded subscription or provider credentials. */
internal fun metadataAndVlessFixture(): String {
    val notices = listOf("请立即到官网下载新客户端！", "通知更新于 2026/11/4", "邀请新用户20%返利", "示例.com", "subscription.example.com")
    val links = notices.map { name ->
        val escapedName = name.map { character ->
            if (character.code > 127) "\\u" + character.code.toString(16).padStart(4, '0')
            else if (character == '/') "\\/" else character.toString()
        }.joinToString("")
        val json = """{"v":"2","ps":"$escapedName","add":"127.0.0.1","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"tcp","tls":""}"""
        "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
    val publicKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
    return (links + ("vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443" +
        "?encryption=none&type=tcp&security=reality&flow=xtls-rprx-vision&sni=example.com&fp=chrome&pbk=$publicKey&sid=0123456789abcdef#Real"))
        .joinToString("\n")
}

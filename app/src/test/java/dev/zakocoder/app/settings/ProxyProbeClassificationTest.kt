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
            "trojan://pw@192.0.2.6:443#T",
        ).joinToString("\n")
        val probe = parser.probe("https://sub.example.com/api", content)
        assertTrue(probe is ProxyProbe.UnsupportedOnly)
        assertEquals(2, (probe as ProxyProbe.UnsupportedOnly).total)
    }

    @Test fun oneUnsupportedNodeIsStillUnsupportedOnly() {
        val probe = parser.probe("https://sub.example.com/api", "trojan://pw@192.0.2.6:443#T")
        assertTrue(probe is ProxyProbe.UnsupportedOnly)
        assertEquals(1, (probe as ProxyProbe.UnsupportedOnly).total)
    }

    @Test fun mixedProtocolsStayAnAirportWithUsableCount() {
        val content = "http://192.0.2.5:8080#A\ntrojan://pw@192.0.2.6:443#T"
        val probe = parser.probe("https://sub.example.com/api", content)
        assertTrue(probe is ProxyProbe.Airport)
        assertEquals(1, (probe as ProxyProbe.Airport).subscription.supportedCount)
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

package dev.zakocoder.app.settings

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyVmessJsonEscapesTest {
    private val parser = ProxySubscriptionParser()

    @Test fun decodesUnicodeSlashAndSupplementaryNamesWithoutClassifyingManualLocalLinks() {
        val parsed = requireNotNull(parser.parseLink(link("""\u901a\u77e5\u66f4\u65b0\u4e8e 2026\/11\/4 \ud83d\ude80""")))
        assertEquals("通知更新于 2026/11/4 🚀", parsed.name)
        assertTrue(parsed.supported)
        assertFalse(parsed.subscriptionPlaceholder)
    }

    @Test fun standardEscapesDecodeOnceInTransportFields() {
        val parsed = requireNotNull(parser.parseLink(link("Manual", """\/ws\t\b\f\n\r\"\\\u0061""")))
        assertEquals("/ws\t\b\u000c\n\r\"\\a", parsed.transport!!.vmess!!.path)
        val literalUnicode = requireNotNull(parser.parseLink(link("""literal\\u0061""")))
        assertEquals("literal\\u0061", literalUnicode.name)
    }

    @Test fun invalidEscapesCannotBecomeUsableNodesOrSilentlyLoseOptionalFields() {
        listOf("""\q""", """\u12""", """\uZZZZ""", """\ud800""", """\udc00""", """\ud800\u0061""")
            .forEach { invalid ->
                assertFalse(invalid, requireNotNull(parser.parseLink(link(invalid))).supported)
                assertFalse(invalid, requireNotNull(parser.parseLink(link("Manual", invalid))).supported)
            }
    }

    @Test fun escapedSubscriptionFixtureExcludesAllFiveMetadataNodes() {
        val subscription = requireNotNull(parser.parse(metadataAndVlessFixture(), "https://subscription.example.com/api"))
        assertEquals(6, subscription.nodes.size)
        assertEquals(1, subscription.supportedCount)
        assertEquals("请立即到官网下载新客户端！", subscription.nodes.first().name)
        assertEquals("通知更新于 2026/11/4", subscription.nodes[1].name)
        assertTrue(subscription.nodes.take(5).all { it.subscriptionPlaceholder && !it.supported })
    }

    @Test fun jsonNamesKeepLiteralPercentEncodingAndUrlNamesDecodeOnlyOnce() {
        assertEquals("literal%41%20🚀", requireNotNull(parser.parseLink(link("literal%41%20\\ud83d\\ude80"))).name)
        assertEquals("🚀 space", requireNotNull(parser.parseLink("socks5://127.0.0.1:1080#🚀%20space")).name)
        assertEquals("🚀", requireNotNull(parser.parseLink("socks5://127.0.0.1:1080#%F0%9F%9A%80")).name)
        assertEquals("%41", requireNotNull(parser.parseLink("socks5://127.0.0.1:1080#%2541")).name)
    }

    @Test fun percentDecoderPreservesSupplementaryUnicodeAndRejectsMalformedEscapes() {
        assertEquals("🚀 / emoji", decodeUserInfo("🚀%20%2F%20emoji"))
        listOf("%", "%0", "%GG", "🚀%Q1").forEach { invalid ->
            org.junit.Assert.assertNull(invalid, decodeUserInfo(invalid))
        }
    }

    @Test fun longNamesDoNotSplitSurrogatePairsAtTheLengthLimit() {
        val parsed = requireNotNull(parser.parseLink(link("a".repeat(79) + "\\ud83d\\ude80" + "z")))
        assertEquals("a".repeat(79), parsed.name)
        val fitting = requireNotNull(parser.parseLink(link("a".repeat(78) + "\\ud83d\\ude80" + "z")))
        assertEquals("a".repeat(78) + "🚀", fitting.name)
        assertEquals(80, fitting.name.length)
        val clash = requireNotNull(parser.parse("""
            proxies:
              - name: ${"a".repeat(79)}🚀z
                type: socks5
                server: 198.51.100.1
                port: 1080
        """.trimIndent()))
        assertEquals("a".repeat(79), clash.nodes.single().name)
    }

    private fun link(namePayload: String, pathPayload: String = "/ws"): String {
        val json = """{"ps":"$namePayload","add":"127.0.0.1","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","path":"$pathPayload","tls":""}"""
        return "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }
}

package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-only round-trip for the encrypted subscription cache: the kernel transport fields must
 * survive Keystore encryption/decryption, otherwise a restart turns every VMess/SS/Trojan node back
 * into "unsupported". Requires a real Android Keystore, so this cannot be a JVM test.
 */
class ProxySubscriptionStoreDeviceTest {
    @Test
    fun savesAndRestoresKernelTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedProxySubscriptionStore(context)
        store.clear()
        try {
            val vmess = ProxyNode(
                id = "node-vmess",
                name = "VM",
                protocol = ProxyProtocol.Vmess,
                host = "198.51.100.7",
                port = 443,
                transport = NodeTransport(
                    vmess = VmessTransport(
                        uuid = "11111111-2222-3333-4444-555555555555",
                        alterId = 0,
                        security = "auto",
                        network = "ws",
                        tls = "tls",
                        sni = "sni.example.com",
                        host = "host.example.com",
                        path = "/ws",
                        headerType = null,
                    ),
                ),
            )
            val ss = ProxyNode(
                id = "node-ss",
                name = "SS",
                protocol = ProxyProtocol.Ss,
                host = "198.51.100.8",
                port = 8388,
                transport = NodeTransport(shadowsocks = ShadowsocksTransport("aes-256-gcm", "test-password")),
            )
            val trojan = ProxyNode(
                id = "node-trojan",
                name = "TR",
                protocol = ProxyProtocol.Trojan,
                host = "198.51.100.9",
                port = 443,
                transport = NodeTransport(trojan = TrojanTransport("test-password", "sni.example.com")),
            )
            val vless = ProxyNode(
                id = "node-vless", name = "VL", protocol = ProxyProtocol.Vless,
                host = "198.51.100.10", port = 443,
                transport = NodeTransport(vless = VlessTransport(
                    uuid = "11111111-2222-3333-4444-555555555555",
                    security = "reality", flow = "xtls-rprx-vision", serverName = "example.com",
                    publicKey = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(ByteArray(32) { (it + 1).toByte() }),
                    shortId = "0123456789abcdef", fingerprint = "chrome", spiderX = "/",
                )),
            )
            val subscription = ProxySubscription(
                sourceUrl = "https://sub.example.com/api/v1/client/TOKEN",
                fetchedAt = 1_700_000_000_000L,
                nodes = listOf(vmess, ss, trojan, vless),
                format = SubscriptionFormat.ClashYaml,
            )

            store.save(subscription)
            val loaded = store.load()
            assertNotNull("encrypted cache disappeared", loaded)
            assertEquals(4, loaded!!.nodes.size)
            assertEquals(vmess.transport, loaded.nodes[0].transport)
            assertEquals(ss.transport, loaded.nodes[1].transport)
            assertEquals(trojan.transport, loaded.nodes[2].transport)
            assertEquals(vless.transport, loaded.nodes[3].transport)
            assertTrue("VMess node lost viaKernel after restart", loaded.nodes[0].viaKernel)
            assertTrue("SS node lost viaKernel after restart", loaded.nodes[1].viaKernel)
            assertTrue("Trojan node lost viaKernel after restart", loaded.nodes[2].viaKernel)
            assertTrue("VLESS REALITY/Vision node lost viaKernel after restart", loaded.nodes[3].viaKernel)
            assertTrue(loaded.nodes[3].supported)
        } finally {
            store.clear()
        }
    }

    @Test
    fun reloadReclassifiesPreviouslyUnmarkedSubscriptionMetadata() {
        val store = EncryptedProxySubscriptionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
        try {
            val notice = ProxyNode(
                id = "notice", name = "通知更新于 2026/11/4", protocol = ProxyProtocol.Socks5,
                host = "127.0.0.1", port = 443,
            )
            assertTrue("Fixture models the old incorrectly usable cache", notice.supported)
            store.save(ProxySubscription(
                sourceUrl = "https://subscription.example.com/api", fetchedAt = 1000,
                nodes = listOf(notice), format = SubscriptionFormat.PlainLinks,
            ))

            val loaded = requireNotNull(store.load())
            assertTrue(loaded.nodes.single().subscriptionPlaceholder)
            assertFalse(loaded.nodes.single().supported)
            assertEquals(0, loaded.supportedCount)
        } finally {
            store.clear()
        }
    }

    @Test
    fun standaloneLocalNodeCacheRetainsExplicitUserIntent() {
        val store = EncryptedProxySubscriptionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
        try {
            val source = "socks5://127.0.0.1:1080#example.com"
            val local = requireNotNull(ProxySubscriptionParser().parseLink(source))
            store.save(ProxySubscription(
                sourceUrl = source, fetchedAt = 1000,
                nodes = listOf(local), format = SubscriptionFormat.PlainLinks,
            ))

            val loaded = requireNotNull(store.load())
            assertFalse(loaded.nodes.single().subscriptionPlaceholder)
            assertTrue(loaded.nodes.single().supported)
            assertEquals(1, loaded.supportedCount)
            assertEquals(local.proxyUrl, loaded.nodes.single().proxyUrl)

            listOf(78, 79).forEach { prefixLength ->
                val longSource = "socks5://127.0.0.1:1080#" + "a".repeat(prefixLength) + "🚀tail"
                val longNode = requireNotNull(ProxySubscriptionParser().parseLink(longSource))
                store.save(ProxySubscription(
                    sourceUrl = longSource, fetchedAt = 1000,
                    nodes = listOf(longNode), format = SubscriptionFormat.PlainLinks,
                ))
                val restored = requireNotNull(store.load()).nodes.single()
                assertEquals(longNode.name, restored.name)
                assertTrue(restored.name.length <= 80)
                assertTrue(restored.supported)
            }
        } finally {
            store.clear()
        }
    }
}

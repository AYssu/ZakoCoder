package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
            val subscription = ProxySubscription(
                sourceUrl = "https://sub.example.com/api/v1/client/TOKEN",
                fetchedAt = 1_700_000_000_000L,
                nodes = listOf(vmess, ss, trojan),
                format = SubscriptionFormat.ClashYaml,
            )

            store.save(subscription)
            val loaded = store.load()
            assertNotNull("encrypted cache disappeared", loaded)
            assertEquals(3, loaded!!.nodes.size)
            assertEquals(vmess.transport, loaded.nodes[0].transport)
            assertEquals(ss.transport, loaded.nodes[1].transport)
            assertEquals(trojan.transport, loaded.nodes[2].transport)
            assertTrue("VMess node lost viaKernel after restart", loaded.nodes[0].viaKernel)
            assertTrue("SS node lost viaKernel after restart", loaded.nodes[1].viaKernel)
            assertTrue("Trojan node lost viaKernel after restart", loaded.nodes[2].viaKernel)
        } finally {
            store.clear()
        }
    }
}

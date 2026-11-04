package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM round-trip tests for the transport persistence codec (no Android/Keystore). */
class ProxyTransportCodecTest {
    private val vmess = VmessTransport(
        uuid = "11111111-1111-1111-1111-111111111111",
        alterId = 2,
        security = "aes-128-gcm",
        network = "ws",
        tls = "tls",
        sni = "sni.example.com",
        host = "cdn.example.com",
        path = "/ws",
        headerType = "none",
    )
    private val vless = VlessTransport(
        uuid = "11111111-2222-3333-4444-555555555555",
        encryption = "none",
        flow = "xtls-rprx-vision",
        network = "tcp",
        security = "reality",
        sni = "www.example.com",
        fingerprint = "chrome",
        publicKey = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUV",
        shortId = "0123456789abcdef",
        spiderX = "/",
        host = null,
        path = null,
        serviceName = null,
    )

    private fun node(protocol: ProxyProtocol, transport: NodeTransport?, host: String = "vm.example.com", port: Int = 443) =
        ProxyNode("node-id", "node", protocol, host, port, transport = transport)

    @Test fun vmessFieldsRoundTripWithAllOptionalValues() {
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess)))
        assertEquals(vmess.uuid, fields[ProxyTransportCodec.VMESS_UUID])
        assertEquals("2", fields[ProxyTransportCodec.VMESS_ALTER_ID])
        assertEquals("ws", fields[ProxyTransportCodec.VMESS_NETWORK])
        assertEquals("/ws", fields[ProxyTransportCodec.VMESS_PATH])

        val decoded = ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, fields)
        assertEquals(NodeTransport(vmess = vmess), decoded)
    }

    @Test fun vmessRoundTripOmitsEmptyOptionalFields() {
        val minimal = VmessTransport("uuid-min", 0, "auto", "tcp", "", null, null, null, null)
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = minimal)))
        assertTrue(fields.containsKey(ProxyTransportCodec.VMESS_TLS))
        assertEquals("", fields[ProxyTransportCodec.VMESS_TLS])
        assertTrue(!fields.containsKey(ProxyTransportCodec.VMESS_SNI))
        assertTrue(!fields.containsKey(ProxyTransportCodec.VMESS_HOST))
        assertTrue(!fields.containsKey(ProxyTransportCodec.VMESS_PATH))
        assertTrue(!fields.containsKey(ProxyTransportCodec.VMESS_HEADER_TYPE))
        assertEquals(NodeTransport(vmess = minimal), ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, fields))
    }

    @Test fun shadowsocksAndTrojanRoundTrip() {
        val shadowsocks = ShadowsocksTransport("aes-256-gcm", "pa:ss\"word\\")
        val ssFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Ss, NodeTransport(shadowsocks = shadowsocks), port = 8388))
        assertEquals(NodeTransport(shadowsocks = shadowsocks), ProxyTransportCodec.fromFields(ProxyProtocol.Ss, ssFields))

        val trojanWithSni = TrojanTransport("tr:pa@ss", "edge.example.com")
        val trojanFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Trojan, NodeTransport(trojan = trojanWithSni)))
        assertEquals(NodeTransport(trojan = trojanWithSni), ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, trojanFields))

        val trojanWithoutSni = TrojanTransport("tr-pw", null)
        val minimalFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Trojan, NodeTransport(trojan = trojanWithoutSni)))
        assertTrue(!minimalFields.containsKey(ProxyTransportCodec.TROJAN_SNI))
        assertEquals(NodeTransport(trojan = trojanWithoutSni), ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, minimalFields))
    }

    @Test fun vlessRealityRoundTripsAllKernelFields() {
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vless, NodeTransport(vless = vless)))
        assertEquals(vless.uuid, fields[ProxyTransportCodec.VLESS_UUID])
        assertEquals("reality", fields[ProxyTransportCodec.VLESS_SECURITY])
        assertEquals(vless.publicKey, fields[ProxyTransportCodec.VLESS_PUBLIC_KEY])
        assertEquals(NodeTransport(vless = vless), ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields))
    }

    @Test fun emptyOrNullTransportProducesNoFields() {
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, null)).isEmpty())
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Ss, null)).isEmpty())
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Trojan, null)).isEmpty())
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Vless, null)).isEmpty())
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Http, null, host = "192.0.2.1", port = 8080)).isEmpty())
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Socks5, null, host = "192.0.2.1", port = 1080)).isEmpty())
    }

    @Test fun missingKeyFieldsReturnNull() {
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, emptyMap()))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, mapOf(ProxyTransportCodec.VMESS_UUID to "")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, mapOf(ProxyTransportCodec.VMESS_ALTER_ID to "0")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Ss, mapOf(ProxyTransportCodec.SS_METHOD to "aes-256-gcm")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Ss, mapOf(ProxyTransportCodec.SS_PASSWORD to "pw")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, emptyMap()))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, mapOf(ProxyTransportCodec.TROJAN_SNI to "edge.example.com")))
    }

    @Test fun unsupportedProtocolsOrFieldMismatchReturnNull() {
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess)))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Http, fields))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Socks5, fields))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, fields))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Ss, fields))
        val ssFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Ss, NodeTransport(shadowsocks = ShadowsocksTransport("aes-256-gcm", "pw"))))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, ssFields))
    }

    @Test fun alterIdMustBeAnIntegerInRange() {
        val base = mapOf(
            ProxyTransportCodec.VMESS_UUID to "uuid-1",
            ProxyTransportCodec.VMESS_SECURITY to "auto",
            ProxyTransportCodec.VMESS_NETWORK to "tcp",
            ProxyTransportCodec.VMESS_TLS to "",
        )
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, base + (ProxyTransportCodec.VMESS_ALTER_ID to "abc")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, base + (ProxyTransportCodec.VMESS_ALTER_ID to "-1")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, base + (ProxyTransportCodec.VMESS_ALTER_ID to "65536")))
        val absent = ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, base)
        assertEquals(0, absent!!.vmess!!.alterId)
    }

    @Test fun controlCharactersAndOversizedValuesReturnNull() {
        val vmessFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess)))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, vmessFields + (ProxyTransportCodec.VMESS_PATH to "/bad\npath")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, vmessFields + (ProxyTransportCodec.VMESS_UUID to "bad\nuuid")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, vmessFields + (ProxyTransportCodec.VMESS_PATH to "/" + "x".repeat(1024))))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, vmessFields + (ProxyTransportCodec.VMESS_SNI to "s".repeat(256))))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Ss, mapOf(
            ProxyTransportCodec.SS_METHOD to "aes-256-gcm",
            ProxyTransportCodec.SS_PASSWORD to "p".repeat(256),
        )))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Trojan, mapOf(
            ProxyTransportCodec.TROJAN_PASSWORD to "pw\u0000",
        )))
        // A node whose parsed transport contains an unsafe value must not be persisted at all.
        assertTrue(ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess.copy(path = "/bad\npath")))).isEmpty())
        val vlessFields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vless, NodeTransport(vless = vless)))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, vlessFields + (ProxyTransportCodec.VLESS_PUBLIC_KEY to "bad\nkey")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, vlessFields + (ProxyTransportCodec.VLESS_NETWORK to "quic")))
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vless, vlessFields + (ProxyTransportCodec.VLESS_UUID to "")))
    }

    @Test fun unknownKeysAreIgnored() {
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess))) +
            mapOf("bogus.key" to "value", "vmess.future" to "x")
        assertEquals(NodeTransport(vmess = vmess), ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, fields))
    }

    @Test fun invalidNetworkValueReturnsNull() {
        val fields = ProxyTransportCodec.toFields(node(ProxyProtocol.Vmess, NodeTransport(vmess = vmess))) +
            mapOf(ProxyTransportCodec.VMESS_NETWORK to "quic")
        assertNull(ProxyTransportCodec.fromFields(ProxyProtocol.Vmess, fields))
    }
}

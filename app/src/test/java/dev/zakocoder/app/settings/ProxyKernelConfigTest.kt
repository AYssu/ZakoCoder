package dev.zakocoder.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the generated Xray config. No process/network is started; the assertions read
 * the JSON text (org.json is not available to JVM unit tests) so escaping is checked literally.
 */
class ProxyKernelConfigTest {
    private fun vmessNode(
        network: String = "tcp",
        tls: String = "",
        sni: String? = null,
        host: String? = null,
        path: String? = null,
        uuid: String = "11111111-1111-1111-1111-111111111111",
        alterId: Int = 0,
        security: String = "auto",
        headerType: String? = null,
    ) = ProxyNode(
        id = "vm-node", name = "VM", protocol = ProxyProtocol.Vmess, host = "vm.example.com", port = 443,
        transport = NodeTransport(
            vmess = VmessTransport(uuid, alterId, security, network, tls, sni, host, path, headerType),
        ),
    )

    private fun ssNode(pw: String = "ss-pw", method: String = "aes-256-gcm") = ProxyNode(
        id = "ss-node", name = "SS", protocol = ProxyProtocol.Ss, host = "ss.example.com", port = 8388,
        transport = NodeTransport(shadowsocks = ShadowsocksTransport(method, pw)),
    )

    private fun trojanNode(pw: String = "tr-pw", sni: String? = null) = ProxyNode(
        id = "tr-node", name = "TR", protocol = ProxyProtocol.Trojan, host = "tr.example.com", port = 443,
        transport = NodeTransport(trojan = TrojanTransport(pw, sni)),
    )

    private fun vlessNode(
        security: String = "reality",
        network: String = "tcp",
        flow: String = "xtls-rprx-vision",
        publicKey: String? = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA",
        host: String? = null,
        path: String? = null,
        serviceName: String? = null,
    ) = ProxyNode(
        id = "vl-node", name = "VL", protocol = ProxyProtocol.Vless, host = "vl.example.com", port = 443,
        transport = NodeTransport(
            vless = VlessTransport(
                uuid = "11111111-2222-3333-4444-555555555555",
                encryption = "none",
                flow = flow,
                network = network,
                security = security,
                serverName = "www.example.com",
                fingerprint = "chrome",
                publicKey = publicKey.takeIf { security == "reality" },
                shortId = "0123456789abcdef".takeIf { security == "reality" },
                spiderX = "/".takeIf { security == "reality" },
                host = host,
                path = path,
                serviceName = serviceName,
            ),
        ),
    )

    @Test fun vmessTcpProducesSocksInboundAndVmessOutbound() {
        val json = ProxyKernelConfig.build(vmessNode(), 10808)
        requireNotNull(json)
        assertTrue(json.contains("\"loglevel\":\"warning\""))
        assertTrue(json.contains("\"listen\":\"127.0.0.1\",\"port\":10808,\"protocol\":\"socks\""))
        assertTrue(json.contains("\"auth\":\"noauth\",\"udp\":false"))
        assertTrue(json.contains("\"protocol\":\"vmess\""))
        assertTrue(json.contains("\"address\":\"vm.example.com\",\"port\":443"))
        assertTrue(json.contains("\"id\":\"11111111-1111-1111-1111-111111111111\""))
        assertTrue(json.contains("\"alterId\":0"))
        assertTrue(json.contains("\"security\":\"auto\""))
        assertTrue(json.contains("\"network\":\"tcp\""))
        assertTrue(json.contains("\"security\":\"none\""))
        assertFalse(json.contains("tlsSettings"))
    }

    @Test fun vmessWebSocketTlsCarriesSniPathAndHostHeader() {
        val json = ProxyKernelConfig.build(
            vmessNode(network = "ws", tls = "tls", sni = "sni.example.com", host = "cdn.example.com", path = "/ws"),
            20808,
        )
        requireNotNull(json)
        assertTrue(json.contains("\"network\":\"ws\""))
        assertTrue(json.contains("\"security\":\"tls\""))
        assertTrue(json.contains("\"tlsSettings\":{\"serverName\":\"sni.example.com\"}"))
        assertTrue(json.contains("\"wsSettings\":{\"path\":\"/ws\",\"headers\":{\"Host\":\"cdn.example.com\"}}"))
        assertTrue(json.contains("\"port\":20808"))
    }

    @Test fun vmessTlsWithoutSniFallsBackToTheNodeHost() {
        val json = ProxyKernelConfig.build(vmessNode(tls = "tls"), 10808)
        requireNotNull(json)
        assertTrue(json.contains("\"serverName\":\"vm.example.com\""))
    }

    @Test fun shadowsocksUsesMethodAndPassword() {
        val json = ProxyKernelConfig.build(ssNode(), 10808)
        requireNotNull(json)
        assertTrue(json.contains("\"protocol\":\"shadowsocks\""))
        assertTrue(json.contains("\"method\":\"aes-256-gcm\""))
        assertTrue(json.contains("\"password\""))
        assertTrue(json.contains("ss-pw"))
        assertTrue(json.contains("\"address\":\"ss.example.com\",\"port\":8388"))
    }

    @Test fun trojanIsTlsOnlyAndDefaultsSniToTheNodeHost() {
        val json = ProxyKernelConfig.build(trojanNode(), 10808)
        requireNotNull(json)
        assertTrue(json.contains("\"protocol\":\"trojan\""))
        assertTrue(json.contains("\"password\""))
        assertTrue(json.contains("tr-pw"))
        assertTrue(json.contains("\"security\":\"tls\""))
        assertTrue(json.contains("\"serverName\":\"tr.example.com\""))
        val withSni = ProxyKernelConfig.build(trojanNode(sni = "edge.example.com"), 10808)
        requireNotNull(withSni)
        assertTrue(withSni.contains("\"serverName\":\"edge.example.com\""))
    }

    @Test fun vlessRealityVisionProducesAnAuthenticatedVlessOutbound() {
        val json = ProxyKernelConfig.build(vlessNode(), 10808, "socks-user", "socks-pass")
        requireNotNull(json)
        assertTrue(json.contains("\"protocol\":\"vless\""))
        assertTrue(json.contains("\"id\":\"11111111-2222-3333-4444-555555555555\""))
        assertTrue(json.contains("\"encryption\":\"none\""))
        assertTrue(json.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(json.contains("\"network\":\"tcp\""))
        assertTrue(json.contains("\"security\":\"reality\""))
        assertTrue(json.contains("\"serverName\":\"www.example.com\""))
        assertTrue(json.contains("\"fingerprint\":\"chrome\""))
        assertTrue(json.contains("\"publicKey\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\""))
        assertTrue(json.contains("\"shortId\":\"0123456789abcdef\""))
        assertTrue(json.contains("\"spiderX\":\"/\""))
        assertTrue(json.contains("\"auth\":\"password\""))
    }

    @Test fun vlessWebSocketAndGrpcTransportSettingsAreGenerated() {
        val ws = ProxyKernelConfig.build(
            vlessNode(security = "tls", network = "ws", flow = "", host = "cdn.example.com", path = "/ws"),
            10808,
        )
        requireNotNull(ws)
        assertTrue(ws.contains("\"network\":\"ws\""))
        assertTrue(ws.contains("\"wsSettings\":{\"path\":\"/ws\",\"headers\":{\"Host\":\"cdn.example.com\"}}"))
        assertTrue(ws.contains("\"tlsSettings\":{\"serverName\":\"www.example.com\",\"fingerprint\":\"chrome\"}"))

        val grpc = ProxyKernelConfig.build(
            vlessNode(security = "tls", network = "grpc", flow = "", serviceName = "grpc-service"),
            10808,
        )
        requireNotNull(grpc)
        assertTrue(grpc.contains("\"network\":\"grpc\""))
        assertTrue(grpc.contains("\"grpcSettings\":{\"serviceName\":\"grpc-service\"}"))
    }

    @Test fun quotesAndBackslashesAreJsonEscapedAndNewlinesAreRejected() {
        val escaped = ProxyKernelConfig.build(ssNode(pw = "pa\"ss\\word"), 10808)
        requireNotNull(escaped)
        assertTrue(escaped.contains("\"password\":\"pa\\\"ss\\\\word\""))

        assertNull("control characters must be rejected", ProxyKernelConfig.build(ssNode(pw = "line\nbreak"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(host = "bad\nhost", network = "ws"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(path = "/ws\nx", network = "ws"), 10808))
    }

    @Test fun unsupportedOrIncompleteNodesReturnNull() {
        assertNull(ProxyKernelConfig.build(ProxyNode("id", "socks", ProxyProtocol.Socks5, "192.0.2.1", 1080), 10808))
        assertNull(ProxyKernelConfig.build(ProxyNode("id", "http", ProxyProtocol.Http, "192.0.2.1", 8080), 10808))
        assertNull(ProxyKernelConfig.build(ProxyNode("id", "vless", ProxyProtocol.Vless, "192.0.2.1", 443), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(publicKey = null), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(network = "quic"), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(flow = "unsupported-flow"), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(network = "ws", security = "tls", flow = "", path = "relative"), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(network = "ws", security = "tls", flow = "xtls-rprx-vision"), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(network = "tcp", host = "cdn.example.com"), 10808))
        assertNull(ProxyKernelConfig.build(vlessNode(network = "grpc", security = "tls", flow = "", host = "cdn.example.com", serviceName = "svc"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode().copy(transport = null), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(uuid = ""), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(network = "quic"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(tls = "reality"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(security = "unknown-cipher"), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(alterId = -1), 10808))
        assertNull(ProxyKernelConfig.build(ssNode(method = "bad method"), 10808))
        assertNull(ProxyKernelConfig.build(trojanNode(pw = ""), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode(), 0))
        assertNull(ProxyKernelConfig.build(vmessNode(), 65536))
        assertNull(ProxyKernelConfig.build(vmessNode().copy(host = ""), 10808))
        assertNull(ProxyKernelConfig.build(vmessNode().copy(port = 0), 10808))
    }

    @Test fun listenPortIsInjectedNotHardcoded() {
        assertTrue(ProxyKernelConfig.build(vmessNode(), 12345)!!.contains("\"port\":12345"))
        assertTrue(ProxyKernelConfig.build(vmessNode(), 1)!!.contains("\"port\":1"))
        assertTrue(ProxyKernelConfig.build(vmessNode(), 65535)!!.contains("\"port\":65535"))
    }

    @Test fun passwordInboundUsesOnlyTheGivenAccount() {
        val json = ProxyKernelConfig.build(vmessNode(), 10808, "socks-user", "socks-pass")
        requireNotNull(json)
        assertTrue(json.contains("\"auth\":\"password\""))
        assertTrue(json.contains("\"user\":\"socks-user\""))
        assertTrue(json.contains("\"pass\":\"socks-pass\""))
        assertFalse(json.contains("noauth"))
    }

    @Test fun halfCredentialIsRejected() {
        assertNull(ProxyKernelConfig.build(vmessNode(), 10808, "user-only", null))
        assertNull(ProxyKernelConfig.build(vmessNode(), 10808, null, "pass-only"))
    }

    @Test fun unsupportedShadowsocksMethodFailsClosed() {
        assertNull(ProxyKernelConfig.build(ssNode(method = "magic-cipher"), 10808))
        assertFalse(ssNode(method = "magic-cipher").viaKernel)
        assertTrue(ssNode(method = "chacha20-ietf-poly1305").viaKernel)
    }

    @Test fun vmessTcpHeaderObfuscationIsNotAdvertisedAsUsable() {
        assertTrue(vmessNode(headerType = null).viaKernel)
        assertTrue(vmessNode(headerType = "none").viaKernel)
        assertFalse(vmessNode(headerType = "http").viaKernel)
    }
}

package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.kernel.KernelPluginManager
import dev.zakocoder.app.kernel.KernelPluginStatus
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in device proof for the optional kernel plugin. The main APK no longer packages or executes
 * Xray, so the device must have the matching signed plugin APK installed first (the debug cert pin
 * is `BuildConfig.KERNEL_PLUGIN_CERT_SHA256`). CI does not run connected tests; run explicitly with
 * a local VMess + HTTP fixture:
 *
 * ```
 * adb install -r kernel-plugin-debug.apk
 * adb shell am instrument -w -e kernelPluginE2E true \
 *   -e kernelPluginHost 10.0.2.2 -e kernelPluginPort 10086 -e kernelPluginHttpPort 8888 \
 *   dev.zakocoder.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * Missing plugin or unreachable fixture skips instead of faking a pass. The server/UUID are
 * test-only values from the local fixture; no real subscription or credential is used.
 */
class KernelPluginDeviceTest {
    private fun arguments() = InstrumentationRegistry.getArguments()

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(host, port), 2_000)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Test
    fun installedPluginBindsAndReportsContractVersion() {
        assumeTrue("Opt-in kernel-plugin e2e", arguments().getString("kernelPluginE2E") == "true")
        warmDeviceActivity()
        val manager = KernelPluginManager(InstrumentationRegistry.getInstrumentation().targetContext)
        manager.refresh()
        assumeTrue("Kernel plugin not installed; install a matching signed plugin APK first", manager.status.value is KernelPluginStatus.Installed)
        val service = manager.bind() ?: error("plugin service did not bind")
        try {
            assertEquals(1, service.contractVersion())
        } finally {
            manager.unbind()
        }
    }

    @Test
    fun vmessKernelThroughPluginProxiesHttpTraffic() = kernelProxiesHttpTraffic(vless = false)

    @Test
    fun vlessKernelThroughPluginProxiesHttpTraffic() = kernelProxiesHttpTraffic(vless = true)

    private fun kernelProxiesHttpTraffic(vless: Boolean) = runBlocking {
        assumeTrue("Opt-in kernel-plugin e2e", arguments().getString("kernelPluginE2E") == "true")
        val host = arguments().getString("kernelPluginHost") ?: "10.0.2.2"
        val vmessPort = (arguments().getString(if (vless) "kernelPluginVlessPort" else "kernelPluginPort") ?: if (vless) "10087" else "10086").toInt()
        val httpPort = (arguments().getString("kernelPluginHttpPort") ?: "8888").toInt()
        // Resolved by the VMess server, not the device: the fixture HTTP server runs on the host.
        val httpHost = arguments().getString("kernelPluginHttpHost") ?: "127.0.0.1"
        val uuid = arguments().getString("kernelPluginUuid") ?: "11111111-2222-3333-4444-555555555555"
        assumeTrue("VMess fixture unreachable", reachable(host, vmessPort))

        warmDeviceActivity()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plugins = KernelPluginManager(context)
        plugins.refresh()
        assumeTrue("Kernel plugin not installed", plugins.status.value is KernelPluginStatus.Installed)

        val node = ProxyNode(
            id = "device-vmess",
            name = "device vmess",
            protocol = if (vless) ProxyProtocol.Vless else ProxyProtocol.Vmess,
            host = host,
            port = vmessPort,
            transport = if (vless) NodeTransport(vless = VlessTransport(uuid)) else NodeTransport(
                vmess = VmessTransport(
                    uuid = uuid,
                    alterId = 0,
                    security = "auto",
                    network = "tcp",
                    tls = "",
                    sni = null,
                    host = null,
                    path = null,
                    headerType = null,
                ),
            ),
        )
        assumeTrue("Kernel transport must be complete", node.viaKernel)

        val kernel = ProxyKernel(context, plugins)
        try {
            val state = kernel.start(node)
            assertTrue("kernel start failed: $state", state is ProxyKernelState.Running)
            // The inbound requires the per-start credential; the URL is the only place it is exposed.
            val spec = requireNotNull(NetworkProxy.parse(kernel.localProxyUrl().orEmpty()))
            assertTrue("kernel inbound must be credential-gated", spec.hasCredentials)
            // The platform SOCKS stack on Android does not use java.net.Authenticator, so the test
            // performs the RFC 1929 handshake itself and reads the HTTP response over the tunnel.
            val response = httpViaSocks(spec, httpHost, httpPort)
            assertTrue("expected HTTP 200 through the plugin kernel, got:\n$response", response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"))
            assertTrue("expected fixture body through the kernel", response.contains("kernel-e2e-ok"))
        } finally {
            kernel.stop()
        }
        assertTrue(kernel.state.value is ProxyKernelState.Stopped)
    }

    /** SOCKS5 CONNECT with password auth, then one HTTP/1.1 GET over the tunnel. */
    private fun httpViaSocks(spec: NetworkProxy.Spec, targetHost: String, targetPort: Int): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", spec.port), 2_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            val greeting = ByteArray(2); java.io.DataInputStream(input).readFully(greeting)
            check(greeting[0].toInt() == 5 && greeting[1].toInt() == 2) { "kernel did not select password auth" }
            val user = spec.username!!.toByteArray(Charsets.UTF_8)
            val pass = spec.password!!.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
            val auth = ByteArray(2); java.io.DataInputStream(input).readFully(auth)
            check(auth[0].toInt() == 1 && auth[1].toInt() == 0) { "kernel rejected the SOCKS credential" }
            val address = targetHost.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            out.flush()
            val reply = ByteArray(10)
            java.io.DataInputStream(input).readFully(reply)
            check(reply[0].toInt() == 5 && reply[1].toInt() == 0) { "SOCKS CONNECT failed" }
            out.write("GET / HTTP/1.1\r\nHost: $targetHost:$targetPort\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            // Assert the complete HTTP response, not transport EOF: a proxy may keep its tunnel
            // alive after the upstream has supplied a complete Content-Length framed response.
            val header = java.io.ByteArrayOutputStream()
            while (!header.toString(Charsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                check(header.size() < 16 * 1024) { "fixture HTTP headers exceed limit" }
                val next = input.read()
                check(next >= 0) { "fixture HTTP headers truncated" }
                header.write(next)
            }
            val headers = header.toString(Charsets.US_ASCII.name())
            val length = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            check(length != null && length in 0..64 * 1024) { "fixture requires bounded Content-Length" }
            val body = ByteArray(length)
            java.io.DataInputStream(input).readFully(body)
            return headers + body.toString(Charsets.UTF_8)
        }
    }
}

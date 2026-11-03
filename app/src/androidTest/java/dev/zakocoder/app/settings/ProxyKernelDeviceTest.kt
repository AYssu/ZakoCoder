package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in device proof for the bundled Xray kernel: it executes the packaged jniLib from
 * nativeLibraryDir, starts a loopback SOCKS inbound and reaches an HTTP server through a VMess
 * outbound. CI does not run connected tests; run it explicitly with a local server:
 *
 * ```
 * adb shell am instrument -w -e proxyKernelE2E true \
 *   -e proxyKernelHost 10.0.2.2 -e proxyKernelPort 10086 -e proxyKernelHttpPort 8888 \
 *   dev.zakocoder.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The server and UUID are test-only values created by the local fixture; no real subscription or
 * credential is involved.
 */
class ProxyKernelDeviceTest {
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
    fun vmessKernelRunsFromNativeLibraryDirAndProxiesHttpTraffic() = runBlocking {
        assumeTrue(
            "Opt-in kernel e2e (see class comment for the local fixture)",
            arguments().getString("proxyKernelE2E") == "true",
        )
        val host = arguments().getString("proxyKernelHost") ?: "10.0.2.2"
        val vmessPort = (arguments().getString("proxyKernelPort") ?: "10086").toInt()
        val httpPort = (arguments().getString("proxyKernelHttpPort") ?: "8888").toInt()
        // Resolved by the VMess server, not the device: the fixture HTTP server runs on the host.
        val httpHost = arguments().getString("proxyKernelHttpHost") ?: "127.0.0.1"
        val uuid = arguments().getString("proxyKernelUuid") ?: "11111111-2222-3333-4444-555555555555"
        assumeTrue("VMess fixture unreachable", reachable(host, vmessPort))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The packaged kernel must exist in nativeLibraryDir; missing means packaging regressed.
        val kernelFile = java.io.File(context.applicationInfo.nativeLibraryDir, "libzakocoder_xray.so")
        assertTrue("kernel jniLib missing: ${kernelFile.absolutePath}", kernelFile.isFile)

        val node = ProxyNode(
            id = "device-vmess",
            name = "device vmess",
            protocol = ProxyProtocol.Vmess,
            host = host,
            port = vmessPort,
            transport = NodeTransport(
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

        val kernel = ProxyKernel(context)
        try {
            val state = kernel.start(node)
            assertTrue("kernel start failed: $state", state is ProxyKernelState.Running)
            // The inbound requires the per-start credential; the URL is the only place it is exposed.
            val spec = requireNotNull(NetworkProxy.parse(kernel.localProxyUrl().orEmpty()))
            assertTrue("kernel inbound must be credential-gated", spec.hasCredentials)
            // java.net.Authenticator is not consulted by the platform SOCKS stack on Android, so the
            // test performs the RFC 1929 handshake itself and reads the HTTP response over the tunnel.
            val response = httpViaSocks(spec, httpHost, httpPort)
            assertTrue("expected HTTP 200 through the VMess kernel, got:\n$response", response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"))
            assertTrue("expected fixture body through the kernel", response.contains("kernel-e2e-ok"))
        } finally {
            kernel.stop()
        }
        assertTrue(kernel.state.value is ProxyKernelState.Stopped)
    }

    /**
     * Regression for the Android TLS trust bug: a pure Go (CGO_ENABLED=0) kernel has no system CA
     * pool unless ProxyKernel passes SSL_CERT_DIR. This dials a public TLS host through a VMess TLS
     * node; the VMess handshake itself is expected to fail, but the log must not show an x509 trust
     * error. Opt-in with `-e proxyKernelTlsE2E true`.
     */
    @Test
    fun tlsOutboundUsesAndroidSystemCertificates() = runBlocking {
        assumeTrue(
            "Opt-in TLS trust check (see class comment)",
            arguments().getString("proxyKernelTlsE2E") == "true",
        )
        val host = arguments().getString("proxyKernelTlsHost") ?: "www.baidu.com"
        val port = (arguments().getString("proxyKernelTlsPort") ?: "443").toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val node = ProxyNode(
            id = "device-tls",
            name = "device tls",
            protocol = ProxyProtocol.Vmess,
            host = host,
            port = port,
            transport = NodeTransport(
                vmess = VmessTransport("11111111-2222-3333-4444-555555555555", 0, "auto", "tcp", "tls", host, null, null, null),
            ),
        )
        assumeTrue("TLS transport must be complete", node.viaKernel)
        val kernel = ProxyKernel(context)
        try {
            val state = kernel.start(node)
            assertTrue("kernel start failed: $state", state is ProxyKernelState.Running)
            val spec = requireNotNull(NetworkProxy.parse(kernel.localProxyUrl().orEmpty()))
            // Force an outbound dial; target does not matter, the kernel must first validate its TLS peer.
            runCatching { socksConnect(spec, "example.com", 80) }
            delay(2_500)
            val log = File(context.cacheDir, "proxy-kernel/kernel.log").readText()
            assertFalse("kernel has no Android system TLS roots:\n$log", log.contains("x509: certificate signed by unknown authority"))
        } finally {
            kernel.stop()
        }
    }

    /** Minimal SOCKS5 CONNECT used only to trigger one kernel outbound dial. */
    private fun socksConnect(spec: NetworkProxy.Spec, targetHost: String, targetPort: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", spec.port), 2_000)
            socket.soTimeout = 2_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            val greeting = ByteArray(2); input.read(greeting)
            val user = spec.username!!.toByteArray(Charsets.UTF_8)
            val pass = spec.password!!.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
            val auth = ByteArray(2); input.read(auth)
            val address = targetHost.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            out.flush()
            val reply = ByteArray(10); input.read(reply)
        }
    }

    /** SOCKS5 CONNECT with password auth, then one HTTP/1.1 GET over the tunnel. */
    private fun httpViaSocks(spec: NetworkProxy.Spec, targetHost: String, targetPort: Int): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", spec.port), 2_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            val greeting = ByteArray(2); input.read(greeting)
            check(greeting[0].toInt() == 5 && greeting[1].toInt() == 2) { "kernel did not select password auth" }
            val user = spec.username!!.toByteArray(Charsets.UTF_8)
            val pass = spec.password!!.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
            val auth = ByteArray(2); input.read(auth)
            check(auth[0].toInt() == 1 && auth[1].toInt() == 0) { "kernel rejected the SOCKS credential" }
            val address = targetHost.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            out.flush()
            val reply = ByteArray(10)
            input.read(reply)
            check(reply[0].toInt() == 5 && reply[1].toInt() == 0) { "SOCKS CONNECT failed" }
            out.write("GET / HTTP/1.1\r\nHost: $targetHost:$targetPort\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            return input.readBytes().toString(Charsets.UTF_8)
        }
    }
}

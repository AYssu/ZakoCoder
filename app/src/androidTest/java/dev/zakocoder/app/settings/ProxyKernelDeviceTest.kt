package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
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
            val port = (state as ProxyKernelState.Running).port
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url("http://$httpHost:$httpPort/").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue((response.body?.bytes()?.size ?: 0) > 0)
            }
        } finally {
            kernel.stop()
        }
        assertTrue(kernel.state.value is ProxyKernelState.Stopped)
    }
}

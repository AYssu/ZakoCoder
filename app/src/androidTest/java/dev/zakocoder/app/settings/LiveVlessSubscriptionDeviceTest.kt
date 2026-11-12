package dev.zakocoder.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.kernel.KernelPluginManager
import dev.zakocoder.app.kernel.KernelPluginStatus
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Explicit local opt-in. The URL is supplied in a temporary private input, never test arguments. */
class LiveVlessSubscriptionDeviceTest {
    @Test(timeout = 900_000) fun downloadsAndTestsRealVlessWithoutLoggingCredentials() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires explicit local live test", InstrumentationRegistry.getArguments().getString("liveVless") == "true")
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "live-subscription-input.txt")
        val document = File(context.filesDir, "live-subscription-document.txt")
        check(input.isFile || document.isFile) { "Private local test input missing" }
        val report = JSONObject()
        var activity: ActivityScenario<MainActivity>? = null
        try {
            warmDeviceActivity()
            activity = ActivityScenario.launch(MainActivity::class.java)
            val body = if (document.isFile) {
                report.put("subscriptionDownload", "host_staged_document")
                document.readText()
            } else {
                report.put("subscriptionDownload", "device_https")
                try { HttpProxySubscriptionFetcher().fetch(input.readText().trim()) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: ProxySubscriptionException) {
                    report.put("downloadFailure", failure.reason.name)
                    throw AssertionError("Subscription download failed: ${failure.reason.name}")
                }
            }
            val subscription = requireNotNull(ProxySubscriptionParser().parse(body))
            report.put("total", subscription.nodes.size)
                .put("notices", subscription.nodes.count { it.subscriptionPlaceholder })
                .put("supported", subscription.supportedCount)
                .put("supportedVless", subscription.nodes.count { it.supported && it.protocol == ProxyProtocol.Vless })
            assertTrue(subscription.nodes.filter { it.subscriptionPlaceholder }.none { it.supported || it.viaKernel })
            val limit = InstrumentationRegistry.getArguments().getString("liveNodeCount")?.toIntOrNull()?.coerceIn(1, 3) ?: 3
            val nodes = subscription.nodes.filter { it.supported && it.protocol == ProxyProtocol.Vless }.take(limit)
            assertTrue("Subscription has no supported VLESS nodes", nodes.isNotEmpty())
            val plugins = KernelPluginManager(context)
            assertTrue("Matching kernel plugin required", plugins.status.value is KernelPluginStatus.Installed)
            val kernel = ProxyKernel(context, plugins)
            val attempts = JSONArray()
            report.put("attempts", attempts)
            var successes = 0
            try {
                for ((index, node) in nodes.withIndex()) {
                    val attempt = JSONObject().put("index", index).put("security", node.transport?.vless?.security)
                        .put("flow", node.transport?.vless?.flow)
                    attempts.put(attempt)
                    try {
                        val started = kernel.start(node)
                        attempt.put("started", started is ProxyKernelState.Running)
                        if (started !is ProxyKernelState.Running) {
                            attempt.put("failure", (started as? ProxyKernelState.Failed)?.reason?.name ?: "Stopped")
                            continue
                        }
                        if (InstrumentationRegistry.getArguments().getString("liveDebug") == "true") {
                            val spec = requireNotNull(NetworkProxy.parse(kernel.localProxyUrl().orEmpty()))
                            val addresses = java.net.InetAddress.getAllByName(node.host)
                            val destination = (addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.first()).hostAddress
                            val config = requireNotNull(ProxyKernelConfig.build(node, spec.port, spec.username, spec.password, resolvedAddress = destination))
                                .replace("\"loglevel\":\"warning\"", "\"loglevel\":\"debug\"")
                                .replace("\"realitySettings\":{", "\"realitySettings\":{\"show\":true,")
                            val service = requireNotNull(plugins.bind())
                            assertTrue(JSONObject(service.start(config, spec.port, spec.username, spec.password, node.id, node.name)).optBoolean("ok"))
                        }
                        if (InstrumentationRegistry.getArguments().getString("liveGuest") == "true") {
                            val bridge = File(context.filesDir, "live-guest-bridge.json")
                            val result = File(context.filesDir, "live-guest-result.json")
                            result.delete()
                            try {
                                bridge.writeText(JSONObject().put("index", index)
                                    .put("proxy", kernel.localProxyUrl()).toString())
                                val waitSeconds = InstrumentationRegistry.getArguments()
                                    .getString("liveGuestWaitSeconds")?.toLongOrNull()?.coerceIn(1, 600) ?: 65L
                                val deadline = System.currentTimeMillis() + waitSeconds * 1_000
                                while (!result.isFile && System.currentTimeMillis() < deadline) Thread.sleep(200)
                                if (result.isFile) attempt.put("guest", JSONObject(result.readText()))
                                else attempt.put("guest", "host_probe_timeout")
                            } finally { bridge.delete(); result.delete() }
                        }
                        val status = tlsGetViaSocks(requireNotNull(NetworkProxy.parse(kernel.localProxyUrl().orEmpty()))) { attempt.put("stage", it) }
                        attempt.put("httpStatus", status)
                        if (status == 204) successes++
                    } catch (_: java.net.SocketTimeoutException) { attempt.put("failure", "Timeout") }
                    catch (_: javax.net.ssl.SSLException) { attempt.put("failure", "Tls") }
                    catch (failure: java.io.IOException) {
                        attempt.put("failure", "Transport").put("transportKind", failure.javaClass.simpleName)
                        attempt.put("socketClosed", failure.message?.contains("closed", ignoreCase = true) == true)
                    }
                    finally {
                        attempt.put("diagnostic", plugins.diagnostics())
                        kernel.stop(); Thread.sleep(500)
                    }
                }
            } finally { kernel.stop(); plugins.unbind() }
            report.put("successfulTransfers", successes)
            report.put("diagnostic", plugins.diagnostics())
            assertTrue("No VLESS node completed HTTPS transfer; consult sanitized local report", successes > 0)
        } finally {
            input.delete()
            document.delete()
            File(context.getExternalFilesDir(null), "live-vless-report.json").writeText(report.toString(2))
            activity?.close()
        }
    }

    /** Authenticate the kernel's loopback SOCKS, then validate the destination's TLS certificate. */
    private fun tlsGetViaSocks(spec: NetworkProxy.Spec, stage: (String) -> Unit): Int {
        val host = "www.gstatic.com"
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", spec.port), 3_000)
            socket.soTimeout = 20_000
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            stage("socks_greeting")
            out.write(byteArrayOf(5, 1, 2)); out.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "SOCKS greeting" }
            val user = requireNotNull(spec.username).toByteArray()
            val pass = requireNotNull(spec.password).toByteArray()
            stage("socks_auth")
            out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); out.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS authentication" }
            val address = host.toByteArray()
            stage("socks_connect")
            out.write(byteArrayOf(5, 1, 0, 3, address.size.toByte()) + address + byteArrayOf(1, -69)); out.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "SOCKS connect" }
            input.readUnsignedByte()
            val addressSize = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("SOCKS address") }
            input.readFully(ByteArray(addressSize + 2))
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 443, true) as SSLSocket).use { tls ->
                stage("tls_handshake")
                tls.soTimeout = 20_000
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                stage("http_response")
                tls.outputStream.write("GET /generate_204 HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
                val firstLine = tls.inputStream.bufferedReader().readLine().orEmpty().take(128)
                return firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            }
        }
    }
}

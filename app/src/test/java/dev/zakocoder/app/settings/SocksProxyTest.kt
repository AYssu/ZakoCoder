package dev.zakocoder.app.settings

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * End-to-end proof of the SOCKS5 contract without a device or external network.
 *
 * A minimal RFC 1928 SOCKS5 server fixture negotiates "no auth", answers one CONNECT, records the
 * ATYP/address/port the client actually sent and relays bytes to a local HTTP fixture. The OkHttp
 * client is configured through [NetworkProxy.javaProxy], i.e. exactly the mapping the app uses.
 *
 * The ATYP is deliberately not asserted to one platform-specific value: the JDK may send a domain
 * name (ATYP=3, proxy-side DNS) or an already resolved IP (ATYP=1). Android's probe observed
 * ATYP=3 for application requests; what matters here is that the request reaches the proxy and
 * that a broken proxy never degrades into a silent direct connection.
 */
class SocksProxyTest {
    @Test(timeout = 5_000)
    fun forwardsApplicationRequestThroughSocks5AndRecordsTarget() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port).use { socks ->
                val response = socksClient(socks.port).newCall(
                    Request.Builder().url("http://127.0.0.1:${http.port}/socks-evidence").build()
                ).execute()
                response.use {
                    assertEquals(200, it.code)
                    assertEquals("hello", it.body?.string())
                }

                val connect = awaitObserved(socks)
                assertTrue(
                    "SOCKS5 ATYP must be 1 (IPv4) or 3 (domain), was ${connect.atyp}",
                    connect.atyp == 1 || connect.atyp == 3,
                )
                assertEquals("CONNECT must target the HTTP fixture port", http.port, connect.port)
                assertEquals("HTTP fixture must be reached through the tunnel", 1, http.accepted.get())
                println(
                    "SOCKS5 fixture observed ATYP=${connect.atyp} (${atypName(connect.atyp)}) " +
                        "target=${connect.host}:${connect.port} (HTTP fixture port=${http.port})",
                )
            }
        }
    }

    @Test(timeout = 5_000)
    fun refusesDirectFallbackWhenProxyPortIsClosed() {
        HttpFixture().use { http ->
            val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val failure = runCatching {
                socksClient(closedPort).newCall(
                    Request.Builder().url("http://127.0.0.1:${http.port}/must-not-be-direct").build()
                ).execute().close()
            }.exceptionOrNull()

            assertTrue("a closed proxy port must fail the request, got $failure", failure is IOException)
            assertEquals("no connection may fall back to the direct HTTP fixture", 0, http.accepted.get())
        }
    }

    @Test(timeout = 5_000)
    fun refusesDirectFallbackWhenSocksHandshakeIsRejected() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, SocksMode.REJECT_METHODS).use { socks ->
                val failure = runCatching {
                    socksClient(socks.port).newCall(
                        Request.Builder().url("http://127.0.0.1:${http.port}/must-not-be-direct").build()
                    ).execute().close()
                }.exceptionOrNull()

                assertTrue(
                    "the rejected SOCKS5 handshake must fail the request, got $failure",
                    failure is IOException,
                )
                assertEquals("no connection may fall back to the direct HTTP fixture", 0, http.accepted.get())
            }
        }
    }

    private fun socksClient(proxyPort: Int): OkHttpClient {
        val proxy = NetworkProxy.javaProxy("socks5://127.0.0.1:$proxyPort")
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        return OkHttpClient.Builder()
            .proxy(requireNotNull(proxy))
            .retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun awaitObserved(fixture: Socks5Fixture): ObservedConnect {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val snapshot = fixture.observed.toList()
            if (snapshot.isNotEmpty()) return snapshot.first()
            Thread.sleep(10)
        }
        throw AssertionError("SOCKS5 fixture recorded no CONNECT request")
    }

    private fun atypName(atyp: Int): String = when (atyp) {
        1 -> "IPv4"
        3 -> "domain name"
        4 -> "IPv6"
        else -> "unknown"
    }

    private data class ObservedConnect(val atyp: Int, val host: String, val port: Int)

    private enum class SocksMode { TUNNEL, REJECT_METHODS }

    /** Local HTTP fixture: answers every request with `200 hello` and counts accepted sockets. */
    private class HttpFixture : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val accepted = AtomicInteger()
        @Volatile private var closed = false

        init {
            thread(isDaemon = true, name = "http-fixture-accept") {
                while (!closed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    accepted.incrementAndGet()
                    thread(isDaemon = true, name = "http-fixture-conn") { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.use {
                it.soTimeout = 2_000
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = "hello".toByteArray()
                it.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body)
                    flush()
                }
            }
        }

        override fun close() {
            closed = true
            server.close()
        }
    }

    /**
     * Minimal SOCKS5 server fixture (RFC 1928): method negotiation -> optional rejection ->
     * CONNECT parse/record -> reply -> byte relay to the fixed target.
     */
    private class Socks5Fixture(
        private val targetHost: String,
        private val targetPort: Int,
        private val mode: SocksMode = SocksMode.TUNNEL,
    ) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val observed: MutableList<ObservedConnect> = Collections.synchronizedList(mutableListOf())
        @Volatile private var closed = false

        init {
            thread(isDaemon = true, name = "socks5-fixture-accept") {
                while (!closed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true, name = "socks5-fixture-conn") { handle(socket) }
                }
            }
        }

        private fun handle(socket: Socket) {
            var upstream: Socket? = null
            try {
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // Method negotiation: version, method count, offered methods.
                val version = input.readRequired("version")
                input.readFully(input.readRequired("method count"))
                if (version != 5) return
                if (mode == SocksMode.REJECT_METHODS) {
                    // 0xFF = "no acceptable methods"; the client must abort instead of going direct.
                    output.write(byteArrayOf(5, 0xFF.toByte()))
                    output.flush()
                    return
                }
                output.write(byteArrayOf(5, 0)) // 0x00 = no authentication required
                output.flush()

                // CONNECT request: version, command, reserved, address type, address, port.
                val requestVersion = input.readRequired("request version")
                val command = input.readRequired("command")
                input.readRequired("reserved")
                if (requestVersion != 5 || command != 1) return
                val atyp = input.readRequired("address type")
                val host = when (atyp) {
                    1 -> InetAddress.getByAddress(input.readFully(4)).hostAddress
                    3 -> String(input.readFully(input.readRequired("domain length")), Charsets.US_ASCII)
                    4 -> InetAddress.getByAddress(input.readFully(16)).hostAddress
                    else -> return
                }
                val requestedPort = (input.readRequired("port high") shl 8) or input.readRequired("port low")
                observed += ObservedConnect(atyp, host, requestedPort)

                val target = try {
                    Socket().apply {
                        connect(InetSocketAddress(targetHost, targetPort), 2_000)
                        soTimeout = 2_000
                    }
                } catch (_: IOException) {
                    // 0x05 = connection refused: the client must surface the failure, not bypass us.
                    output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    return
                }
                upstream = target
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // success, bound 0.0.0.0:0
                output.flush()

                val toTarget = thread(isDaemon = true, name = "socks5-fixture-up") {
                    runCatching { relay(input, target.getOutputStream()) }
                    runCatching { target.shutdownOutput() }
                }
                runCatching { relay(target.getInputStream(), output) }
                toTarget.join(1_000)
            } catch (_: IOException) {
                // Client hung up or fixture closed mid-flight; assertions cover the observable side.
            } finally {
                runCatching { upstream?.close() }
                runCatching { socket.close() }
            }
        }

        private fun relay(from: InputStream, to: OutputStream) {
            val buffer = ByteArray(8192)
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        }

        override fun close() {
            closed = true
            server.close()
        }
    }
}

private fun InputStream.readRequired(what: String): Int {
    val value = read()
    if (value < 0) throw IOException("EOF while reading SOCKS5 $what")
    return value
}

private fun InputStream.readFully(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(buffer, offset, length - offset)
        if (count < 0) throw IOException("EOF while reading SOCKS5 payload")
        offset += count
    }
    return buffer
}

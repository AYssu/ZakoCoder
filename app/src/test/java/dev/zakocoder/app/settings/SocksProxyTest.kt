package dev.zakocoder.app.settings

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
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
 * A minimal RFC 1928 / RFC 1929 SOCKS5 server fixture negotiates "no auth" or username/password,
 * answers one CONNECT, records the ATYP/address/port the client actually sent and relays bytes to a
 * local HTTP fixture. The OkHttp client is configured through [NetworkProxy.javaProxy] and
 * [NetworkProxy.configureSocksAuthentication], i.e. exactly the wiring the app uses, so the JDK
 * global [Authenticator] is exercised for real.
 *
 * The unauthenticated ATYP is deliberately not asserted to one platform-specific value: the JDK may
 * send a domain name (ATYP=3, proxy-side DNS) or an already resolved IP (ATYP=1). Android's probe
 * observed ATYP=3 for application requests; the authenticated case asserts ATYP=3 because that is
 * what this JVM/OkHttp combination was observed to send. What matters in every failure case is that
 * the request reaches the proxy and that a broken proxy never degrades into a silent direct
 * connection.
 */
class SocksProxyTest {
    @Test(timeout = 5_000)
    fun forwardsApplicationRequestThroughSocks5AndRecordsTarget() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port).use { socks ->
                val response = socksClient("socks5://127.0.0.1:${socks.port}").newCall(
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

    @Test(timeout = 10_000)
    fun forwardsThroughRfc1929UsernamePasswordAuthentication() {
        val username = "user@corp"
        val password = "p@ss:word"
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = username to password).use { socks ->
                withGlobalAuthenticator {
                    // The URL is exactly how the UI enters percent-encoded credentials; parsing,
                    // encoding and the global Authenticator are all part of the production path.
                    val raw = "socks5://user%40corp:p%40ss%3Aword@127.0.0.1:${socks.port}"
                    val response = socksClient(raw).newCall(
                        Request.Builder().url("http://127.0.0.1:${http.port}/authenticated-socks-evidence").build()
                    ).execute()
                    response.use {
                        assertEquals(200, it.code)
                        assertEquals("hello", it.body?.string())
                    }
                }

                val connect = awaitObserved(socks)
                assertEquals("authenticated CONNECT must keep ATYP=3 (domain name)", 3, connect.atyp)
                assertEquals("CONNECT must target the HTTP fixture port", http.port, connect.port)
                assertEquals("HTTP fixture must be reached through the authenticated tunnel", 1, http.accepted.get())
                val attempt = socks.authAttempts.single()
                assertEquals("RFC1929 username must be decoded from the percent-encoded URL", username, attempt.username)
                assertEquals("RFC1929 password must be decoded from the percent-encoded URL", password, attempt.password)
                assertTrue("fixture must have accepted the credential pair", attempt.accepted)
                println(
                    "RFC1929 fixture accepted user=$username (password redacted), ATYP=${connect.atyp} " +
                        "target=${connect.host}:${connect.port}",
                )
            }
        }
    }

    @Test(timeout = 10_000)
    fun rejectsWrongCredentialsWithoutDirectFallback() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "user" to "correct").use { socks ->
                withGlobalAuthenticator {
                    val failure = runCatching {
                        socksClient("socks5://user:wrong@127.0.0.1:${socks.port}").newCall(
                            Request.Builder().url("http://127.0.0.1:${http.port}/must-not-be-direct").build()
                        ).execute().close()
                    }.exceptionOrNull()

                    assertTrue("wrong SOCKS5 credentials must fail the request, got $failure", failure is IOException)
                }
                assertEquals("no connection may fall back to the direct HTTP fixture", 0, http.accepted.get())
                assertTrue("the fixture must have recorded an authentication attempt", socks.authAttempts.isNotEmpty())
                assertTrue("the wrong credential pair must never be accepted", socks.authAttempts.none { it.accepted })
                assertTrue("CONNECT must not be reached after a rejected authentication", socks.observed.isEmpty())
            }
        }
    }

    @Test(timeout = 10_000)
    fun refusesWhenServerRequiresAuthenticationButClientHasNoCredentials() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "user" to "correct").use { socks ->
                withGlobalAuthenticator {
                    // No stored credentials: the global authenticator must not invent a pair.
                    NetworkProxy.configureSocksAuthentication(null)
                    val failure = runCatching {
                        socksClient("socks5://127.0.0.1:${socks.port}").newCall(
                            Request.Builder().url("http://127.0.0.1:${http.port}/must-not-be-direct").build()
                        ).execute().close()
                    }.exceptionOrNull()

                    assertTrue("a required but missing credential pair must fail, got $failure", failure is IOException)
                }
                assertEquals("no connection may fall back to the direct HTTP fixture", 0, http.accepted.get())
                assertTrue("the fixture must have read an authentication frame", socks.authAttempts.isNotEmpty())
                assertTrue("no unauthenticated pair may be accepted", socks.authAttempts.none { it.accepted })
                assertTrue("CONNECT must not be reached without authentication", socks.observed.isEmpty())
            }
        }
    }

    @Test(timeout = 5_000)
    fun refusesDirectFallbackWhenProxyPortIsClosed() {
        HttpFixture().use { http ->
            val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val failure = runCatching {
                socksClient("socks5://127.0.0.1:$closedPort").newCall(
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
                    socksClient("socks5://127.0.0.1:${socks.port}").newCall(
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

    /** Production wiring: parse the URL, publish the pair to the JDK authenticator, build the client. */
    private fun socksClient(raw: String): OkHttpClient {
        NetworkProxy.configureSocksAuthentication(NetworkProxy.credentials(raw))
        val proxy = NetworkProxy.javaProxy(raw)
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        return OkHttpClient.Builder()
            .proxy(requireNotNull(proxy))
            .retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** Restores the process-wide default authenticator so no other test can be polluted. */
    private fun <T> withGlobalAuthenticator(block: () -> T): T {
        val previous = currentDefaultAuthenticator()
        return try {
            block()
        } finally {
            NetworkProxy.configureSocksAuthentication(null)
            Authenticator.setDefault(previous)
        }
    }

    /**
     * The Android compile-time stub of [Authenticator] does not expose `getDefault()`, while the
     * JVM that actually runs unit tests does. Reflection keeps `finally` faithful without a
     * production API just for tests.
     */
    private fun currentDefaultAuthenticator(): Authenticator? = try {
        Authenticator::class.java.getMethod("getDefault").invoke(null) as? Authenticator
    } catch (_: Exception) {
        null
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
    private data class ObservedAuth(val username: String, val password: String, val accepted: Boolean)

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
     * Minimal SOCKS5 server fixture (RFC 1928 + RFC 1929): method negotiation -> optional
     * username/password auth -> CONNECT parse/record -> reply -> byte relay to the fixed target.
     */
    private class Socks5Fixture(
        private val targetHost: String,
        private val targetPort: Int,
        private val mode: SocksMode = SocksMode.TUNNEL,
        private val expected: Pair<String, String>? = null,
    ) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val observed: MutableList<ObservedConnect> = Collections.synchronizedList(mutableListOf())
        val authAttempts: MutableList<ObservedAuth> = Collections.synchronizedList(mutableListOf())
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
                val offered = input.readFully(input.readRequired("method count"))
                if (version != 5) return
                if (mode == SocksMode.REJECT_METHODS) {
                    // 0xFF = "no acceptable methods"; the client must abort instead of going direct.
                    output.write(byteArrayOf(5, 0xFF.toByte()))
                    output.flush()
                    return
                }
                if (expected == null) {
                    output.write(byteArrayOf(5, 0)) // 0x00 = no authentication required
                    output.flush()
                } else {
                    // 0x02 = username/password. The JDK client always offers methods 0 and 2.
                    check(2 in offered.toList()) { "client did not offer username/password" }
                    output.write(byteArrayOf(5, 2))
                    output.flush()
                    // RFC 1929: version, ulen, uname, plen, passwd.
                    val authVersion = input.readRequired("auth version")
                    val username = String(input.readFully(input.readRequired("username length")), Charsets.ISO_8859_1)
                    val password = String(input.readFully(input.readRequired("password length")), Charsets.ISO_8859_1)
                    val accepted = authVersion == 1 && username == expected.first && password == expected.second
                    authAttempts += ObservedAuth(username, password, accepted)
                    // status 0x00 = success, anything else = failure; RFC 1929 requires closing on failure.
                    output.write(byteArrayOf(5, if (accepted) 0 else 1))
                    output.flush()
                    if (!accepted) return
                }

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

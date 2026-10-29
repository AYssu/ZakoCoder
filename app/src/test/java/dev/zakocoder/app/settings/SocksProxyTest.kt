package dev.zakocoder.app.settings

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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

    /**
     * Endpoint-binding regression on the real JDK callback: records what the JDK passes to
     * [Authenticator.getPasswordAuthentication] for (a) OkHttp over SOCKS5, (b) HttpURLConnection
     * over SOCKS5, (c) HttpURLConnection through an HTTP proxy that answers 407.
     *
     * Probe-confirmed values on JDK 17 (see the printed `AUTH_CALLBACK`/`HTTP_PROXY_*` lines and
     * `.local/dev/evidence-socks-auth-binding.md`):
     * - SOCKS5: `requestorType=SERVER`, `requestingProtocol=SOCKS5`, `requestingHost`/`requestingPort`
     *   are the **proxy** endpoint (not the target);
     * - HTTP proxy challenge: `requestorType=PROXY`, `requestingProtocol=http`, host/port are the
     *   HTTP proxy endpoint.
     * The production decision function must accept every real SOCKS5 callback of the bound endpoint
     * and reject the HTTP proxy callback (and any callback for another endpoint).
     */
    @Test(timeout = 20_000)
    fun jdkAuthenticationCallbacksAreBoundToTheConfiguredSocks5Endpoint() {
        val socksCallbacks = Collections.synchronizedList(mutableListOf<AuthCallback>())
        val httpProxyCallbacks = Collections.synchronizedList(mutableListOf<AuthCallback>())
        val proxyHeaders = Collections.synchronizedList(mutableListOf<String?>())
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "probe-user" to "probe-pass").use { socks ->
                HttpProxyFixture(proxyHeaders).use { proxy ->
                    withGlobalAuthenticator {
                        Authenticator.setDefault(object : Authenticator() {
                            override fun getPasswordAuthentication(): PasswordAuthentication? {
                                val callback = AuthCallback(
                                    requestorType = requestorType,
                                    requestingHost = requestingHost,
                                    requestingPort = requestingPort,
                                    requestingProtocol = requestingProtocol,
                                    requestingScheme = requestingScheme,
                                    requestingURL = requestingURL,
                                    requestingPrompt = requestingPrompt,
                                )
                                if (requestingProtocol?.equals("SOCKS5", ignoreCase = true) == true) {
                                    socksCallbacks += callback
                                } else {
                                    httpProxyCallbacks += callback
                                }
                                return null
                            }
                        })
                        // (a) OkHttp over SOCKS5 (no NetworkProxy binding installed: capture only).
                        runCatching {
                            proxyOnlyClient("socks5://127.0.0.1:${socks.port}").newCall(
                                Request.Builder().url("http://127.0.0.1:${http.port}/probe-okhttp").build()
                            ).execute().close()
                        }
                        // (b) HttpURLConnection over the same SOCKS5 proxy.
                        runCatching { httpGet("http://127.0.0.1:${http.port}/probe-huc", NetworkProxy.javaProxy("socks5://127.0.0.1:${socks.port}")) }
                        // (c) HttpURLConnection through an HTTP proxy that requires authentication.
                        runCatching {
                            httpGet(
                                "http://127.0.0.2/probe-http-proxy",
                                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)),
                            )
                        }

                        assertEquals(
                            "OkHttp and HttpURLConnection must each authenticate through the JDK callback",
                            2,
                            socksCallbacks.size,
                        )
                        assertEquals("one HTTP proxy authentication challenge", 1, httpProxyCallbacks.size)
                        socksCallbacks.forEach { callback ->
                            assertEquals(Authenticator.RequestorType.SERVER, callback.requestorType)
                            assertEquals("SOCKS5", callback.requestingProtocol)
                            assertEquals("127.0.0.1", callback.requestingHost)
                            assertEquals(socks.port, callback.requestingPort)
                        }
                        httpProxyCallbacks.forEach { callback ->
                            assertEquals(Authenticator.RequestorType.PROXY, callback.requestorType)
                            assertEquals("http", callback.requestingProtocol)
                            assertEquals("127.0.0.1", callback.requestingHost)
                            assertEquals(proxy.port, callback.requestingPort)
                        }

                        // Bind the production authenticator to the SOCKS5 endpoint and replay the
                        // real callback values through the pure decision function.
                        NetworkProxy.configureSocksAuthentication("socks5://probe-user:probe-pass@127.0.0.1:${socks.port}")
                        socksCallbacks.forEach { callback ->
                            assertEquals(
                                "the real SOCKS5 callback must match the bound endpoint",
                                "probe-user",
                                NetworkProxy.credentialFor(
                                    callback.requestingHost,
                                    callback.requestingPort,
                                    callback.requestingProtocol,
                                    callback.requestorType,
                                )?.username,
                            )
                        }
                        httpProxyCallbacks.forEach { callback ->
                            assertNull(
                                "an HTTP proxy challenge must never match a SOCKS5 binding",
                                NetworkProxy.credentialFor(
                                    callback.requestingHost,
                                    callback.requestingPort,
                                    callback.requestingProtocol,
                                    callback.requestorType,
                                ),
                            )
                        }
                        socksCallbacks.forEach { println("AUTH_CALLBACK $it") }
                        httpProxyCallbacks.forEach { println("HTTP_PROXY_AUTH_CALLBACK $it") }
                        proxyHeaders.forEach { println("HTTP_PROXY_AUTHORIZATION header=$it") }
                    }
                }
            }
        }
    }

    /**
     * Regression for maintainer reproduction 1: a SOCKS5 proxy on another endpoint must not receive
     * the pair bound to the configured endpoint, even when its authentication challenge arrives.
     */
    @Test(timeout = 10_000)
    fun doesNotSendCredentialsToAnUnrelatedSocks5Endpoint() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "userA" to "passA").use { configured ->
                Socks5Fixture("127.0.0.1", http.port, expected = "userB" to "passB").use { unrelated ->
                    withGlobalAuthenticator {
                        NetworkProxy.configureSocksAuthentication("socks5://userA:passA@127.0.0.1:${configured.port}")
                        val failure = runCatching {
                            proxyOnlyClient("socks5://127.0.0.1:${unrelated.port}").newCall(
                                Request.Builder().url("http://127.0.0.1:${http.port}/must-not-leak").build()
                            ).execute().close()
                        }.exceptionOrNull()

                        assertTrue("the unrelated endpoint must fail authentication, got $failure", failure is IOException)
                        assertTrue("the unrelated endpoint must have challenged authentication", unrelated.authAttempts.isNotEmpty())
                        assertTrue("the pair bound to another endpoint must never be accepted here",
                            unrelated.authAttempts.none { it.accepted })
                        assertTrue("the configured pair must never be sent to the unrelated endpoint",
                            unrelated.authAttempts.none { it.username == "userA" && it.password == "passA" })
                        assertTrue("the configured endpoint must not have been contacted", configured.authAttempts.isEmpty())
                        assertEquals("no connection may fall back to the direct HTTP fixture", 0, http.accepted.get())
                    }
                }
            }
        }
    }

    /**
     * Regression for maintainer reproduction 2: an HTTP proxy authentication challenge on another
     * address must not receive the bound SOCKS5 pair (the old code returned it for RequestorType.PROXY).
     */
    @Test(timeout = 10_000)
    fun doesNotSendCredentialsToAnHttpProxyAuthenticationChallenge() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "userA" to "passA").use { socks ->
                val proxyHeaders = Collections.synchronizedList(mutableListOf<String?>())
                HttpProxyFixture(proxyHeaders).use { proxy ->
                    withGlobalAuthenticator {
                        NetworkProxy.configureSocksAuthentication("socks5://userA:passA@127.0.0.1:${socks.port}")
                        val failure = runCatching {
                            httpGet(
                                "http://127.0.0.2/proxy-auth-challenge",
                                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)),
                            )
                        }.exceptionOrNull()

                        assertTrue("the HTTP proxy challenge must not succeed, got $failure", failure is IOException)
                        assertTrue("no Proxy-Authorization header may carry the SOCKS5 pair",
                            proxyHeaders.none { it != null })
                        assertEquals("the SOCKS5 endpoint must not have been used", 0, socks.authAttempts.size)
                        assertEquals(0, http.accepted.get())
                    }
                }
            }
        }
    }

    /**
     * A real HttpURLConnection SOCKS5 authentication (the runtime image download path) still works
     * through the endpoint-bound authenticator.
     */
    @Test(timeout = 10_000)
    fun authenticatesHttpUrlConnectionThroughSocks5() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "user@corp" to "p@ss:word").use { socks ->
                withGlobalAuthenticator {
                    val raw = "socks5://user%40corp:p%40ss%3Aword@127.0.0.1:${socks.port}"
                    NetworkProxy.configureSocksAuthentication(raw)
                    val codeAndBody = httpGet("http://127.0.0.1:${http.port}/huc-auth", NetworkProxy.javaProxy(raw))
                    assertEquals(200, codeAndBody.first)
                    assertEquals("hello", codeAndBody.second)
                    assertEquals(1, socks.authAttempts.count { it.accepted })
                    assertEquals(1, http.accepted.get())
                }
            }
        }
    }

    /**
     * Switching the configured endpoint mid-process fails closed for the old endpoint: the new pair
     * is never handed to it, while the new endpoint keeps working; disabling clears everything.
     */
    @Test(timeout = 10_000)
    fun switchingAndDisablingFailClosedForTheOldEndpoint() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "userA" to "passA").use { a ->
                Socks5Fixture("127.0.0.1", http.port, expected = "userB" to "passB").use { b ->
                    withGlobalAuthenticator {
                        NetworkProxy.configureSocksAuthentication("socks5://userA:passA@127.0.0.1:${a.port}")
                        assertEquals(200, socksClient("socks5://userA:passA@127.0.0.1:${a.port}").newCall(
                            Request.Builder().url("http://127.0.0.1:${http.port}/before-switch").build()
                        ).execute().use { it.code })
                        assertEquals(1, a.authAttempts.count { it.accepted })

                        // Switch A -> B. A connection to the old endpoint must not get the new pair.
                        NetworkProxy.configureSocksAuthentication("socks5://userB:passB@127.0.0.1:${b.port}")
                        val failure = runCatching {
                            proxyOnlyClient("socks5://127.0.0.1:${a.port}").newCall(
                                Request.Builder().url("http://127.0.0.1:${http.port}/after-switch-old-endpoint").build()
                            ).execute().close()
                        }.exceptionOrNull()
                        assertTrue("the old endpoint must fail after the switch, got $failure", failure is IOException)
                        assertTrue("the new pair must never be accepted by the old endpoint",
                            a.authAttempts.none { it.username == "userB" || it.password == "passB" })

                        // The new endpoint works with the new pair.
                        assertEquals(200, socksClient("socks5://userB:passB@127.0.0.1:${b.port}").newCall(
                            Request.Builder().url("http://127.0.0.1:${http.port}/after-switch-new-endpoint").build()
                        ).execute().use { it.code })
                        assertEquals(1, b.authAttempts.count { it.accepted })

                        // Disabling the proxy clears the binding: a new connection to B gets no pair.
                        NetworkProxy.configureSocksAuthentication(null)
                        val disabledFailure = runCatching {
                            proxyOnlyClient("socks5://127.0.0.1:${b.port}").newCall(
                                Request.Builder().url("http://127.0.0.1:${http.port}/after-disable").build()
                            ).execute().close()
                        }.exceptionOrNull()
                        assertTrue("a disabled proxy must not authenticate, got $disabledFailure", disabledFailure is IOException)
                        assertEquals("the accepted authentication count must not grow", 1, b.authAttempts.count { it.accepted })
                    }
                }
            }
        }
    }

    /**
     * In-flight isolation: a connection that already completed SOCKS5 authentication keeps working
     * after the binding is switched and then disabled. The JDK authenticator only runs while
     * connecting, so an established tunnel is never affected by later settings changes.
     */
    @Test(timeout = 10_000)
    fun establishedConnectionIsUnaffectedByLaterBindingSwitch() {
        HttpFixture().use { http ->
            Socks5Fixture("127.0.0.1", http.port, expected = "userA" to "passA").use { socks ->
                withGlobalAuthenticator {
                    NetworkProxy.configureSocksAuthentication("socks5://userA:passA@127.0.0.1:${socks.port}")
                    val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks.port)))
                    socket.use {
                        it.connect(InetSocketAddress("127.0.0.1", http.port), 2_000)
                        it.soTimeout = 2_000
                        // Switch to another endpoint and then disable it while this tunnel is open.
                        NetworkProxy.configureSocksAuthentication("socks5://userB:passB@127.0.0.1:1")
                        NetworkProxy.configureSocksAuthentication(null)

                        val output = it.getOutputStream()
                        output.write(
                            "GET /in-flight HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        output.flush()
                        val response = it.getInputStream().use { input -> String(input.readBytes(), Charsets.ISO_8859_1) }
                        assertTrue("the established tunnel must keep working after switch+disable", response.contains("200 OK"))
                        assertTrue(response.contains("hello"))
                    }
                    assertEquals(1, socks.authAttempts.count { it.accepted })
                    assertEquals(1, http.accepted.get())
                }
            }
        }
    }

    /** Production wiring: bind the endpoint-bound authenticator, then build the client. */
    private fun socksClient(raw: String): OkHttpClient {
        NetworkProxy.configureSocksAuthentication(raw)
        return proxyOnlyClient(raw)
    }

    /** Builds a client with the given SOCKS5 proxy but without touching the global binding. */
    private fun proxyOnlyClient(raw: String): OkHttpClient {
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

    /** One HttpURLConnection request (optionally through a proxy), returning status and body. */
    private fun httpGet(url: String, proxy: Proxy? = null): Pair<Int, String> {
        val connection = (if (proxy == null) URL(url).openConnection() else URL(url).openConnection(proxy)) as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        return try {
            val code = connection.responseCode
            val body = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            code to body
        } finally {
            connection.disconnect()
        }
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
    private data class AuthCallback(
        val requestorType: Authenticator.RequestorType?,
        val requestingHost: String?,
        val requestingPort: Int,
        val requestingProtocol: String?,
        val requestingScheme: String?,
        val requestingURL: URL?,
        val requestingPrompt: String?,
    )

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
     * Minimal HTTP proxy fixture that always answers `407 Proxy Authentication Required` and records
     * the `Proxy-Authorization` request header (if any). Used to prove that a SOCKS5 endpoint
     * binding never leaks credentials to an HTTP proxy challenge on another address.
     */
    private class HttpProxyFixture(private val proxyHeaders: MutableList<String?>) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        @Volatile private var closed = false

        init {
            thread(isDaemon = true, name = "http-proxy-fixture-accept") {
                while (!closed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true, name = "http-proxy-fixture-conn") { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.use {
                it.soTimeout = 2_000
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var authorization: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) authorization = line
                }
                proxyHeaders += authorization
                it.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"probe\"\r\nContent-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
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

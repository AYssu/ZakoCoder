package dev.zakocoder.app

import dev.zakocoder.app.ai.AiConfig
import dev.zakocoder.app.ai.AiFailure
import dev.zakocoder.app.ai.AiRequestLog
import dev.zakocoder.app.ai.AiTransportException
import dev.zakocoder.app.ai.ChatTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** Exercises the production chat entry point with local, generated TLS certificates only. */
class HttpTlsCompatibilityDeviceTest {
    @Test fun defaultTransportRejectsUntrustedSelfSignedCertificate() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost").addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        withServer(certificates) { server ->
            assertTlsRejected(ChatTransport(), server)
        }
    }

    @Test fun explicitlyTrustedCaDoesNotDisableHostnameVerification() = runBlocking {
        val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Fixture CA").build()
        val certificate = HeldCertificate.Builder().signedBy(ca)
            .commonName("wrong.example").addSubjectAlternativeName("wrong.example").build()
        val certificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate, ca.certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .retryOnConnectionFailure(false).build()
        try {
            withServer(certificates) { server ->
                assertTlsRejected(ChatTransport(client), server)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun withServer(
        certificates: HandshakeCertificates,
        test: suspend (MockWebServer) -> Unit
    ) {
        val server = MockWebServer()
        server.useHttps(certificates.sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // If TLS verification regresses, complete the request so the test fails immediately.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        ))
        try {
            test(server)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun assertTlsRejected(transport: ChatTransport, server: MockWebServer) {
        var log: AiRequestLog? = null
        var text = ""
        val endpoint = "https://localhost:${server.port}/v1"
        try {
            withTimeout(20_000) {
                transport.request(
                    AiConfig(endpoint, "tls-fixture"),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "local test")),
                    JSONArray(),
                    observe = { log = it }
                ) { text += it }
            }
            fail("TLS verification must reject the connection")
        } catch (error: AiTransportException) {
            assertEquals(AiFailure.Network, error.kind)
        }
        assertEquals("tls_failed", log?.diagnostic)
        assertTrue(log?.complete == true)
        assertEquals("", text)
        assertEquals("TLS must fail before sending an HTTP request", 0, server.requestCount)
    }
}

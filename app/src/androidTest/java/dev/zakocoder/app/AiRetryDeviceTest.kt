package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/** Isolated TLS protocol fixtures; no provider credentials, user files or tool execution. */
class AiRetryDeviceTest {
    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val invocations = AtomicInteger()
        val waits = mutableListOf<Long>()
        val logs = linkedMapOf<String, AiRequestLog>()
        val retries = mutableListOf<AiRetryEvent>()
        var networkFailures = 0
        var tlsFailure = false
        var wait: suspend (Long) -> Unit = { waits.add(it) }
        val transport: ChatTransport
        init {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(certificates.sslSocketFactory(), false); server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .addInterceptor { chain ->
                    val number = invocations.incrementAndGet()
                    if (tlsFailure) throw SSLHandshakeException("synthetic certificate failure")
                    if (number <= networkFailures) throw SocketException("synthetic connection reset")
                    chain.proceed(chain.request())
                }.callTimeout(10, TimeUnit.SECONDS).build()
            transport = ChatTransport(client, retryDelay = { wait(it) }, maxAttempts = 3)
        }
        suspend fun request(output: StringBuilder = StringBuilder(), reset: () -> Unit = {}, reasoning: (String) -> Unit = {},
            drafts: (List<AiCallDraft>) -> Unit = {}): AiReply = transport.request(
            AiConfig(server.url("/v1").toString(), "fixture-model"),
            JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect without executing tools")), JSONArray(),
            toolDelta = drafts, observe = { logs[it.id] = it }, reasoningDelta = reasoning,
            onRetry = { retries.add(it) }, onRetryReset = { reset() }) { output.append(it) }
        override fun close() { server.shutdown() }
    }
    private fun event(delta: JSONObject, finish: String? = null): String {
        val choice = JSONObject().put("delta", delta)
        if (finish != null) choice.put("finish_reason", finish)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }
    private fun answer(text: String, done: Boolean = true): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream").setBody(event(JSONObject().put("content", text)) +
            event(JSONObject(), "stop") + if (done) "data: [DONE]\n\n" else "")
    private suspend fun failure(f: Fixture): AiTransportException = try { f.request(); error("Expected failure") }
        catch (error: AiTransportException) { error }

    @Test(timeout = 30000) fun transientHttpAndNetworkFailuresRetryIdenticalSnapshotsAndKeepAttemptLogs() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(503)); f.server.enqueue(answer("ready"))
            assertEquals("ready", f.request().text)
            val first = f.server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = f.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertEquals(listOf(1, 2), f.logs.values.map { it.attempt })
            assertEquals(1, f.logs.values.map { it.retryGroup }.distinct().size)
            assertEquals(AiFailure.Http, f.logs.values.first().failure)
            assertNull(f.logs.values.last().failure); assertTrue(f.logs.values.all { it.complete && it.maxAttempts == 3 })
            assertTrue(f.waits.single() in 1000L..1250L)
            f.logs.values.forEach { assertEquals(it, AiRequestLog.decode(it.encode())) }
        }
        Fixture().use { f ->
            f.networkFailures = 1; f.server.enqueue(answer("connected"))
            assertEquals("connected", f.request().text)
            assertEquals(2, f.invocations.get()); assertEquals(2, f.logs.size)
            assertEquals("network_io_failed", f.logs.values.first().diagnostic)
        }
    }

    @Test(timeout = 30000) fun retryAfterIsRespectedAndExcessiveWaitDoesNotRetry() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7")); f.server.enqueue(answer("ready"))
            assertEquals("ready", f.request().text); assertEquals(7000L, f.waits.single())
            assertEquals(2, f.retries.single().attempt); assertEquals(429, f.retries.single().status)
        }
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            assertEquals(429, failure(f).status); assertTrue(f.waits.isEmpty()); assertEquals(1, f.server.requestCount)
        }
    }

    @Test(timeout = 30000) fun interruptedPartialReasoningAndToolDraftAreReplacedOnlyAtFreshOutput() = runBlocking {
        Fixture().use { f ->
            val old = event(JSONObject().put("reasoning_content", "old reasoning")) + event(JSONObject().put("content", "old partial")) +
                event(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "old-call")
                    .put("function", JSONObject().put("name", "write_file").put("arguments", "{\"path\":")))))
            f.server.enqueue(MockResponse().setBody(old)); f.server.enqueue(MockResponse().setResponseCode(503)); f.server.enqueue(answer("fresh answer"))
            val output = StringBuilder(); val reasoning = StringBuilder(); var resets = 0; var lastDrafts = emptyList<AiCallDraft>()
            f.wait = { assertEquals("old partial", output.toString()); assertEquals(0, resets) }
            val reply = f.request(output, reset = { output.clear(); reasoning.clear(); lastDrafts = emptyList(); resets++ },
                reasoning = { reasoning.append(it) }, drafts = { lastDrafts = it })
            assertEquals("fresh answer", reply.text); assertTrue(reply.calls.isEmpty())
            assertEquals("fresh answer", output.toString()); assertEquals("", reasoning.toString()); assertTrue(lastDrafts.isEmpty())
            assertEquals(1, resets); assertEquals(3, f.logs.size)
            assertTrue(f.logs.values.first().argumentChars > 0)
        }
    }

    @Test(timeout = 30000) fun exhaustedAttemptsPreservePartialAndSuccessfulEmptyReplacementClearsIt() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(answer("partial", done = false)); repeat(2) { f.server.enqueue(MockResponse().setResponseCode(503)) }
            val output = StringBuilder(); var resets = 0
            try { f.request(output, reset = { output.clear(); resets++ }); fail("Expected failure") }
            catch (error: AiTransportException) { assertEquals("http_status_503", error.diagnostic) }
            assertEquals("partial", output.toString()); assertEquals(0, resets)
            assertEquals(3, f.logs.size); assertEquals(2, f.retries.size)
            assertTrue(f.logs.values.all { it.complete && it.failure != null })
        }
        Fixture().use { f ->
            f.server.enqueue(answer("partial", done = false)); f.server.enqueue(answer(""))
            val output = StringBuilder(); var resets = 0
            assertEquals("", f.request(output, reset = { output.clear(); resets++ }).text)
            assertEquals("", output.toString()); assertEquals(1, resets)
        }
    }

    @Test(timeout = 30000) fun permanentHttpTlsProtocolAndOutputFailuresDoNotRetry() = runBlocking {
        val responses = listOf(
            MockResponse().setResponseCode(401),
            MockResponse().setBody("data: {bad-json}\n\n"),
            MockResponse().setBody(event(JSONObject().put("content", "partial"), "length") + "data: [DONE]\n\n"),
            MockResponse().setBody(event(JSONObject(), "content_filter") + "data: [DONE]\n\n"),
            MockResponse().setBody(event(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "call")
                .put("function", JSONObject().put("name", "x".repeat(101)).put("arguments", "{}"))))) + "data: [DONE]\n\n"))
        for (response in responses) Fixture().use { f ->
            f.server.enqueue(response); failure(f); assertEquals(1, f.invocations.get()); assertTrue(f.waits.isEmpty())
        }
        Fixture().use { f ->
            f.tlsFailure = true; assertEquals("tls_failed", failure(f).diagnostic)
            assertEquals(1, f.invocations.get()); assertTrue(f.waits.isEmpty())
        }
    }

    @Test(timeout = 30000) fun cancellationDuringBackoffOrActiveStreamDoesNotRetryOrDiscardPartial() = runBlocking {
        Fixture().use { f ->
            val waiting = CompletableDeferred<Unit>(); val output = StringBuilder(); var resets = 0
            f.wait = { waiting.complete(Unit); awaitCancellation() }
            f.server.enqueue(answer("partial", done = false))
            val task = launch { f.request(output, reset = { output.clear(); resets++ }) }
            withTimeout(5000) { waiting.await() }; task.cancelAndJoin()
            assertEquals("partial", output.toString()); assertEquals(0, resets); assertEquals(1, f.invocations.get())
        }
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setBody(event(JSONObject().put("content", "partial")) + " ".repeat(10000))
                .throttleBody(100, 100, TimeUnit.MILLISECONDS))
            val output = StringBuilder()
            val task = launch { f.request(output) }
            withTimeout(5000) { while (output.isEmpty()) delay(10) }
            task.cancelAndJoin(); assertEquals("partial", output.toString()); assertEquals(1, f.invocations.get())
            assertEquals(AiFailure.Cancelled, f.logs.values.single().failure)
        }
    }
}

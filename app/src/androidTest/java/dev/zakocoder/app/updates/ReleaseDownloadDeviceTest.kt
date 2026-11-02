package dev.zakocoder.app.updates

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Deterministic HTTP-layer fixture exercises production streamed download and digest rejection. */
class ReleaseDownloadDeviceTest {
    private val bytes = "apk-stream-fixture".repeat(10000).toByteArray()
    private val tag = "v0.2.0-beta.1"
    private val name = "ZakoCoder-0.2.0-beta.1-arm64.apk"
    private val root = "https://github.com/AYssu/ZakoCoder/releases/download/$tag/"
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun client(checksum: String = digest, declaredSize: Long = bytes.size.toLong()): Pair<ReleaseClient, AppRelease> {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("SHA256SUMS.txt")) "$checksum  $name\n".toByteArray() else bytes
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return ReleaseClient(http) to AppRelease(tag, "", true, ReleaseAsset(name, root + name, declaredSize), ReleaseAsset("SHA256SUMS.txt", root + "SHA256SUMS.txt", 99))
    }
    @Test fun completeBodyIsVerifiedBeforeRenameAndReportsProgress() = runBlocking {
        val (client, release) = client()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "update-stream-test.apk")
        var received = 0L
        try {
            client.download(release, file) { current, total -> assertTrue(current >= received); assertEquals(bytes.size.toLong(), total); received = current }
            assertArrayEquals(bytes, file.readBytes()); assertEquals(bytes.size.toLong(), received)
            assertFalse(File(file.parentFile, file.name + ".part").exists())
        } finally { file.delete() }
    }
    @Test fun cancellingARealHttpsStreamStopsAndDeletesPartialFile() = runBlocking {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "update-cancel-test.apk")
        val http = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val client = ReleaseClient(http)
        val worker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("$digest  $name\n"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes))
                .throttleBody(1024, 1, java.util.concurrent.TimeUnit.SECONDS))
            val release = AppRelease(tag, "", true, ReleaseAsset(name, server.url("/apk").toString(), bytes.size.toLong()),
                ReleaseAsset("SHA256SUMS.txt", server.url("/sums").toString(), 99))
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = worker.async {
                client.download(release, file) { _, _ -> started.complete(Unit) }
            }
            kotlinx.coroutines.withTimeout(10000) { started.await() }
            job.cancel(); client.cancel()
            kotlinx.coroutines.withTimeout(5000) { job.join() }
            assertTrue("The stream job must acknowledge cancellation", job.isCancelled)
            // Deferred owns the socket-reset exception; cancellation must not escape as an
            // unhandled root launch failure and terminate the instrumentation process.
            assertTrue("Cancelled download cannot return success", runCatching { job.await() }.isFailure)
            assertFalse(file.exists()); assertFalse(File(file.parentFile, file.name + ".part").exists())
        } finally { worker.cancel(); client.cancel(); file.delete(); server.shutdown() }
    }
    @Test fun corruptedDigestOrWrongSizeNeverLeavesInstallableFile() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "update-stream-invalid.apk")
        for ((client, release) in listOf(client("0".repeat(64)), client(declaredSize = bytes.size.toLong() + 1))) {
            try { client.download(release, file) { _, _ -> }; fail("Must reject invalid artifact") }
            catch (_: IOException) { }
            assertFalse(file.exists()); assertFalse(File(file.parentFile, file.name + ".part").exists())
        }
    }
}

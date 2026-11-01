package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.mcp.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class McpDiagnosticsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureSecret = "diagnostic-fixture-only"

    @Test fun importFailuresHaveSpecificReasonsWithoutEchoingConfiguration() {
        val cases = listOf(
            "{\"token\":\"$fixtureSecret\",}" to McpFailureReason.JsonSyntax,
            "{\"type\":\"http\",\"type\":\"sse\"}" to McpFailureReason.DuplicateJsonKey,
            "[]" to McpFailureReason.ConfigShape,
            "{\"mcpServers\":{}}" to McpFailureReason.ServerCount,
            "{\"type\":\"unsupported\"}" to McpFailureReason.Transport,
            "{\"command\":\"python3\",\"unknown\":\"$fixtureSecret\"}" to McpFailureReason.UnknownField,
            "{\"type\":\"http\",\"url\":\"https://example.invalid/mcp?token=$fixtureSecret\"}" to McpFailureReason.UrlSecrets,
            "{\"type\":\"http\",\"url\":\"not-an-address\"}" to McpFailureReason.Url,
            "{\"type\":\"http\",\"url\":\"https://example.invalid/mcp\",\"headers\":{\"Host\":\"$fixtureSecret\"}}" to McpFailureReason.Headers,
            "{\"command\":\"python3\",\"args\":[42]}" to McpFailureReason.Fields,
        )
        cases.forEach { (source, reason) ->
            val failure = try { McpConfigParser.parse(source); error("Invalid fixture accepted") } catch (expected: McpException) { expected }
            assertEquals(reason, failure.reason)
            val message = McpDiagnostics.message(context, failure)
            assertTrue(message.contains("MCP_${reason.name}"))
            assertFalse(message.contains(fixtureSecret))
            assertFalse(failure.toString().contains(fixtureSecret))
        }
        val large = " ".repeat(512 * 1024 + 1)
        try { McpConfigParser.parse(large); fail() } catch (failure: McpException) { assertEquals(McpFailureReason.ConfigSize, failure.reason) }
    }

    @Test(timeout = 15000) fun httpFailureKeepsTypedReasonAcrossSessionCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        MockWebServer().use { http ->
            http.enqueue(MockResponse().setResponseCode(401).setBody("fixture-only"))
            val session = McpSession(McpServer(name = "fixture", transport = McpTransport.StreamableHttp,
                url = http.url("/mcp").toString()), scope, { error("No process") })
            try {
                session.probe(); fail("HTTP failure expected")
            } catch (failure: McpException) {
                assertEquals("typed: $failure", McpFailureReason.HttpStatus, failure.reason)
                assertEquals(401, failure.httpStatus)
            } finally { session.close(); scope.cancel() }
        }
    }

    @Test(timeout = 60000) fun controllerReportsHttpStatusAuthenticationAndDuplicateNameAndRecovers() = runBlocking {
        val root = File(context.cacheDir, "mcp-diagnostic-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val alias = "mcp-diagnostic-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = McpController(isolated, scope, { _, _ -> error("No process") }, keyAlias = alias)
        try {
            withTimeout(10000) { while (controller.state.value.loading || controller.state.value.busy) delay(10) }
            MockWebServer().use { http ->
                val server = McpServer(name = "diagnostic", transport = McpTransport.StreamableHttp, url = http.url("/mcp").toString(), enabled = true)
                controller.save(server).join()
                for (code in listOf(401, 403, 404, 405, 429, 503, 302)) {
                    http.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "https://example.invalid/$fixtureSecret")
                        .setHeader("Content-Type", "text/html").setBody("upstream-secret=$fixtureSecret"))
                    controller.probe("fixture-project", server.id).join()
                    val message = controller.state.value.error
                    assertTrue("Expected HTTP $code, got: $message", if (code == 302) message.contains("MCP_Redirect") else message.contains("HTTP $code"))
                    assertFalse(message.contains(fixtureSecret))
                    assertEquals("", controller.state.value.probeResult)
                    assertFalse(controller.state.value.busy)
                }
            }
            controller.importJson("""{"mcpServers":{"diagnostic":{"command":"python3"}}}""").join()
            assertTrue(controller.state.value.error.contains("MCP_DuplicateServer"))
            assertEquals(1, controller.state.value.servers.size)
            controller.importJson("""{"command":"python3"}""", "another").join()
            assertEquals("", controller.state.value.error)
            assertEquals(2, controller.state.value.servers.size)
        } finally {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
}

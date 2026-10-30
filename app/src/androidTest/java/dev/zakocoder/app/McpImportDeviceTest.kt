package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.mcp.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real org.json and encrypted isolated storage; never uses a user's MCP configuration. */
class McpImportDeviceTest {
    private val http = """{"type":"http","url":"http://127.0.0.1:38457/mcp","headers":{"Authorization":"Bearer import-test-placeholder"}}"""

    @Test fun singleHttpConfigImportsWithSafeDefaultNameAndNoPermission() {
        val server = McpConfigParser.parse(http.dropLast(1) + ",\"enabled\":true,\"autoApprove\":[\"*\"]}").single()
        assertEquals(McpConfigParser.DEFAULT_IMPORT_NAME, server.name)
        assertEquals(McpTransport.StreamableHttp, server.transport)
        assertEquals("Bearer import-test-placeholder", server.headers["Authorization"])
        assertFalse(server.enabled)
        assertFalse(server.toString().contains("import-test-placeholder"))
        assertFalse(server.name.contains("import-test-placeholder"))
    }

    @Test fun wrappedPrivateHttpConfigPreservesNameAndHeadersWithoutEnabling() {
        val source = """{"mcpServers":{"proxypin_mobile":{"type":"http","url":"http://10.0.0.2:9127/mcp","headers":{"Authorization":"Bearer import-test-placeholder"}}}}"""
        val server = McpConfigParser.parse(source).single()
        assertEquals("proxypin_mobile", server.name)
        assertEquals("http://10.0.0.2:9127/mcp", server.url)
        assertEquals(McpTransport.StreamableHttp, server.transport)
        assertEquals("Bearer import-test-placeholder", server.headers["Authorization"])
        assertFalse(server.enabled)
        assertFalse(server.toString().contains("import-test-placeholder"))
        assertTrue(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.0.0.2"))
        assertTrue(McpConfigParser.sameOrigin(server.url, "http://10.0.0.2:9127/messages?session=fixture"))
        assertFalse(McpConfigParser.sameOrigin(server.url, "http://10.0.0.3:9127/messages"))
    }

    @Test(timeout = 30000) fun privateHttpEndpointCompletesProtocolWithFixtureRoutingOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authenticated = AtomicBoolean(true)
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer import-test-placeholder") authenticated.set(false)
                    val message = JSONObject(request.body.readUtf8())
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    val result = if (message.getString("method") == "initialize")
                        JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject().put("tools", JSONObject()))
                    else JSONObject().put("tools", JSONArray())
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        JSONObject().put("jsonrpc", "2.0").put("id", message.get("id")).put("result", result).toString())
                }
            }
            http.start(InetAddress.getByName("127.0.0.1"), 0)
            val source = """{"mcpServers":{"proxypin_mobile":{"type":"http","url":"http://10.0.0.2:${http.port}/mcp","headers":{"Authorization":"Bearer import-test-placeholder"}}}}"""
            // A fixed loopback HTTP proxy receives every request; never contact a real LAN service.
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", http.port)))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            val session = McpSession(McpConfigParser.parse(source).single(), scope, { error("No process expected") }, client)
            try {
                val result = session.exchange("tools/list", JSONObject())
                assertEquals(0, result.getJSONArray("tools").length())
                assertTrue(authenticated.get())
                assertEquals(3, http.requestCount)
            } finally { session.close(); scope.cancel() }
        }
    }

    @Test fun explicitSingleNameOverridesJsonNameAndWrappedNamesArePreserved() {
        val named = http.dropLast(1) + ",\"name\":\"JSON service\"}"
        assertEquals("JSON service", McpConfigParser.parse(named).single().name)
        assertEquals("My server", McpConfigParser.parse(named, "  My server  ").single().name)
        val wrapped = """{"mcpServers":{"first":{"command":"python3"},"second":{"type":"sse","url":"https://example.invalid/sse"}}}"""
        assertEquals(setOf("first", "second"), McpConfigParser.parse(wrapped, "ignored single name").map { it.name }.toSet())
        assertTrue(McpConfigParser.parse(wrapped).all { !it.enabled })
    }

    @Test fun singleStdioAndHttpTransportAliasesUseExistingValidation() {
        val stdio = McpConfigParser.parse("""{"command":"python3","args":["-u","server.py"],"env":{"MODE":"fixture"}}""").single()
        assertEquals(McpTransport.Stdio, stdio.transport)
        assertEquals(listOf("-u", "server.py"), stdio.args)
        for (type in listOf("http", "streamable_http", "streamable-http", "streamablehttp")) {
            assertEquals(McpTransport.StreamableHttp, McpConfigParser.parse("""{"transport":"$type","url":"https://example.invalid/mcp"}""").single().transport)
        }
    }

    @Test fun singleConfigStillRejectsUnsafeUrlsDuplicatesUnknownFieldsAndInvalidCredentials() {
        val invalid = listOf(
            "{}",
            """{"type":"http","url":"http://example.invalid/mcp"}""",
            """{"type":"http","url":"http://127.0.0.1.evil.invalid/mcp"}""",
            """{"type":"http","url":"https://user:pass@example.invalid/mcp"}""",
            """{"type":"http","url":"https://example.invalid/mcp?token=placeholder"}""",
            """{"type":"http","type":"sse","url":"https://example.invalid/mcp"}""",
            """{"type":"http","url":"https://example.invalid/mcp","unknown":true}""",
            """{"type":"http","url":"https://example.invalid/mcp","headers":{"Authorization":42}}""",
            """{"type":"http","url":"https://example.invalid/mcp","headers":{"Host":"other.invalid"}}""",
            """{"type":"http","url":"https://example.invalid/mcp","headers":{"Authorization":"fixture\r\nOther: injected"}}""",
            """{"command":"python3","enabled":"true"}""",
            """{"command":"python3","autoApprove":true}""",
            """{"command":"python3","name":12}""",
            """{"mcpServers":{"one":{"command":"python3"}},"type":"stdio"}"""
        )
        for (source in invalid) {
            try { McpConfigParser.parse(source); fail("Invalid import accepted") }
            catch (error: McpException) { assertEquals(McpProblem.InvalidConfig, error.problem) }
        }
    }

    @Test fun controllerSavesSingleConfigEncryptedWithoutStartingAndRejectsNameCollision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mcp-import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val alias = "mcp-import-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val starts = AtomicInteger()
        val controller = McpController(isolated, scope, { _, _ -> starts.incrementAndGet(); error("Import cannot start a process") }, keyAlias = alias)
        try {
            withTimeout(10000) { while (controller.state.value.loading || controller.state.value.busy) delay(10) }
            controller.importJson(http, "Phone service").join()
            assertEquals("", controller.state.value.error)
            val server = controller.state.value.servers.single()
            assertEquals("Phone service", server.name)
            assertFalse(server.enabled)
            assertEquals(0, starts.get())
            val stored = withContext(Dispatchers.IO) { File(root, "mcp-servers-v1.json").readText() }
            assertFalse(stored.contains("import-test-placeholder"))
            controller.importJson(http, "Phone service").join()
            assertTrue(controller.state.value.error.isNotBlank())
            assertEquals(listOf(server), controller.state.value.servers)
            controller.importJson(http, "Other service").join()
            assertEquals("", controller.state.value.error)
            assertEquals(2, controller.state.value.servers.size)
            assertTrue(controller.state.value.servers.all { !it.enabled })
            assertEquals(0, starts.get())
            controller.load().join()
            assertEquals(2, controller.state.value.servers.size)
            assertTrue(controller.state.value.servers.all { !it.enabled })
        } finally {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
}

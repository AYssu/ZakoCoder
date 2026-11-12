package dev.zakocoder.app

import android.content.ContextWrapper
import dev.zakocoder.app.mcp.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Isolated config/Keystore and loopback servers; no model or installed guest required. */
class McpDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Owned(val context: android.content.Context) {
        val root = File(context.cacheDir, "mcp-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val alias = "mcp-test-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = root
        }
        fun controller() = McpController(isolated, scope, keyAlias = alias)
        suspend fun ready(controller: McpController) { withTimeout(10000) { while (controller.state.value.loading || controller.state.value.busy) delay(10) }; assertEquals("", controller.state.value.error) }
        suspend fun close(controller: McpController) {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
    private fun response(id: Any, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    private fun initialized() = JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject().put("tools", JSONObject()).put("resources", JSONObject()).put("prompts", JSONObject()))
    private suspend fun saved(c: McpController, server: McpServer) { c.save(server).join(); assertEquals("", c.state.value.error) }

    @Test fun strictImportIsDisabledEncryptedAndDuplicateNamesNeverOverwrite() = runBlocking {
        val own = Owned(context); val c = own.controller()
        try {
            own.ready(c)
            val source = """{"mcpServers":{"fixture":{"type":"http","url":"http://127.0.0.1:1234/mcp","headers":{"Authorization":"Bearer secret-env-fixture"},"enabled":true,"autoApprove":["*"]}}}"""
            c.importJson(source).join()
            assertEquals("", c.state.value.error)
            val server = c.state.value.servers.single()
            assertFalse(server.enabled)
            assertFalse(server.toString().contains("secret-env-fixture"))

            val disk = withContext(Dispatchers.IO) { File(own.root, "mcp-servers-v1.json").readText() }
            assertFalse(disk.contains("secret-env-fixture")); assertFalse(disk.contains("service.py"))
            c.importJson(source).join()
            assertTrue(c.state.value.error.isNotBlank()); assertEquals(listOf(server), c.state.value.servers)
            val reloaded = own.controller(); own.ready(reloaded)
            assertEquals(server, reloaded.state.value.servers.single())
            for (invalid in listOf(
                """{"mcpServers":{"x":{"command":"a"},"x":{"command":"b"}}}""",
                """{"mcpServers":{"x":{"type":"sse","url":"https://example.com/mcp?token=secret"}}}""",
                """{"mcpServers":{"x":{"command":"a","args":[1]}}}""",
                """{"mcpServers":{"x":{"command":"a","url":"https://example.com"}}}""",
                """{"mcpServers":{"x":{"type":"http","url":"http:///mcp"}}}"""
            )) {
                try { McpConfigParser.parse(invalid); fail("invalid accepted") } catch (_: McpException) { }
            }
            reloaded.closeAll().join()
        } finally { own.close(c) }
    }

    @Test fun streamableHttpNegotiatesPaginatesPreservesToolFailureAndRedactsSecrets() = runBlocking {
        val own = Owned(context); val c = own.controller(); val requests = CopyOnWriteArrayList<JSONObject>()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = JSONObject(request.body.readUtf8()); requests += message
                    if (message.optString("method") != "initialize") {
                        assertEquals("2025-06-18", request.getHeader("MCP-Protocol-Version"))
                        assertEquals("session-fixture", request.getHeader("Mcp-Session-Id"))
                    }
                    assertEquals("Bearer secret-header-fixture", request.getHeader("Authorization"))
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    val result = when (message.getString("method")) {
                        "initialize" -> initialized()
                        "tools/list" -> if (!message.getJSONObject("params").has("cursor")) JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "first"))).put("nextCursor", "second")
                            else JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "second")))
                        "tools/call" -> JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "secret-header-fixture secret-env-fixture")))
                        "resources/list" -> JSONObject().put("resources", JSONArray())
                        "resources/read" -> JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", "fixture://one").put("text", "resource")))
                        "prompts/list" -> JSONObject().put("prompts", JSONArray())
                        "prompts/get" -> JSONObject().put("messages", JSONArray())
                        else -> error("unexpected method")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "session-fixture").setBody(response(message.get("id"), result).toString())
                }
            }
            try {
                own.ready(c)
                val server = McpServer(name = "http", transport = McpTransport.StreamableHttp, url = http.url("/mcp").toString(), headers = mapOf("Authorization" to "Bearer secret-header-fixture"), enabled = true)
                saved(c, server)
                assertEquals(2, c.request("project", server.id, "tools/list", JSONObject()).getJSONArray("tools").length())
                val result = c.request("project", server.id, "tools/call", JSONObject().put("name", "first").put("arguments", JSONObject()))
                assertTrue(result.getBoolean("isError")); assertFalse(result.toString().contains("secret-header-fixture"))
                c.request("project", server.id, "resources/list", JSONObject())
                assertEquals("resource", c.request("project", server.id, "resources/read", JSONObject().put("uri", "fixture://one")).getJSONArray("contents").getJSONObject(0).getString("text"))
                c.request("project", server.id, "prompts/list", JSONObject())
                c.request("project", server.id, "prompts/get", JSONObject().put("name", "example"))
                assertEquals(1, requests.count { it.optString("method") == "initialize" })
                c.setEnabled(server.id, false).join()
                try { c.request("project", server.id, "tools/list", JSONObject()); fail("disabled") } catch (error: McpException) { assertEquals(McpProblem.Disabled, error.problem) }
            } finally { own.close(c) }
        }
    }

    @Test fun streamableSseResponseAndCancelledToolAreNeverReplayed() = runBlocking {
        val own = Owned(context); val c = own.controller(); val toolCalls = AtomicInteger()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = JSONObject(request.body.readUtf8())
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    if (message.getString("method") == "tools/call") { toolCalls.incrementAndGet(); return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                    val result = if (message.getString("method") == "initialize") initialized() else JSONObject().put("tools", JSONArray())
                    return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: ${response(message.get("id"), result)}\n\n")
                }
            }
            try {
                own.ready(c); val server = McpServer(name = "sse-response", transport = McpTransport.StreamableHttp, url = http.url("/mcp").toString(), enabled = true); saved(c, server)
                assertEquals(0, c.request("project", server.id, "tools/list", JSONObject()).getJSONArray("tools").length())
                val task = launch { c.request("project", server.id, "tools/call", JSONObject().put("name", "wait")) }
                withTimeout(10000) { while (toolCalls.get() == 0) delay(10) }
                withTimeout(5000) { task.cancelAndJoin() }
                c.closeTask("project")
                assertEquals(1, toolCalls.get()); assertFalse(c.state.value.busy)
            } finally { own.close(c) }
        }
    }

    @Test fun retiredStdioCannotBeImportedOrStarted() = runBlocking {
        val own = Owned(context); val c = own.controller()
        try {
            own.ready(c)
            c.importJson("""{"command":"python3","args":["server.py"]}""").join()
            assertTrue(c.state.value.error.isNotBlank())
            assertTrue(c.state.value.servers.isEmpty())

        } finally { own.close(c) }
    }

    @Test fun oldProcessEntriesAreIgnoredWithoutLosingGlobalNetworkConfiguration() = runBlocking {
        val own = Owned(context); val c = own.controller()
        try {
            own.ready(c)
            val network = McpServer(name = "network", transport = McpTransport.StreamableHttp,
                url = "http://localhost:1234/mcp", headers = mapOf("X-Fixture" to "kept"), enabled = true)
            saved(c, network)
            withContext(Dispatchers.IO) {
                val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(own.alias, null)
                val file = File(own.root, "mcp-servers-v1.json")
                val original = JSONObject(file.readText())
                val decoder = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                decoder.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128,
                    android.util.Base64.decode(original.getString("iv"), android.util.Base64.NO_WRAP)))
                decoder.updateAAD("zakocoder-mcp-v1".toByteArray())
                val entries = JSONArray(String(decoder.doFinal(android.util.Base64.decode(original.getString("data"), android.util.Base64.NO_WRAP))))
                entries.put(JSONObject().put("id", "retired").put("name", "old process").put("enabled", true)
                    .put("config", JSONObject().put("type", "stdio").put("command", "never-run")))
                val encoder = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                encoder.init(javax.crypto.Cipher.ENCRYPT_MODE, key); encoder.updateAAD("zakocoder-mcp-v1".toByteArray())
                file.writeText(JSONObject().put("schemaVersion", 1)
                    .put("iv", android.util.Base64.encodeToString(encoder.iv, android.util.Base64.NO_WRAP))
                    .put("data", android.util.Base64.encodeToString(encoder.doFinal(entries.toString().toByteArray()), android.util.Base64.NO_WRAP)).toString())
            }
            c.load().join()
            assertEquals("", c.state.value.error)
            assertEquals(listOf(network), c.state.value.servers)

            c.save(network.copy(name = "still editable")).join()
            assertEquals("", c.state.value.error)
            val reloaded = own.controller(); own.ready(reloaded)
            assertEquals(listOf(network.copy(name = "still editable")), reloaded.state.value.servers)
            reloaded.closeAll().join()
        } finally { own.close(c) }
    }

    @Test fun corruptedOwnStoreBlocksSaveUntilOriginalEncryptedBytesReload() = runBlocking {
        val own = Owned(context); val c = own.controller()
        try {
            own.ready(c)
            val server = McpServer(name = "encrypted", transport = McpTransport.StreamableHttp, url = "http://127.0.0.1:1234/mcp",
                headers = mapOf("Authorization" to "Bearer secret-env-fixture"))
            saved(c, server)
            val file = File(own.root, "mcp-servers-v1.json")
            val original = withContext(Dispatchers.IO) { file.readBytes() }
            val corrupt = "owned corrupt encrypted store".toByteArray()
            withContext(Dispatchers.IO) { file.writeBytes(corrupt) }
            c.load().join()
            assertTrue(c.state.value.error.isNotEmpty()); assertTrue(c.state.value.servers.isEmpty())
            c.save(server.copy(name = "must-not-save")).join()
            assertTrue(c.state.value.error.isNotEmpty())
            assertArrayEquals(corrupt, withContext(Dispatchers.IO) { file.readBytes() })
            withContext(Dispatchers.IO) { file.writeBytes(original) }
            c.load().join()
            assertEquals("", c.state.value.error); assertEquals(listOf(server), c.state.value.servers)
            assertArrayEquals(original, withContext(Dispatchers.IO) { file.readBytes() })

        } finally { own.close(c) }
    }

    @Test fun disconnectAfterToolExecutionLeavesOneAttemptAndUnknownOutcome() = runBlocking {
        val own = Owned(context); val c = own.controller(); val executions = AtomicInteger()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = JSONObject(request.body.readUtf8())
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    if (message.getString("method") == "tools/call") {
                        executions.incrementAndGet() // The server has already performed the operation.
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(response(message.get("id"), initialized()).toString())
                }
            }
            try {
                own.ready(c)
                val server = McpServer(name = "disconnect", transport = McpTransport.StreamableHttp,
                    url = http.url("/mcp").toString(), enabled = true)
                saved(c, server)
                try {
                    withTimeout(10000) { c.request("project", server.id, "tools/call", JSONObject().put("name", "perform-once")) }
                    fail("Disconnected operation cannot report success")
                } catch (error: McpException) {
                    assertEquals(McpProblem.Unavailable, error.problem)
                }
                c.closeTask("project")
                assertEquals("A sent tool must not be replayed when its response is lost", 1, executions.get())
                assertFalse(c.state.value.running)
            } finally { own.close(c) }
        }
    }

    @Test fun legacySseDiscoversSameOriginEndpointAndRoutesResponses() = runBlocking {
        val own = Owned(context); val c = own.controller()
        LegacyServer().use { server ->
            try {
                own.ready(c)
                val config = McpServer(name = "legacy", transport = McpTransport.Sse, url = server.url, enabled = true)
                saved(c, config)
                val tools = c.request("project", config.id, "tools/list", JSONObject()).getJSONArray("tools")
                assertEquals("legacy-tool", tools.getJSONObject(0).getString("name"))
                assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), server.methods.toList())
                assertEquals("2024-11-05", server.lastProtocol)
            } finally { own.close(c) }
        }
    }

    @Test fun cyclicCursorAndRedirectFailWithoutRetryingOrForwardingCredentials() = runBlocking {
        val own = Owned(context); val c = own.controller(); val pages = AtomicInteger()
        MockWebServer().use { http -> MockWebServer().use { other ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/redirect") return MockResponse().setResponseCode(307).setHeader("Location", other.url("/leak"))
                    val message = JSONObject(request.body.readUtf8())
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    val result = if (message.getString("method") == "initialize") initialized()
                        else { pages.incrementAndGet(); JSONObject().put("tools", JSONArray()).put("nextCursor", "repeated") }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(response(message.get("id"), result).toString())
                }
            }
            try {
                own.ready(c)
                val config = McpServer(name = "cycle", transport = McpTransport.StreamableHttp, url = http.url("/mcp").toString(), enabled = true)
                saved(c, config)
                try { c.request("project", config.id, "tools/list", JSONObject()); fail("cursor loop accepted") }
                catch (error: McpException) { assertEquals(McpProblem.Protocol, error.problem) }
                assertEquals(2, pages.get())
                val redirect = McpServer(name = "redirect", transport = McpTransport.StreamableHttp, url = http.url("/redirect").toString(), headers = mapOf("Authorization" to "Bearer secret-redirect-fixture"), enabled = true)
                saved(c, redirect)
                try { c.request("project", redirect.id, "tools/list", JSONObject()); fail("redirect followed") }
                catch (error: McpException) { assertFalse(error.toString().contains("secret-redirect-fixture")) }
                assertNull(other.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally { own.close(c) }
        } }
    }

    /** A real streaming HTTP server: responses are emitted only after their corresponding POST. */
    private inner class LegacyServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/sse"
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var lastProtocol: String? = null
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val streamReady = java.util.concurrent.CountDownLatch(1)
        private val streamLock = Any()
        private var stream: OutputStream? = null
        private val acceptor = Thread {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept(); sockets += socket
                    Thread { handle(socket) }.apply { isDaemon = true; start() }
                }
            } catch (_: IOException) { /* owned listener closed */ }
        }.apply { isDaemon = true; start() }
        private fun handle(socket: Socket) {
            try {
                val input = socket.getInputStream().bufferedReader()
                val first = input.readLine() ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val out = socket.getOutputStream()
                if (first.startsWith("GET ")) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\nevent: endpoint\ndata: /messages?session=fixture\n\n".toByteArray()); out.flush()
                    synchronized(streamLock) { stream = out }; streamReady.countDown()
                    return
                }
                check(first.startsWith("POST /messages?session=fixture "))
                val body = CharArray(headers.getValue("content-length").toInt()); var offset = 0
                while (offset < body.size) { val count = input.read(body, offset, body.size - offset); check(count > 0); offset += count }
                val message = JSONObject(String(body)); methods += message.getString("method")
                lastProtocol = headers["mcp-protocol-version"]
                if (message.has("id")) {
                    check(streamReady.await(5, TimeUnit.SECONDS))
                    val result = if (message.getString("method") == "initialize") initialized().put("protocolVersion", "2024-11-05")
                        else JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "legacy-tool")))
                    synchronized(streamLock) { stream!!.write("event: message\ndata: ${response(message.get("id"), result)}\n\n".toByteArray()); stream!!.flush() }
                }
                out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); socket.close()
            } catch (_: IOException) { /* owned server stopping */ }
        }
        override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; acceptor.join(1000) }
    }

}

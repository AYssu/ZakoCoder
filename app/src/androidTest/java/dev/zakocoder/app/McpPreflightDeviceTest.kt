package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real loopback MCP traffic with isolated encrypted configuration, never user services. */
class McpPreflightDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: Context, timeout: Long = 3_000) {
        val root = File(context.cacheDir, "mcp-preflight-${UUID.randomUUID()}").apply { check(mkdirs()) }
        private val alias = "mcp-preflight-${UUID.randomUUID()}"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = McpController(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root },
            scope, keyAlias = alias, preflightTimeoutMillis = timeout)
        suspend fun ready() = withTimeout(10_000) {
            while (controller.state.value.loading || controller.state.value.busy) delay(10)
            assertEquals("", controller.state.value.error)
        }
        suspend fun save(server: McpServer) { controller.save(server).join(); assertEquals("", controller.state.value.error) }
        suspend fun close() {
            controller.closeAll().join(); scope.cancel()
            withContext(Dispatchers.IO) {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
            }
        }
    }
    private fun reply(message: JSONObject, value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", message.get("id")).put("result", value).toString())
    private fun initialized() = JSONObject().put("protocolVersion", "2025-06-18")
        .put("capabilities", JSONObject().put("tools", JSONObject()))
    private suspend fun unavailable(block: suspend () -> Unit) {
        try { block(); fail("Unchecked or failed service was allowed") } catch (_: McpException) { }
    }

    @Test fun failedDisabledServicesNeverEnterTaskCatalogAndSuccessfulSessionIsReused() = runBlocking {
        val f = Fixture(context); val c = f.controller
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = JSONObject(request.body.readUtf8()); val path = request.path.orEmpty()
                    seen += path to message.optString("method")
                    if (path == "/rejected") return MockResponse().setResponseCode(401)
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    return reply(message, if (message.getString("method") == "initialize") initialized()
                        else JSONObject().put("tools", JSONArray()))
                }
            }
            try {
                f.ready()
                val good = McpServer(name = "healthy", transport = McpTransport.StreamableHttp, url = http.url("/good").toString(),
                    headers = mapOf("Authorization" to "Bearer only-a-test-secret"), enabled = true)
                val bad = good.copy(id = UUID.randomUUID().toString(), name = "rejected", url = http.url("/rejected").toString())
                val off = good.copy(id = UUID.randomUUID().toString(), name = "disabled", url = http.url("/off").toString(), enabled = false)
                f.save(good); f.save(bad); f.save(off)
                assertEquals("", c.taskCatalog("project"))
                unavailable { c.requestPrepared("project", good.id, "tools/list", JSONObject()) }
                c.prepareTask("project")
                val catalog = JSONObject(c.taskCatalog("project")).getJSONArray("enabled_mcp_servers")
                assertEquals(1, catalog.length()); assertEquals(good.id, catalog.getJSONObject(0).getString("id"))
                assertFalse(c.taskCatalog("project").contains("only-a-test-secret"))
                assertFalse(c.taskCatalog("project").contains(http.hostName))
                assertEquals(McpConnectionStatus.Failed, c.state.value.connections[bad.id]?.status)
                assertTrue(c.state.value.connections[bad.id]!!.detail.contains("401"))
                assertFalse(seen.any { it.first == "/off" || it.second == "tools/list" || it.second == "tools/call" })
                val count = seen.size
                c.prepareTask("project")
                assertEquals(count, seen.size)
                unavailable { c.requestPrepared("project", bad.id, "tools/list", JSONObject()) }
                c.requestPrepared("project", good.id, "tools/list", JSONObject())
                assertEquals(1, seen.count { it.first == "/good" && it.second == "initialize" })
                c.closeTask("project")
                assertEquals("", c.taskCatalog("project"))
                c.prepareTask("project")
                assertEquals(2, seen.count { it.first == "/good" && it.second == "initialize" })
                c.setEnabled(good.id, false).join()
                assertEquals("", c.taskCatalog("project"))
                unavailable { c.requestPrepared("project", good.id, "tools/list", JSONObject()) }
            } finally { f.close() }
        }
    }

    @Test fun boundedTimeoutKeepsHealthyServiceAndDoesNotRetryFailedServiceWithinTask() = runBlocking {
        val f = Fixture(context, timeout = 700); val c = f.controller
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val message = JSONObject(request.body.readUtf8())
                    seen += request.path.orEmpty() to message.optString("method")
                    if (request.path == "/hung") return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    if (!message.has("id")) return MockResponse().setResponseCode(202)
                    return reply(message, initialized())
                }
            }
            try {
                f.ready()
                val good = McpServer(name = "healthy", transport = McpTransport.StreamableHttp, url = http.url("/good").toString(), enabled = true)
                val hung = good.copy(id = UUID.randomUUID().toString(), name = "hung", url = http.url("/hung").toString())
                f.save(good); f.save(hung)
                withTimeout(5_000) { c.prepareTask("project") }
                assertEquals(good.id, JSONObject(c.taskCatalog("project")).getJSONArray("enabled_mcp_servers").getJSONObject(0).getString("id"))
                assertEquals(McpConnectionStatus.Failed, c.state.value.connections[hung.id]?.status)
                assertFalse(c.state.value.running); assertFalse(c.state.value.busy)
                assertTrue(c.state.value.connections[hung.id]!!.detail.contains("超时"))
                val count = seen.size
                c.prepareTask("project")
                assertEquals(count, seen.size)
            } finally { f.close() }
        }
    }

    @Test fun cancellationClearsCheckingStateAndNewConfigurationInvalidatesReadySnapshot() = runBlocking {
        val f = Fixture(context); val c = f.controller
        val requests = CopyOnWriteArrayList<String>()
        MockWebServer().use { http ->
            http.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.path.orEmpty()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            try {
                f.ready()
                val hung = McpServer(name = "hung", transport = McpTransport.StreamableHttp, url = http.url("/hung").toString(), enabled = true)
                f.save(hung)
                val task = launch { c.prepareTask("project") }
                withTimeout(5_000) { while (requests.isEmpty()) delay(10) }
                withTimeout(5_000) { task.cancelAndJoin() }
                assertEquals("", c.taskCatalog("project"))
                assertFalse(c.state.value.connections.values.any { it.status == McpConnectionStatus.Checking })
                assertFalse(c.state.value.busy)
                c.save(hung.copy(url = http.url("/changed").toString())).join()
                assertEquals("", c.taskCatalog("project"))
                assertTrue(c.state.value.connections.isEmpty())
                assertEquals(listOf("/hung"), requests.toList())
            } finally { f.close() }
        }
    }
}

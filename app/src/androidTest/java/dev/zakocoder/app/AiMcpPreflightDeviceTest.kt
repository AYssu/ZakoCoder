package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Assert the actual serialized model request, not just the controller's connection status. */
class AiMcpPreflightDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun ready(ai: AiController) = withTimeout(30_000) {
        while (!ai.state.value.configured || ai.state.value.permissionsLoading || ai.state.value.permissionsSaving || ai.state.value.skillsLoading) delay(20)
    }
    private suspend fun finished(ai: AiController) = withTimeout(30_000) { while (ai.state.value.active) delay(20) }
    private fun isolated(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun request(f: AiDeviceTest.Fixture) = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
    private fun hasMcp(request: JSONObject) = request.getJSONArray("tools").toString().contains("\"use_mcp\"")

    @Test(timeout = 90_000) fun unavailableMcpIsNotAdvertisedAndIsCheckedOnlyOnceAcrossModelRounds() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var checks = 0
            var ai: AiController? = null
            try {
                main {
                    ai = AiController(isolated(f), { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No guest commands") }, {}, {}, f.transport, f.config, history = f.history,
                        prepareMcpTask = { checks++ }, mcpTaskCatalog = { "" },
                        mcpPreview = { error("Unavailable service must not be previewed") },
                        mcpTool = { _, _ -> error("Unavailable service must not be invoked") })
                    ai!!.select("fixture")
                }
                val c = requireNotNull(ai); ready(c)
                f.enqueue(calls = listOf(AiCall("plan", "update_plan", JSONObject().put("steps", JSONArray()
                    .put(JSONObject().put("title", "Inspect current project").put("status", "in_progress"))).toString())))
                f.enqueue("已检查计划。")
                main { assertTrue(c.send("整理项目计划")) }
                finished(c)
                assertEquals("", c.state.value.error)
                assertEquals(1, checks)
                repeat(2) {
                    val body = request(f)
                    assertFalse(hasMcp(body))
                    assertFalse(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("Use use_mcp"))
                }
                main { c.setPolicy(c.state.value.policy.copy(overrides = c.state.value.policy.overrides + (AiTool.UseMcp to AiPermission.Deny))) }
                ready(c)
                f.enqueue("继续按普通权限工作。")
                main { assertTrue(c.send("继续检查")) }
                finished(c)
                assertEquals(1, checks)
                assertFalse(hasMcp(request(f)))
            } finally { main { ai?.stop() } }
        }
    }

    @Test(timeout = 90_000) fun runtimeConnectionFailureRemovesMcpFromSubsequentRequestsWithoutNewHandshake() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var checks = 0
            var available = true
            var calls = 0
            var ai: AiController? = null
            try {
                main {
                    ai = AiController(isolated(f), { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No guest commands") }, {}, {}, f.transport, f.config, history = f.history,
                        prepareMcpTask = { checks++ }, mcpTaskCatalog = { if (available) "Connected MCP: fixture" else "" },
                        mcpPreview = { "Fixture MCP discovery" }, mcpTool = { _, _ ->
                            calls++; available = false; throw dev.zakocoder.app.mcp.McpException(dev.zakocoder.app.mcp.McpProblem.Unavailable)
                        })
                    ai!!.select("fixture")
                }
                val c = requireNotNull(ai); ready(c)
                main { c.setPolicy(c.state.value.policy.copy(overrides = c.state.value.policy.overrides + (AiTool.UseMcp to AiPermission.Allow))) }
                ready(c)
                f.enqueue(calls = listOf(AiCall("discover", "use_mcp", JSONObject().put("server_id", "fixture")
                    .put("method", "tools/list").put("params", JSONObject()).put("reason", "Discover fixture tools").toString())))
                f.enqueue("服务不可用，继续其他工作。")
                main { assertTrue(c.send("检查服务工具")) }
                finished(c)
                assertEquals(1, checks); assertEquals(1, calls)
                assertTrue(hasMcp(request(f)))
                assertFalse(hasMcp(request(f)))
                assertTrue(c.state.value.events.any { it.tool == AiTool.UseMcp && it.status == AiToolStatus.Failed })
                assertEquals("", c.state.value.error)
            } finally { main { ai?.stop() } }
        }
    }
}

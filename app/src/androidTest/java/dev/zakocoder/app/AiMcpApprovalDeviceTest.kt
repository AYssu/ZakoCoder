package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Isolated TLS model fixture; no user services, projects or model configuration are used. */
class AiMcpApprovalDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(30000) { while (!check()) delay(30) }
    private fun request(id: String, server: String = "local", count: Int = 1) = AiCall(id, "use_mcp",
        JSONObject().put("server_id", server).put("method", "tools/call")
            .put("params", JSONObject().put("name", "format").put("arguments", JSONObject().put("count", count)))
            .put("reason", "调用已配置服务").toString())

    private fun controller(f: AiDeviceTest.Fixture,
        invoke: suspend (String, JSONObject) -> String,
        close: suspend (String) -> Unit = {}) = AiController(
        object : ContextWrapper(context) { override fun getFilesDir(): File = f.root },
        { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false }, { _, _ -> error("No guest command expected") }, {}, {},
        f.transport, f.config, history = f.history,
        mcpCatalog = { "Enabled fixture servers: local, remote. Metadata is not connection evidence." },
        mcpPreview = { it.toString() }, mcpTool = invoke, finishMcpTask = close)

    @Test(timeout = 90000) fun rejectingMcpNeverInvokesTheConnectionOrRemoteOperation() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var calls = 0
            var closes = 0
            lateinit var ai: AiController
            var initialized = false
            try {
                main { ai = controller(f, { _, _ -> calls++; "{}" }, { closes++ }); initialized = true; ai.select("fixture") }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                assertEquals(AiPermission.Ask, ai.state.value.policy.permission(AiTool.UseMcp))
                f.enqueue(calls = listOf(request("one")))
                f.enqueue("已取消外部操作。")
                main { assertTrue(ai.send("调用 MCP")) }
                await { ai.state.value.approval != null }
                assertEquals(0, calls)
                assertTrue(ai.state.value.approval!!.detail.replace("\\/", "/").contains("tools/call"))
                main { ai.approve(AiDecision.Reject) }
                await { !ai.state.value.active }
                assertEquals(0, calls)
                assertEquals(1, closes)
                assertEquals(AiToolStatus.Denied, ai.state.value.events.single().status)
            } finally { main { if (initialized) ai.stop() }; await { !initialized || !ai.state.value.active } }
        }
    }

    @Test(timeout = 120000) fun taskGrantIsBoundToServerAndExactArgumentsAndToolErrorsPersist() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val observed = mutableListOf<JSONObject>()
            lateinit var ai: AiController
            var initialized = false
            try {
                main {
                    ai = controller(f, { _, args ->
                        observed += args
                        assertEquals(1, args.getJSONObject("params").getJSONObject("arguments").getInt("count"))
                        JSONObject().put("isError", observed.size == 2).put("content", "fixture operation result").toString()
                    }); initialized = true; ai.select("fixture")
                }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                f.enqueue(calls = listOf(request("one"), request("same"), request("changed", count = 2), request("other", server = "remote")))
                f.enqueue("已保留服务返回的失败结果。")
                main { assertTrue(ai.send("按精确输入授权")) }
                await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.TaskTarget) }
                await { observed.size == 2 && ai.state.value.approval != null }
                assertEquals(AiToolStatus.Failed, ai.state.value.events[1].status)
                assertEquals(R.string.ai_permission_task_rule, ai.state.value.events[1].permissionSource)
                assertTrue(ai.state.value.approval!!.detail.contains("\"count\":2"))
                main { ai.approve(AiDecision.Reject) }
                await { ai.state.value.approval?.detail?.contains("remote") == true }
                main { ai.approve(AiDecision.Reject) }
                await { !ai.state.value.active }
                assertEquals(2, observed.size)
                val saved = withContext(Dispatchers.IO) { f.history.load("fixture") }
                assertTrue(saved.state!!.events.any { it.tool == AiTool.UseMcp && it.status == AiToolStatus.Failed && it.output.contains("isError") })
            } finally { main { if (initialized) ai.stop() }; await { !initialized || !ai.state.value.active } }
        }
    }

    @Test(timeout = 90000) fun stoppingAnInFlightMcpCallCancelsItAndClosesTheTaskWithoutRetry() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var calls = 0
            var cancelled = false
            var closed = false
            lateinit var ai: AiController
            var initialized = false
            try {
                main {
                    ai = controller(f, { _, _ ->
                        calls++
                        try { awaitCancellation() } finally { cancelled = true }
                    }, { closed = true }); initialized = true; ai.select("fixture")
                }
                await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                f.enqueue(calls = listOf(request("stop")))
                main { assertTrue(ai.send("取消外部调用")) }
                await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.Once) }
                await { calls == 1 }
                main { ai.stop() }
                await { !ai.state.value.active }
                assertTrue(cancelled)
                assertTrue(closed)
                assertEquals(1, calls)
                assertEquals(AiToolStatus.Cancelled, ai.state.value.events.single().status)
            } finally { main { if (initialized) ai.stop() }; await { !initialized || !ai.state.value.active } }
        }
    }
}

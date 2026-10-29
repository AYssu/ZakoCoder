package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AiExecutionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(check: () -> Boolean) = withTimeout(25000) { while (!check()) delay(35) }
    private fun isolated(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) { override fun getFilesDir(): File = f.root }
    private fun call(id: String, tool: AiTool, path: String = "", content: String? = null) = AiCall(id, tool.wire,
        JSONObject().put(if (tool == AiTool.RunCommand) "command" else "path", path).put("reason", "验证工具权限与真实执行事件")
            .apply { if (content != null) put("content", content) }.toString())
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading }
    private suspend fun policy(ai: AiController, value: AiPolicy) { main { ai.setPolicy(value) }; await { !ai.state.value.permissionsSaving }; assertEquals(value, ai.state.value.policy) }

    @Test(timeout = 100000) fun denyAllowRulesPersistPerProjectAndNeverInvokeForbiddenTools() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val wrapped = isolated(f); var ai: AiController? = null; var commands = 0
            try {
                File(f.root, "source.txt").writeText("original")
                main { ai = AiController(wrapped, { f.root }, { _, _ -> false }, { _, _ -> commands++; error("Forbidden") }, {}, {}, f.transport, f.config, history = f.history); ai!!.select("project-one") }
                val c = ai!!; ready(c)
                val rules = AiPolicy(AiPermission.Deny, mapOf(AiTool.ReadFile to AiPermission.Allow))
                policy(c, rules)
                f.enqueue(calls = listOf(call("read", AiTool.ReadFile, "source.txt"), call("write", AiTool.WriteFile, ".env", "forbidden"), call("run", AiTool.RunCommand, "touch forbidden")))
                f.enqueue("权限拒绝已被保留。")
                main { c.send("检查权限") }; await { !c.state.value.active }
                assertEquals(listOf(AiToolStatus.Succeeded, AiToolStatus.Denied, AiToolStatus.Denied), c.state.value.events.map { it.status })
                assertEquals(0, commands); assertFalse(File(f.root, ".env").exists()); assertEquals("original", File(f.root, "source.txt").readText())
                assertTrue(c.state.value.events.filter { it.status == AiToolStatus.Denied }.all { it.permission == AiPermission.Deny && it.output.contains("禁止") })
                assertEquals(rules, withContext(Dispatchers.IO) { AiPermissionStore(wrapped).load("project-one") })
                main { c.select("project-two") }; ready(c); assertEquals(AiPolicy(), c.state.value.policy)
                main { c.select("project-one") }; ready(c); assertEquals(rules, c.state.value.policy)
                assertEquals(3, c.state.value.events.size)
                withContext(Dispatchers.IO) { File(f.root, "ai-permissions-v1.json").writeText("{\"project-one\":42}") }
                main { c.select("project-two") }; ready(c)
                main { c.select("project-one") }; ready(c)
                assertTrue(AiTool.entries.all { c.state.value.policy.permission(it) == AiPermission.Deny })
                assertTrue(c.state.value.error.isNotBlank())
                assertEquals("{\"project-one\":42}", File(f.root, "ai-permissions-v1.json").readText())
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
    @Test(timeout = 100000) fun targetGrantNeverExpandsAndPendingPermissionCanBeRevoked() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var ai: AiController? = null
            try {
                main { ai = AiController(isolated(f), { f.root }, { _, _ -> false }, { _, _ -> error("No command") }, {}, {}, f.transport, f.config, history = f.history); ai!!.select("fixture") }
                val c = ai!!; ready(c)
                f.enqueue(calls = listOf(call("w1", AiTool.WriteFile, "one.txt", "first"), call("w2", AiTool.WriteFile, "one.txt", "second"), call("w3", AiTool.WriteFile, "two.txt", "unapproved")))
                f.enqueue("按授权执行。")
                main { c.send("按目标授权") }; await { c.state.value.approval != null }; main { c.approve(AiDecision.TaskTarget) }
                await { c.state.value.approval?.title == "two.txt" }
                assertEquals("second", File(f.root, "one.txt").readText()); assertFalse(File(f.root, "two.txt").exists())
                assertEquals(R.string.ai_permission_task_rule, c.state.value.events[1].permissionSource)
                assertEquals(AiPermission.Allow, c.state.value.events.first().permission)
                main { c.approve(false) }; await { !c.state.value.active }
                assertEquals(AiPermission.Deny, c.state.value.events.last().permission)
                assertEquals(R.string.ai_permission_rejected_rule, c.state.value.events.last().permissionSource)
                f.enqueue(calls = listOf(call("read-next", AiTool.ReadFile, "one.txt"), call("w-next", AiTool.WriteFile, "one.txt", "third")))
                f.enqueue("遵守撤销权限。")
                main { c.send("下一任务必须重新审批") }; await { c.state.value.approval?.title == "one.txt" }
                policy(c, c.state.value.policy.copy(overrides = c.state.value.policy.overrides + (AiTool.WriteFile to AiPermission.Deny)))
                await { !c.state.value.active }
                assertEquals("second", File(f.root, "one.txt").readText())
                assertEquals(AiToolStatus.Denied, c.state.value.events.last().status); assertEquals(AiPermission.Deny, c.state.value.events.last().permission)
                assertEquals(R.string.ai_permission_tool_rule, c.state.value.events.last().permissionSource)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
    @Test(timeout = 100000) fun automaticPermissionStillShowsDiffAndCannotOverwriteBaselineConflict() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var ai: AiController? = null
            val writeResponse = java.util.concurrent.CountDownLatch(1)
            try {
                val file = File(f.root, "source.txt").apply { writeText("original") }
                main { ai = AiController(isolated(f), { f.root }, { _, _ -> false }, { _, _ -> error("No command") }, {}, {}, f.transport, f.config, history = f.history); ai!!.select("fixture") }
                val c = ai!!; ready(c)
                policy(c, c.state.value.policy.copy(overrides = c.state.value.policy.overrides + (AiTool.WriteFile to AiPermission.Allow)))
                f.enqueue(calls = listOf(call("read", AiTool.ReadFile, "source.txt")))
                f.enqueue(calls = listOf(call("write", AiTool.WriteFile, "source.txt", "AI overwrite")), delayMillis = 50)
                f.enqueue("报告冲突。")
                val queued = f.server.dispatcher
                val requests = java.util.concurrent.atomic.AtomicInteger()
                f.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        if (requests.incrementAndGet() == 2) check(writeResponse.await(20, java.util.concurrent.TimeUnit.SECONDS))
                        return queued.dispatch(request)
                    }
                }
                main { c.send("遇到冲突必须保留用户内容") }
                await { c.state.value.events.any { it.tool == AiTool.ReadFile && it.status == AiToolStatus.Succeeded } }
                file.writeText("manual edit")
                writeResponse.countDown()
                await { !c.state.value.active }
                val event = c.state.value.events.last()
                assertEquals(AiToolStatus.Failed, event.status); assertEquals(AiPermission.Allow, event.permission)
                assertTrue(event.preview.contains("+AI overwrite")); assertEquals("manual edit", file.readText()); assertTrue(c.state.value.changes.isEmpty())
                assertNull(c.state.value.approval)
            } finally { writeResponse.countDown(); main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
    @Test(timeout = 60000) fun partialToolArgumentsAreVisibleButNeverExecuteWhenStopped() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            var ai: AiController? = null
            try {
                main { ai = AiController(isolated(f), { f.root }, { _, _ -> false }, { _, _ -> error("No command") }, {}, {}, f.transport, f.config, history = f.history); ai!!.select("fixture") }
                val c = ai!!; ready(c)
                val partial = call("partial", AiTool.WriteFile, "partial.txt", "never write")
                val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", partial.id)
                    .put("function", JSONObject().put("name", partial.name).put("arguments", partial.arguments.take(partial.arguments.length / 2)))))
                val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n"
                // Keep a partial SSE record after the first tool delta. Large chunks flush TLS buffers,
                // so observation does not depend on tiny writes remaining buffered until EOF.
                f.server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setChunkedBody(body + " ".repeat(128000), 4096).throttleBody(8192, 500, java.util.concurrent.TimeUnit.MILLISECONDS))
                main { c.send("接收工具参数时停止") }; await { c.state.value.drafts.isNotEmpty() || !c.state.value.active }
                assertTrue("No streamed drafts; phase=${c.state.value.phase}, events=${c.state.value.events.size}, error=${c.state.value.error}", c.state.value.drafts.isNotEmpty())
                assertFalse(File(f.root, "partial.txt").exists()); assertNull(c.state.value.approval)
                main { c.stop() }; await { !c.state.value.active }
                assertEquals(AiPhase.Cancelled, c.state.value.phase)
                assertTrue(c.state.value.drafts.isEmpty()); assertTrue(c.state.value.events.any { it.status == AiToolStatus.Cancelled && it.input.isNotEmpty() })
                assertFalse(File(f.root, "partial.txt").exists())
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
    @Test(timeout = 120000) fun publicPlanAndStreamingUbuntuOutputHaveIndependentTruthfulStatuses() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workbench = (context.applicationContext as ZakoApplication).workbench
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            AiDeviceTest.Fixture(context).use { f ->
                val project = withContext(Dispatchers.IO) { workbench.projects.create(f.token) }
                val root = workbench.projects.directory(project.id); var ai: AiController? = null
                try {
                    main { ai = AiController(isolated(f), workbench.projects::directory, { _, _ -> false }, { _, _ -> error("Use streaming seam") }, {}, {}, f.transport, f.config, history = f.history,
                        streamingCommand = { id, command, output -> workbench.runtime.execute(command, id, workbench.state.value.settings, timeoutMillis = 120000, output = output) }); ai!!.select(project.id) }
                    val c = ai!!; ready(c)
                    val plan = JSONArray().put(JSONObject().put("title", "运行验证命令").put("status", "in_progress"))
                    f.enqueue(calls = listOf(AiCall("plan", "update_plan", JSONObject().put("steps", plan).put("reason", "先说明验证安排").toString()),
                        call("command", AiTool.RunCommand, "printf 'FIRST_OUTPUT\\n'; sleep 2; printf 'LAST_OUTPUT\\n'; exit 7")))
                    f.enqueue("验证命令退出 7，未通过。")
                    main { c.send("公开计划和命令输出") }; await { c.state.value.approval != null }
                    assertEquals("运行验证命令", c.state.value.plan.single().title)
                    main { c.approve(true) }
                    await { c.state.value.events.any { event -> event.tool == AiTool.RunCommand && event.status == AiToolStatus.Running && event.output.contains("FIRST_OUTPUT") } }
                    assertFalse(c.state.value.events.last().output.contains("LAST_OUTPUT"))
                    await { !c.state.value.active }
                    val event = c.state.value.events.last(); assertEquals(7, event.exitCode); assertEquals(AiToolStatus.Failed, event.status)
                    assertTrue(event.output.contains("LAST_OUTPUT")); assertTrue(event.finished!! >= event.started!!)
                    assertEquals(AiPhase.Completed, c.state.value.phase)
                    val schema = AiController.schemas().getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
                    assertEquals("title", schema.getJSONObject("properties").getJSONObject("steps").getJSONObject("items").getJSONArray("required").getString(0))
                } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true }; withContext(Dispatchers.IO) { root.deleteRecursively() } }
            }
        }
    }
}

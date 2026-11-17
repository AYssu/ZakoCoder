package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.capabilities.CapabilityPackage
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local TLS model and stub SSH executor: no real account, credential or remote process is used. */
class AiSshPlanningDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) {
        while (!condition()) delay(10)
    }
    private suspend fun ready(ai: AiController) = await {
        val state = ai.state.value
        state.configured && !state.permissionsLoading && !state.permissionsSaving &&
            !state.skillsLoading && !state.restoring && !ai.models.state.value.loading
    }
    private fun controller(f: AiDeviceTest.Fixture, executed: (String) -> Unit): AiController {
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "ssh-planning-files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "ssh-planning-private").apply { mkdirs() }
        }
        return AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("Planning must never run a phone command") }, {}, {}, f.transport, f.config,
            history = f.history, capabilitySnapshot = { mapOf(CapabilityPackage.Ssh to "fixture-ssh-lease") },
            sshDescription = { "Configured synthetic SSH fixture" },
            sshTool = { _, args, _ ->
                executed(args.getString("action"))
                JSONObject().put("connected", true).put("fixture", true)
            })
    }
    private fun enterPlanning(request: String) = AiCall("enter-readonly", "set_task_mode",
        JSONObject().put("mode", "readonly").put("user_request", request).toString())
    private fun call(id: String, action: String, body: JSONObject = JSONObject()) = AiCall(id, "ssh_remote",
        body.put("action", action).put("reason", "Inspect the configured synthetic remote fixture").toString())
    private fun request(f: AiDeviceTest.Fixture) =
        JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
    private fun sshSchema(schemas: JSONArray): JSONObject = (0 until schemas.length())
        .map(schemas::getJSONObject).single { it.getJSONObject("function").getString("name") == "ssh_remote" }
    private fun actions(schema: JSONObject): Set<String> = schema.getJSONObject("function")
        .getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        .let { values -> (0 until values.length()).map(values::getString).toSet() }

    @Test fun planningNarrowsOnlyItsOwnSshSchemaWithoutMutatingExecutionCatalog() {
        val complete = AiToolRegistry.schemas(screen = true, ssh = true, skills = true, delegation = true, browser = true)
        val before = complete.toString()
        val planning = AiPlanning.schemas(complete, true)
        assertEquals(setOf("status", "list_files", "read_file"), actions(sshSchema(planning)))
        assertNotSame(sshSchema(complete), sshSchema(planning))
        assertEquals("Planning must not change the caller's execution schema", before, complete.toString())
        assertEquals(AiToolRegistry.SSH_ACTIONS.toSet(), actions(sshSchema(complete)))
        assertSame(complete, AiPlanning.schemas(complete, false))
        assertEquals(AiToolRegistry.SSH_ACTIONS.toSet(), actions(sshSchema(AiToolRegistry.schemas(ssh = true))))
    }

    @Test(timeout = 120000) fun readonlyRemoteInspectionStillRequiresApprovalAndThenExecutesOnlyReadActions() = runBlocking<Unit> {
        AiDeviceTest.Fixture(base).use { f ->
            val executed = mutableListOf<String>()
            var ai: AiController? = null
            try {
                main { ai = controller(f, executed::add); ai!!.select("ssh-planning-approved") }
                val current = requireNotNull(ai)
                ready(current)
                main { current.setPolicy(AiPolicy(default = AiPermission.Ask, overrides = mapOf(AiTool.SetTaskMode to AiPermission.Allow))) }
                ready(current)
                val text = "只读检查远程连接和文件，不执行命令或修改"
                f.enqueue(calls = listOf(enterPlanning(text)))
                f.enqueue(calls = listOf(call("read-status", "status"), call("read-list", "list_files", JSONObject().put("path", "")),
                    call("read-file", "read_file", JSONObject().put("path", "README.md"))))
                f.enqueue("已完成获批的远程只读检查，没有执行命令或修改文件。")
                main { assertTrue(current.send(text)) }
                await { current.state.value.approval != null || !current.state.value.active }
                assertTrue(current.state.value.planningMode)
                assertNotNull("Remote reads must not bypass the user's approval policy", current.state.value.approval)
                val firstRead = current.state.value.events.single { it.callId == "read-status" }
                assertEquals(AiTool.SshRemote, firstRead.tool)
                assertEquals(AiToolStatus.Waiting, firstRead.status)
                assertEquals(firstRead.id, current.state.value.approval?.eventId)
                assertTrue("No remote executor before approval", executed.isEmpty())
                main { current.approve(AiDecision.TaskTool) }
                await {
                    !current.state.value.active || current.state.value.approval?.let { it.eventId != firstRead.id } == true
                }
                assertNull(current.state.value.approval)
                assertFalse(current.state.value.active)
                assertEquals(AiPhase.Completed, current.state.value.phase)
                assertEquals("", current.state.value.error)
                assertEquals(listOf("status", "list_files", "read_file"), executed)
                assertTrue(current.state.value.events.filter { it.tool == AiTool.SshRemote }.all { it.status == AiToolStatus.Succeeded })
                assertEquals(AiToolRegistry.SSH_ACTIONS.toSet(), actions(sshSchema(request(f).getJSONArray("tools"))))
                assertEquals(setOf("status", "list_files", "read_file"), actions(sshSchema(request(f).getJSONArray("tools"))))
                val final = request(f).getJSONArray("messages")
                listOf("read-status", "read-list", "read-file").forEach { callId ->
                    assertTrue("Approved remote result must return to the model", (0 until final.length()).any {
                        val message = final.getJSONObject(it)
                        message.optString("role") == "tool" && message.optString("tool_call_id") == callId &&
                            JSONObject(message.getString("content")).optBoolean("fixture")
                    })
                }
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 120000) fun readonlyRejectsUnadvertisedSshExecWritesAndTmuxEvenUnderYolo() = runBlocking<Unit> {
        AiDeviceTest.Fixture(base).use { f ->
            val executed = mutableListOf<String>()
            var ai: AiController? = null
            try {
                main { ai = controller(f, executed::add); ai!!.select("ssh-planning-blocked") }
                val current = requireNotNull(ai)
                ready(current)
                main { current.setPolicy(AiPermissionPresets.apply(current.state.value.policy, AiPermissionPreset.Yolo)) }
                ready(current)
                assertEquals(AiPermission.Allow, current.state.value.policy.permission(AiTool.SshRemote))
                val text = "先只分析远程项目，不运行命令和修改文件"
                f.enqueue(calls = listOf(enterPlanning(text)))
                f.enqueue(calls = listOf(
                    call("illegal-exec", "exec", JSONObject().put("command", "printf must-not-run")),
                    call("illegal-write", "write_file", JSONObject().put("path", "blocked.txt").put("content", "must-not-write")),
                    call("illegal-tmux", "tmux_run", JSONObject().put("command", "printf must-not-start"))))
                f.enqueue("只读限制已阻止远程执行与写入。")
                main { assertTrue(current.send(text)) }
                await { !current.state.value.active || current.state.value.approval != null }
                assertNull("Blocked planning actions must not offer an approval bypass", current.state.value.approval)
                assertFalse(current.state.value.active)
                assertEquals(AiPhase.Completed, current.state.value.phase)
                assertTrue(current.state.value.planningMode)
                assertEquals("", current.state.value.error)
                assertTrue("No prohibited action may reach the SSH executor", executed.isEmpty())
                val events = current.state.value.events.filter { it.tool == AiTool.SshRemote }
                assertEquals(3, events.size)
                events.forEach { event ->
                    assertEquals(AiToolStatus.Denied, event.status)
                    assertEquals(base.getString(R.string.ai_planning_tool_blocked), event.output)
                }
                request(f)
                assertEquals(setOf("status", "list_files", "read_file"), actions(sshSchema(request(f).getJSONArray("tools"))))
                val final = request(f).getJSONArray("messages")
                assertEquals(3, (0 until final.length()).map(final::getJSONObject).count { message ->
                    message.optString("role") == "tool" && message.optString("tool_call_id").startsWith("illegal-") &&
                        message.optString("content").contains(base.getString(R.string.ai_planning_tool_blocked))
                })
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }
}

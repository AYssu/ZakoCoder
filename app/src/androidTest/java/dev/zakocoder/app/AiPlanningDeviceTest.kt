package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local TLS model plus isolated app storage; never uses real models or starts guest processes. */
class AiPlanningDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(20) }
    private suspend fun ready(ai: AiController) = await {
        ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.permissionsSaving &&
            !ai.state.value.skillsLoading && !ai.state.value.restoring && !ai.models.state.value.loading
    }
    private fun context(f: AiDeviceTest.Fixture) = object : ContextWrapper(base) {
        override fun getFilesDir() = File(f.root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }

    @Test(timeout = 120000) fun yoloPlanningRejectsUnadvertisedWriteAndStillReturnsFinalAnswer() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "existing.txt").writeText("keep original\n")
            var commands = 0
            var ai: AiController? = null
            try {
                main {
                    ai = AiController(context(f), { project }, { _, _ -> false },
                        { _, _ -> commands++; error("Planning must not execute commands") }, {}, {}, f.transport, f.config, history = f.history)
                    ai!!.select("planning-fixture")
                }
                val controller = requireNotNull(ai)
                ready(controller)
                main { controller.setPolicy(AiPermissionPresets.apply(controller.state.value.policy, AiPermissionPreset.Yolo)) }
                ready(controller)
                assertEquals(AiPermission.Allow, controller.state.value.policy.permission(AiTool.WriteFile))
                assertFalse(controller.state.value.planningMode)
                val requestText = "先分析并给出计划，不执行修改"
                f.enqueue(calls = listOf(AiCall("enter-readonly", "set_task_mode", JSONObject()
                    .put("mode", "readonly").put("user_request", requestText).toString())))
                f.enqueue(calls = listOf(AiCall("self-unlock", "set_task_mode", JSONObject()
                    .put("mode", "execute").put("user_request", requestText).toString())))
                f.enqueue(calls = listOf(AiCall("illegal-write", "write_file", JSONObject().put("path", "existing.txt")
                    .put("content", "must never replace the original").put("reason", "A model ignored the read-only mode").toString())), responseDelayMillis = 250)
                f.enqueue("这里只提供实现计划，尚未修改任何文件。")
                main {
                    assertTrue(controller.send(requestText))
                }
                await { !controller.state.value.active || controller.state.value.approval != null }
                assertNull("Illegal planning tools must be rejected without opening an approval bypass", controller.state.value.approval)
                assertEquals(AiPhase.Completed, controller.state.value.phase)
                assertEquals("", controller.state.value.error)
                assertEquals("keep original\n", File(project, "existing.txt").readText())
                assertEquals(0, commands)
                val event = controller.state.value.events.single { it.tool == AiTool.WriteFile }
                assertEquals(AiTool.WriteFile, event.tool)
                assertEquals(AiToolStatus.Denied, event.status)
                assertEquals(base.getString(R.string.ai_planning_tool_blocked), event.output)
                assertTrue(controller.state.value.timeline.any { it.role == AiRole.Assistant && it.text.contains("尚未修改任何文件") })

                val initial = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                assertTrue("New tasks default to execution", initial.getJSONArray("tools").toString().contains("write_file"))
                val first = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
                val advertised = first.getJSONArray("tools")
                assertTrue("Planning should still advertise useful inspection tools", advertised.length() > 0)
                repeat(advertised.length()) { index ->
                    val tool = AiTool.named(advertised.getJSONObject(index).getJSONObject("function").getString("name"))
                    assertNotNull(tool)
                    assertTrue(AiPlanning.allows(requireNotNull(tool)))
                }
                assertTrue(first.getJSONArray("messages").toString().contains("Read-only planning mode"))
                requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) // rejected self-unlock result
                val second = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).getJSONArray("messages")
                assertTrue("The model must see the denied tool result and be able to finish", (0 until second.length()).any {
                    second.getJSONObject(it).optString("role") == "tool" && second.getJSONObject(it).optString("tool_call_id") == "illegal-write" &&
                        second.getJSONObject(it).optString("content").contains(base.getString(R.string.ai_planning_tool_blocked))
                })
                assertEquals(4, f.server.requestCount)
                val restored = withContext(Dispatchers.IO) { requireNotNull(f.history.load("planning-fixture").state) }
                assertTrue(restored.planningMode)
                assertEquals(AiToolStatus.Denied, restored.events.single { it.tool == AiTool.WriteFile }.status)
                f.enqueue("收到，按新要求执行。")
                main { assertTrue(controller.send("开始实施")) }
                await { !controller.state.value.active }
                assertFalse("A new user task defaults to execution without a manual switch", controller.state.value.planningMode)
                assertEquals(AiPhase.Completed, controller.state.value.phase)
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test fun planningToolSchemasDropUnknownAndSideEffectToolsWithoutChangingExecutionSchemas() {
        val schemas = JSONArray().apply {
            AiTool.entries.forEach { put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.wire))) }
            put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "new_unknown_mutating_tool")))
        }
        val selected = AiPlanning.schemas(schemas, true)
        val names = (0 until selected.length()).map { selected.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        assertEquals(AiTool.entries.filter(AiPlanning::allows).map { it.wire }.toSet(), names)
        assertFalse("Unknown tools must not become planning-capable", "new_unknown_mutating_tool" in names)
        assertSame("Execution mode must retain the original schemas", schemas, AiPlanning.schemas(schemas, false))
        assertEquals(AiTool.entries.size + 1, schemas.length())
    }
}

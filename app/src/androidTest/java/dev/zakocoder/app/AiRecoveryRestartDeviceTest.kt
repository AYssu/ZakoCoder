package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Host force-stops the process between these explicitly enabled phases; only owned fixture history is touched. */
class AiRecoveryRestartDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry get() = File(context.noBackupFilesDir, "ai-recovery-probe-owned.json")
    private val scratch get() = File(context.cacheDir, "ai-recovery-probe-owned").apply { mkdirs() }
    private val history get() = AiHistoryStore(context)
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(30) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("restartProbe") == "true")
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = scratch }
        return AiController(isolated, { scratch }, { _, _ -> false }, { _, _ ->
            val count = File(scratch, "executions.txt")
            val value = if (count.exists()) count.readText().toInt() else 0
            count.writeText((value + 1).toString()); CommandResult(0, "RESTART_ACTION_DONE_ONCE")
        }, {}, {}, f.transport, f.config, history = history)
    }
    @Test(timeout = 120000) fun saveFailedTaskBeforeProcessDeath() = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            check(!registry.exists())
            val project = "recovery-probe-${UUID.randomUUID()}"
            registry.writeText(JSONObject().put("project", project).toString())
            var retained = false
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                try {
                    main { ai = controller(f); ai.select(project) }
                    await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                    f.enqueue(calls = listOf(AiCall("once", "run_command", "{\"command\":\"python3 task.py\",\"reason\":\"验证重启后不会重复执行\"}")))
                    f.enqueue("检查完成一部分", done = false)
                    main { ai.send("RESTART_ORIGINAL_REQUEST 完成任务并验证") }; await { ai.state.value.approval != null }; main { ai.approve(true) }
                    await { !ai.state.value.active }
                    assertEquals(AiFailure.Protocol, ai.state.value.failureKind)
                    assertEquals("1", File(scratch, "executions.txt").readText())
                    assertEquals("missing_done", ai.state.value.requests.last().diagnostic)
                    registry.writeText(JSONObject().put("project", project).put("conversation", ai.state.value.conversationId).toString())
                    retained = true
                } finally {
                    if (!retained) withContext(Dispatchers.IO) { history.deleteProject(project); registry.delete(); scratch.deleteRecursively() }
                }
            }
        }
    }
    @Test(timeout = 120000) fun restoreFailedTaskAfterProcessDeathAndContinue() = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            val owned = JSONObject(registry.readText()); val project = owned.getString("project")
            AiDeviceTest.Fixture(context).use { f ->
                var ai: AiController? = null
                try {
                    main { ai = controller(f); ai!!.select(project) }; val c = ai!!
                    await { c.state.value.configured && !c.state.value.permissionsLoading }
                    assertEquals(owned.getString("conversation"), c.state.value.conversationId)
                    assertEquals(AiFailure.Protocol, c.state.value.failureKind); assertEquals("missing_done", c.state.value.requests.last().diagnostic)
                    assertTrue(c.state.value.recoveryNotes.single().contains("RESTART_ACTION_DONE_ONCE"))
                    assertNull(c.state.value.approval); assertFalse(c.state.value.active)
                    f.enqueue("先查看已修改的内容再继续验证，不重复之前的命令。")
                    main { c.send("继续") }; await { !c.state.value.active }
                    val payload = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages").toString()
                    assertTrue(payload.contains("RESTART_ORIGINAL_REQUEST")); assertTrue(payload.contains("RESTART_ACTION_DONE_ONCE")); assertTrue(payload.contains("missing_done"))
                    assertEquals("1", File(scratch, "executions.txt").readText()); assertEquals(AiPhase.Completed, c.state.value.phase)
                } finally {
                    main { ai?.stop() }; await { ai?.state?.value?.active != true }
                    withContext(Dispatchers.IO) { history.deleteProject(project); registry.delete(); scratch.deleteRecursively() }
                }
            }
        }
    }
}

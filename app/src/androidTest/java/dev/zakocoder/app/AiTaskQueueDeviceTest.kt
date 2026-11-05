package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AiTaskQueueDeviceTest {
    @Before fun foregroundForDeviceNetwork() { warmDeviceActivity() }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private fun diagnostic(ai: AiController) = ai.state.value.let {
        "phase=${it.phase}, active=${it.active}, turn=${it.turn}, failure=${it.failureKind}, historyError=${it.historyError}, skillsLoading=${it.skillsLoading}\n${it.taskDiagnostic}"
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(20000) { while (!predicate()) delay(30) }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = f.root
            override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${f.token}-$name", mode)
        }
        return AiController(isolated, { f.root }, { _, _ -> false }, { _, _ -> error("No commands expected") }, {}, {},
            f.transport, f.config, permissionStore = AiPermissionStore(isolated), history = f.history)
    }
    private suspend fun start(f: AiDeviceTest.Fixture): AiController {
        lateinit var ai: AiController
        main { ai = controller(f); ai.select("fixture") }
        await { ai.state.value.configured && !ai.models.state.value.loading && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
        return ai
    }
    @Test(timeout = 60000) fun steeringWaitsForWholeToolBatchAndDoesNotGrantPermission() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = start(f)
            try {
                f.enqueue(calls = listOf(AiCall("write", "write_file", JSONObject().put("path", "a.txt").put("content", "first").toString()),
                    AiCall("second", "write_file", JSONObject().put("path", "b.txt").put("content", "second").toString())))
                f.enqueue("已接收补充要求")
                main { assertTrue(ai.send("创建两个文件")) }
                await { ai.state.value.approval != null }
                main { assertTrue(ai.enqueue("下一轮先检查，不要继续修改", AiQueuedKind.Steer)); ai.approve(false) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertFalse(File(f.root, "a.txt").exists()); assertFalse(File(f.root, "b.txt").exists())
                assertEquals(1, ai.state.value.events.count { it.status == AiToolStatus.Denied })
                assertEquals(1, ai.state.value.events.count { it.status == AiToolStatus.Cancelled })
                assertEquals(AiQueuedStatus.Applied, ai.state.value.queuedMessages.single().status)
                f.server.takeRequest(5, TimeUnit.SECONDS)
                val messages = JSONObject(requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()).getJSONArray("messages")
                val objects = (0 until messages.length()).map(messages::getJSONObject)
                assertTrue(objects.none { it.has("zako_steering") })
                val steering = objects.indexOfFirst { it.optString("content") == "下一轮先检查，不要继续修改" }
                assertTrue(steering > objects.indexOfFirst { it.optString("tool_call_id") == "write" })
                assertTrue(steering > objects.indexOfFirst { it.optString("tool_call_id") == "second" })
            } finally { main { ai.stop() } }
        }
    }
    @Test(timeout = 60000) fun followupStartsNewTurnOnlyAfterSuccess() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = start(f)
            try {
                f.enqueue("第一轮完成", responseDelayMillis = 500); f.enqueue("第二轮完成")
                main { assertTrue(ai.send("第一项")); assertTrue(ai.enqueue("第二项", AiQueuedKind.FollowUp)) }
                await { !ai.state.value.active && (ai.state.value.turn == 2 || ai.state.value.phase == AiPhase.Failed) }
                assertEquals(diagnostic(ai), AiPhase.Completed, ai.state.value.phase)
                assertEquals(diagnostic(ai), 2, f.server.requestCount)
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(AiQueuedStatus.Applied, ai.state.value.queuedMessages.single().status)
                assertEquals(listOf("第一项", "第二项"), ai.state.value.timeline.filter { it.role == AiRole.User }.map { it.text })
                assertEquals(2, ai.state.value.turnTimings.size)
            } finally { main { ai.stop() } }
        }
    }
    @Test(timeout = 60000) fun failedTaskKeepsQueueAcrossRestartWithoutAutoRequest() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = start(f)
            var restored: AiController? = null
            try {
                f.enqueue("未结束", done = false, responseDelayMillis = 500)
                main { assertTrue(ai.send("原任务")); assertTrue(ai.enqueue("后续任务", AiQueuedKind.FollowUp)) }
                await { !ai.state.value.active }
                assertEquals(diagnostic(ai), AiPhase.Failed, ai.state.value.phase)
                assertEquals(diagnostic(ai), 1, f.server.requestCount)
                main { restored = controller(f); restored!!.select("fixture") }
                val fresh = restored!!
                await { fresh.state.value.configured && !fresh.models.state.value.loading && !fresh.state.value.permissionsLoading && !fresh.state.value.skillsLoading }
                assertFalse(fresh.state.value.active)
                assertEquals(1, f.server.requestCount)
                val queued = fresh.state.value.queuedMessages.single()
                assertEquals(AiQueuedStatus.Pending, queued.status)
                f.enqueue("后续任务已完成")
                main { assertTrue(fresh.startQueued(queued.id)) }
                await { !fresh.state.value.active }
                assertEquals(AiQueuedStatus.Applied, fresh.state.value.queuedMessages.single().status)
                assertEquals(2, f.server.requestCount)
            } finally { main { ai.stop(); restored?.stop() } }
        }
    }
    @Test(timeout = 60000) fun stoppedQueueDoesNotAttachToUnrelatedNewTask() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = start(f)
            try {
                f.enqueue("原任务输出", responseDelayMillis = 500)
                main { assertTrue(ai.send("原任务")); assertTrue(ai.enqueue("旧补充", AiQueuedKind.Steer));
                    assertTrue(ai.enqueue("旧后续", AiQueuedKind.FollowUp)) }
                await { f.server.requestCount == 1 || (!ai.state.value.active && ai.state.value.phase == AiPhase.Failed) }
                assertEquals(diagnostic(ai), 1, f.server.requestCount)
                main { ai.stop() }
                await { !ai.state.value.active }
                f.enqueue("新任务完成")
                main { assertTrue(ai.send("独立的新任务")) }
                await { !ai.state.value.active }
                assertEquals(2, f.server.requestCount)
                assertEquals(2, ai.state.value.queuedMessages.count { it.status == AiQueuedStatus.Pending })
                f.server.takeRequest(5, TimeUnit.SECONDS)
                val request = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                assertFalse(request.contains("旧补充")); assertFalse(request.contains("旧后续"))
            } finally { main { ai.stop() } }
        }
    }
    @Test fun historyRoundtripAndLegacyDefaults() {
        AiDeviceTest.Fixture(context).use { f ->
            val queue = listOf(AiQueuedMessage(text = "补充", kind = AiQueuedKind.Steer, turn = 1),
                AiQueuedMessage(text = "稍后", kind = AiQueuedKind.FollowUp, turn = 1))
            f.history.save("fixture", AiState(turn = 1, queuedMessages = queue))
            assertEquals(queue, f.history.load("fixture").state!!.queuedMessages)
            assertTrue(AiTaskQueue.decode(null).isEmpty())
        }
    }
}

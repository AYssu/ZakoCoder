package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real application page, actual composer callbacks and isolated TLS controller; synthetic project only. */
class AiTaskQueuePageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(descendants(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root?.takeIf { root -> root.refresh() } }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::descendants)
    }
    private fun find(label: String) = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString()?.lines()?.contains(label) == true ||
            it.contentDescription?.toString()?.lines()?.contains(label) == true)
    }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(25000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private suspend fun click(label: String) {
        fun target(): AccessibilityNodeInfo? {
            var node = find(label) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node.takeIf { it.isEnabled && it.isClickable }
        }
        await("Enabled action: $label") { target() != null }
        assertTrue("Click $label", requireNotNull(target()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(120)
    }
    private suspend fun enter(text: String) {
        await("Enabled actual composer") { nodes().any { it.isVisibleToUser && it.isEditable && it.isEnabled } }
        val composer = requireNotNull(nodes().firstOrNull { it.isVisibleToUser && it.isEditable && it.isEnabled })
        assertTrue("Set actual composer text", composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        await("Composer displays input") { nodes().any { it.isEditable && it.text?.toString() == text } }
    }
    private suspend fun screenshot(name: String) {
        delay(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-task-queue-page/$name.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private class Replies : Dispatcher() {
        data class Reply(val text: String, val calls: List<AiCall>, val gate: CountDownLatch?)
        private val replies = LinkedBlockingQueue<Reply>()
        fun add(text: String = "", calls: List<AiCall> = emptyList(), gate: CountDownLatch? = null) { replies.put(Reply(text, calls, gate)) }
        override fun dispatch(request: RecordedRequest): MockResponse {
            val reply = replies.poll(30, TimeUnit.SECONDS) ?: return MockResponse().setResponseCode(500)
            if (reply.gate?.await(45, TimeUnit.SECONDS) == false) return MockResponse().setResponseCode(504)
            val delta = JSONObject().put("content", reply.text)
            if (reply.calls.isNotEmpty()) delta.put("tool_calls", JSONArray(reply.calls.mapIndexed { index, call ->
                JSONObject().put("index", index).put("id", call.id).put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
            }))
            val body = "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))}\n\n" +
                "data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", if (reply.calls.isEmpty()) "stop" else "tool_calls")))}\n\n" +
                "data: [DONE]\n\n"
            return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
        }
    }

    @Test(timeout = 240000) fun dayActualComposerSteeringQueueRemovalStopAndContinue() = runBlocking { exercise(false) }
    @Test(timeout = 240000) fun nightActualComposerSteeringQueueRemovalStopAndContinue() = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = this.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context, timeoutSeconds = 90, readTimeoutSeconds = 60) }
        val replies = Replies()
        fixture.server.dispatcher = replies
        val firstGate = CountDownLatch(1)
        val stoppedGate = CountDownLatch(1)
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val theme = if (dark) "night" else "day"
        val previousNight = withContext(Dispatchers.IO) { shell("cmd uimode night").substringAfter(":").trim() }
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        try {
            withContext(Dispatchers.IO) {
                shell("cmd uimode night ${if (dark) "yes" else "no"}")
                fixture.config.save(fixture.server.url("/v1").toString(), "gpt-6.1-sol", "test-only-placeholder")
            }
            withContext(Dispatchers.Main) {
                owned = Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config,
                    aiHistory = fixture.history, aiPermissions = AiPermissionStore(isolated))
            }
            val workbench = requireNotNull(owned)
            await("Workbench initialized") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            withContext(Dispatchers.Main) { workbench.create("移动端队列验收") }
            await("Synthetic project created") { !workbench.state.value.busy && workbench.state.value.project != null }
            withContext(Dispatchers.Main) { workbench.ai.select(workbench.state.value.selectedId) }
            val ai = workbench.ai
            await("Project assistant ready") { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
            replies.add(calls = listOf("one", "two").map { id -> AiCall(id, "write_file", JSONObject()
                .put("path", "$id.txt").put("content", "never write").put("reason", "测试未执行工具的完整回执").toString()) }, gate = firstGate)
            replies.add("已收到补充要求，没有修改文件。")
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ZakoCoderApp(workbench) } }
                click(context.getString(R.string.app_navigation_open)); click(context.getString(R.string.nav_ai))
                enter("先创建两个测试文件")
                click(context.getString(R.string.ai_send))
                await("First actual request in flight") { ai.state.value.active && fixture.server.requestCount == 1 }
                val supplement = "先不要写文件，检查现有实现"
                enter(supplement)
                await("Both running input actions visible") { find(context.getString(R.string.ai_queue_steer)) != null && find(context.getString(R.string.ai_queue_followup)) != null }
                screenshot("$theme-running-supplement")
                click(context.getString(R.string.ai_queue_steer))
                await("Actual controller holds pending supplement") { ai.state.value.queuedMessages.any { it.text == supplement && it.status == AiQueuedStatus.Pending } }
                await("Composer cleared by supplement callback") { nodes().any { it.isEditable && it.text.isNullOrEmpty() } }
                await("Pending supplement is rendered") { find(supplement) != null }
                screenshot("$theme-supplement-pending")
                firstGate.countDown()
                await("Supplement applied and task completed") { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                assertEquals(listOf(AiToolStatus.Cancelled, AiToolStatus.Cancelled), ai.state.value.events.map { it.status })
                assertEquals(AiPermission.Ask, ai.state.value.policy.permission(AiTool.WriteFile))
                assertTrue(ai.state.value.timeline.any { it.role == AiRole.User && it.originalInput == supplement })
                val transcript = JSONArray(ai.state.value.contextTurns.single())
                val messages = (0 until transcript.length()).map(transcript::getJSONObject)
                val supplementIndex = messages.indexOfFirst { it.optString("content") == supplement }
                assertTrue(supplementIndex > 0)
                for (id in listOf("one", "two")) assertTrue(messages.indexOfFirst { it.optString("tool_call_id") == id } in 0 until supplementIndex)
                screenshot("$theme-supplement-applied")

                replies.add(text = "任务停止后的响应不会被继续执行", gate = stoppedGate)
                enter("整理下一阶段检查清单"); click(context.getString(R.string.ai_send))
                await("Second task is in flight") { ai.state.value.active && fixture.server.requestCount == 3 }
                enter("完成后运行单元测试"); click(context.getString(R.string.ai_queue_followup))
                await("Follow-up queued through actual page") { ai.state.value.queuedMessages.count { it.status == AiQueuedStatus.Pending } == 1 }
                click(context.getString(R.string.ai_queue_cancel))
                await("Remove callback cancels queued item") { ai.state.value.queuedMessages.none { it.status == AiQueuedStatus.Pending } }
                val next = "仅列出验证步骤，不执行修改"
                enter(next); click(context.getString(R.string.ai_queue_followup))
                await("Replacement follow-up is pending") { ai.state.value.queuedMessages.singleOrNull { it.status == AiQueuedStatus.Pending }?.text == next }
                await("Pending follow-up is rendered") { find(next) != null }
                screenshot("$theme-followup-pending")
                click(context.getString(R.string.ai_stop))
                stoppedGate.countDown()
                await("Stopped task keeps queue without auto-start") { !ai.state.value.active && ai.state.value.phase == AiPhase.Cancelled }
                assertEquals(3, fixture.server.requestCount)
                await("Explicit continue action visible") { find(context.getString(R.string.ai_queue_continue)) != null }
                screenshot("$theme-stopped-continue")
                replies.add(text = "验证步骤已列出，未执行修改。")
                click(context.getString(R.string.ai_queue_continue))
                await("Explicit continuation completes") { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed && fixture.server.requestCount == 4 }
                assertEquals(AiQueuedStatus.Applied, ai.state.value.queuedMessages.single { it.text == next }.status)
                assertEquals(1, ai.state.value.timeline.count { it.role == AiRole.User && it.originalInput == next })
                assertTrue(ai.state.value.queuedMessages.any { it.text == "完成后运行单元测试" && it.status == AiQueuedStatus.Cancelled })
                assertEquals("", ai.state.value.error)
                screenshot("$theme-explicitly-continued")
            }
        } finally {
            firstGate.countDown(); stoppedGate.countDown()
            withContext(Dispatchers.Main) { owned?.stopAll() }
            withContext(Dispatchers.IO) { try { fixture.close() } finally { shell("cmd uimode night $previousNight") } }
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = previousFlags }
        }
    }
}

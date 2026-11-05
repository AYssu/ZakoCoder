package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.QueueDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Local TLS model + private workbench only. Actual approval UI never edits user projects. */
class AiLocalEditUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(25000) { while (!condition()) delay(30) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun targets(label: String): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true ||
                node.contentDescription?.toString()?.lines()?.contains(label) == true)) add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(walk(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::walk)
    }
    private suspend fun click(label: String) {
        fun target(): AccessibilityNodeInfo? {
            val matches = targets(label)
            fun actionable(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            matches.firstOrNull(::actionable)?.let { return it }
            return matches.firstNotNullOfOrNull {
                var node = it.parent
                while (node != null && !actionable(node)) node = node.parent
                node
            }
        }
        await("Clickable $label") { target() != null }
        assertTrue(requireNotNull(target()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(150)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
    private suspend fun screenshot(name: String) {
        delay(100)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/local-edit/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } } finally { bitmap.recycle() }
    }

    @Test(timeout = 120000) fun dayEditPreviewRequiresApprovalAndRejectPreservesSource() = runBlocking { exercise(false) }
    @Test(timeout = 120000) fun nightEditPreviewRequiresApprovalAndRejectPreservesSource() = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = this.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previousNight = withContext(Dispatchers.IO) { shell("cmd uimode night").substringAfter(":").trim() }
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context, timeoutSeconds = 30) }
        // MockWebServer also uses peek().throttleBody while reading REQUEST bytes.
        // Keep uploads unthrottled; dispatch() still returns queued responses with
        // their real output pacing, preserving a screenshotable streaming draft.
        val responses = QueueDispatcher()
        fixture.server.dispatcher = object : Dispatcher() {
            override fun peek() = MockResponse()
            override fun dispatch(request: RecordedRequest) = responses.dispatch(request)
            override fun shutdown() = responses.shutdown()
        }
        fun enqueue(text: String = "", calls: List<AiCall> = emptyList(), delayMillis: Long = 1) {
            val deltas = mutableListOf<JSONObject>()
            if (text.isNotEmpty()) deltas += JSONObject().put("content", text)
            calls.forEachIndexed { index, call ->
                val split = call.arguments.length / 2
                deltas += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index)
                    .put("id", call.id).put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.take(split)))))
                deltas += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index)
                    .put("function", JSONObject().put("arguments", call.arguments.drop(split)))))
            }
            val body = buildString {
                deltas.forEach { append("data: ").append(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", it)))).append("\n\n") }
                append("data: ").append(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject())
                    .put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")))).append("\n\ndata: [DONE]\n\n")
            }
            responses.enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(body).throttleBody(37, delayMillis, TimeUnit.MILLISECONDS))
        }
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val key = "${fixture.token}-$name"
                synchronized(preferences) { preferences.add(key) }
                return context.getSharedPreferences(key, mode)
            }
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val theme = if (dark) "night" else "day"
        try {
            withContext(Dispatchers.IO) { shell("cmd uimode night ${if (dark) "yes" else "no"}") }
            main { owned = Workbench(isolated, fixture.transport, fixture.config, AiPermissionStore(isolated), fixture.history) }
            val workbench = requireNotNull(owned)
            await("Private workbench") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            main { workbench.create("局部修改审批验收") }
            await("Private project") { !workbench.state.value.busy && workbench.state.value.projects.size == 1 }
            val project = workbench.state.value.projects.single()
            val source = File(workbench.projects.directory(project.id), "sample.py")
            withContext(Dispatchers.IO) { source.writeText(SOURCE) }
            main { workbench.ai.select(project.id) }
            await("AI ready") { !workbench.ai.state.value.permissionsLoading && !workbench.ai.state.value.skillsLoading && workbench.ai.state.value.configured }
            enqueue(calls = listOf(AiCall("read-source", "read_file", JSONObject().put("path", "sample.py").toString())))
            enqueue(calls = listOf(AiCall("rename-function", "edit_file", JSONObject().put("path", "sample.py")
                .put("reason", "仅重命名函数，保留参数与函数体")
                .put("edits", JSONArray().put(JSONObject().put("old_text", "def greet(name):").put("new_text", "def greet_user(name):"))).toString())), delayMillis = 180)
            enqueue("已尊重拒绝，未修改源码。")
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { ZakoCoderApp(workbench) } }
                instrumentation.waitForIdleSync()
                if (targets(context.getString(R.string.nav_ai)).isEmpty()) click(context.getString(R.string.app_navigation_open))
                click(context.getString(R.string.nav_ai))
                main { assertTrue(workbench.ai.send("读取 sample.py，用局部编辑把 greet 重命名为 greet_user，函数体不变。")) }
                await("Streaming edit preview") { workbench.ai.state.value.drafts.any { it.name == "edit_file" && it.arguments.contains("sample.py") } }
                screenshot("$theme-streaming-preview")
                await("Edit approval pending") { workbench.ai.state.value.approval?.review?.path == "sample.py" }
                assertTrue(workbench.ai.state.value.events.any { it.tool == AiTool.ReadFile && it.status == AiToolStatus.Succeeded })
                assertEquals(SOURCE, withContext(Dispatchers.IO) { source.readText() })
                val review = requireNotNull(workbench.ai.state.value.approval?.review)
                assertEquals(SOURCE, review.before)
                assertEquals(SOURCE.replace("def greet(", "def greet_user("), review.after)
                await("Reject action visible") { targets(context.getString(R.string.ai_reject)).isNotEmpty() }
                screenshot("$theme-approval-preview")
                click(context.getString(R.string.ai_reject))
                await("Denied task finished") { !workbench.ai.state.value.active }
                assertEquals(SOURCE, withContext(Dispatchers.IO) { source.readText() })
                assertEquals(AiToolStatus.Denied, workbench.ai.state.value.events.single { it.callId == "rename-function" }.status)
                assertEquals(3, fixture.server.requestCount)
                assertTrue(workbench.state.value.sessions.isEmpty())
                screenshot("$theme-rejected-unchanged")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { owned?.stopAll() }
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    fixture.close()
                    synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
                } finally { shell("cmd uimode night $previousNight") }
            }
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
        }
    }
    private companion object { const val SOURCE = "def greet(name):\n    return f\"Hello, {name}!\"\n" }
}

package dev.zakocoder.app

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.screentest.ScreenTestAccessibilityService
import dev.zakocoder.app.screentest.ScreenTestController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Local HTTPS model fixture -> real AI approval/executor -> real controller -> real installed app.
 * Requires /data/local/tmp/fixture.apk, its installed Debug app and explicitly enabled host service.
 * Does not build/install pretend artifacts, change system settings or use a provider credential.
 */
@SdkSuppress(minSdkVersion = 30)
class AiAndroidWorkflowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test(timeout = 180000)
    fun approvedAiTaskLaunchesObservesClicksVerifiesAndSharesActualPixelsThenRevokesTarget() = runBlocking {
        automation // Preserve the service explicitly enabled by the device test owner.
        await { ScreenTestAccessibilityService.isConnected() }
        AiDeviceTest.Fixture(base, timeoutSeconds = 45, readTimeoutSeconds = 30).use { fixture ->
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(fixture.root, "controller-files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "controller-private").apply { mkdirs() }
                override fun getCacheDir() = File(fixture.root, "controller-cache").apply { mkdirs() }
            }
            val project = withContext(Dispatchers.IO) { File(fixture.root, "project").apply { check(mkdirs()) } }
            val screenshots = Collections.synchronizedList(mutableListOf<Uri>())
            val screen = ScreenTestController(isolated) { id -> check(id == PROJECT); project }
            var ai: AiController? = null
            try {
                withContext(Dispatchers.IO) {
                    automation.executeShellCommand("cat /data/local/tmp/fixture.apk").use { descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            File(project, "app-debug.apk").outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                shell("am force-stop $TARGET")
                screen.setAccess(ScreenTestAccess.Accessibility)
                val model = WorkflowModel()
                fixture.server.dispatcher = model
                main {
                    ai = AiController(isolated, { id -> check(id == PROJECT); project }, { _, _ -> false },
                        { _, _ -> error("This screen workflow must not run a host or guest command") }, {}, {},
                        fixture.transport, fixture.config, history = fixture.history,
                        capabilitySnapshot = { mapOf(dev.zakocoder.app.capabilities.CapabilityPackage.Screen to "screen-package-test") },
            screenTestTool = { id, args, lease ->
                            check(ai?.state?.value?.let { it.active && it.projectId == id } == true)
                            check(id == PROJECT)
                            screen.execute(id, args, lease).also { result -> result.imageUri?.let(screenshots::add) }
                        },
                        screenTestLease = screen::taskLease,
                        screenTestDescription = { id -> "$id · ${screen.state.value.packageName.orEmpty()} · Accessibility" },
                        finishScreenTestTask = { id, lease -> screen.finishTask(id, lease) },
                        stopScreenTest = screen::stop,
                        shareScreenTestImages = { id -> screen.state.value.projectId == id && screen.state.value.shareScreenshots })
                    ai!!.select(PROJECT)
                }
                val controller = requireNotNull(ai)
                await { controller.state.value.configured && !controller.state.value.permissionsLoading &&
                    !controller.state.value.skillsLoading && !controller.models.state.value.loading }
                // Selecting an AI project revokes any preceding screen target. Authorize only
                // after that project transition, exactly as the user-facing panel does.
                val selected = screen.selectApk(PROJECT, "app-debug.apk")
                assertEquals(TARGET, selected.packageName)
                assertTrue(selected.debuggable)
                assertTrue(screen.state.value.enabled && screen.state.value.connected)
                val originalLease = requireNotNull(screen.taskLease(PROJECT))
                assertFalse(screen.state.value.shareScreenshots)
                // This simulates the explicit user confirmation, not a model-provided option.
                screen.setScreenshotSharing(PROJECT, true)

                main { assertTrue(controller.send("对已构建并安装的调试应用执行界面测试：启动，点击 Increment，验证 Counter: 1，再检查截图。不要声称执行了构建或安装。")) }
                await { controller.state.value.approval != null }
                assertTrue(controller.state.value.approval!!.detail.contains(TARGET))
                assertEquals(0, controller.state.value.events.count { it.status == AiToolStatus.Succeeded })
                assertEquals(originalLease, screen.taskLease(PROJECT))
                main { controller.approve(AiDecision.TaskTool) }
                await { !controller.state.value.active }

                assertNull("Local model fixture validation: ${model.failure.get()}", model.failure.get())
                assertEquals("", controller.state.value.error)
                assertEquals(7, fixture.server.requestCount)
                assertTrue("Model used an actual observation snapshot and node ID", model.usedObservedNode.get())
                assertTrue("Subsequent observation confirmed the actual UI counter", model.observedIncrement.get())
                assertTrue("Next actual model request contains decodable captured pixels", model.receivedPixels.get())
                assertEquals(6, controller.state.value.events.size)
                assertTrue(controller.state.value.events.all { it.tool == AiTool.AndroidTest && it.status == AiToolStatus.Succeeded })
                assertNull(controller.state.value.approval)

                val report = screen.readReport(PROJECT)
                val entries = report.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                assertEquals(listOf("select", "launch", "observe", "click", "observe", "assert_text", "screenshot", "finish"),
                    entries.map { it.getString("action") }.filter { it != "stop" })
                assertEquals("task_finished", entries.single { it.getString("action") == "stop" }.getString("detail"))
                assertTrue(entries.all { it.getBoolean("operationSucceeded") })
                assertTrue(entries.single { it.getString("action") == "assert_text" }.getBoolean("assertionPassed"))
                assertEquals("requested_observe_to_verify", entries.single { it.getString("action") == "click" }.getString("outcome"))
                assertEquals(1, entries.map { it.getString("session") }.distinct().size)
                assertFalse(screen.state.value.active)
                assertFalse(screen.state.value.shareScreenshots)
                assertNull(screen.taskLease(PROJECT))
                assertNull(screen.state.value.packageName)

                val saved = fixture.history.load(PROJECT).state!!
                assertEquals(6, saved.events.size)
                assertTrue(saved.events.all { it.status == AiToolStatus.Succeeded })
                assertFalse(saved.contextTurns.any { "data:image" in it || "zako_images" in it })
                assertTrue(controller.state.value.requests.all { "data:image" !in it.request })
                assertTrue(File(fixture.root, "controller-private/ai-images-v1").listFiles().orEmpty().isEmpty())
                assertEquals(1, screenshots.size)
                withContext(Dispatchers.IO) {
                    val evidence = File(base.cacheDir, "ui-review/screen-testing").apply { check(mkdirs() || isDirectory) }
                    File(evidence, "ai-workflow-report.jsonl").writeText(report)
                    base.contentResolver.openInputStream(screenshots.single()).use { input ->
                        File(evidence, "ai-workflow-target.png").outputStream().use { output -> requireNotNull(input).copyTo(output) }
                    }
                }
                // After normal completion the original authorization cannot perform another action.
                assertTrue(runCatching { screen.execute(PROJECT, JSONObject().put("action", "observe"), originalLease) }.isFailure)
            } finally {
                main { ai?.stop() }
                await { ai?.state?.value?.active != true }
                screen.stop()
                shell("am force-stop $TARGET")
                withContext(Dispatchers.IO) {
                    // Only files produced by this fixture are removed, never another test's evidence.
                    screenshots.forEach { uri -> File(base.filesDir, "screen-test/evidence/${uri.lastPathSegment}").delete() }
                }
            }
        }
    }

    /** Each response is derived from the preceding real tool result, not a prewritten snapshot. */
    private class WorkflowModel : Dispatcher() {
        private val round = AtomicInteger()
        val failure = AtomicReference<String?>()
        val usedObservedNode = AtomicBoolean()
        val observedIncrement = AtomicBoolean()
        val receivedPixels = AtomicBoolean()
        override fun dispatch(request: RecordedRequest): MockResponse = try {
            val body = JSONObject(request.body.readUtf8())
            when (round.getAndIncrement()) {
                0 -> response("launch")
                1 -> {
                    require(tool(body, "android-launch").isNotBlank())
                    awaitTargetReady()
                    response("observe")
                }
                2 -> {
                    val observed = JSONObject(tool(body, "android-observe-initial"))
                    require(observed.getString("package") == TARGET)
                    require(nodes(observed).any { it.optString("text") == "Counter: 0" })
                    val button = nodes(observed).single { it.optString("text") == "Increment" }
                    require(button.getBoolean("clickable") && !button.getBoolean("sensitive"))
                    usedObservedNode.set(true)
                    response("click", JSONObject().put("snapshot", observed.getString("snapshot")).put("node", button.getString("id")))
                }
                3 -> {
                    val clicked = JSONObject(tool(body, "android-click"))
                    require(clicked.getString("accepted") == "click" && !clicked.getBoolean("verified"))
                    response("observe", id = "android-observe-updated", delayMillis = 350)
                }
                4 -> {
                    val observed = JSONObject(tool(body, "android-observe-updated"))
                    require(observed.getString("package") == TARGET)
                    require(nodes(observed).any { it.optString("text") == "Counter: 1" })
                    observedIncrement.set(true)
                    response("assert_text", JSONObject().put("text", "Counter: 1"))
                }
                5 -> {
                    val assertion = JSONObject(tool(body, "android-assert_text"))
                    require(assertion.getString("assertion") == "passed")
                    response("screenshot")
                }
                6 -> {
                    val captured = JSONObject(tool(body, "android-screenshot"))
                    require(captured.getString("package") == TARGET)
                    val images = images(body)
                    require(images.size == 1)
                    val image = images.single()
                    require(image.startsWith("data:image/jpeg;base64,"))
                    val bytes = Base64.decode(image.substringAfter(','), Base64.DEFAULT)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { require(bitmap.width > 100 && bitmap.height > 100) } finally { bitmap.recycle() }
                    receivedPixels.set(true)
                    finalResponse()
                }
                else -> error("Unexpected additional model round")
            }
        } catch (error: Exception) {
            failure.compareAndSet(null, "${error.javaClass.simpleName}: ${error.message}")
            MockResponse().setResponseCode(500).setBody("Local workflow fixture rejected the observed request")
        }

        private fun awaitTargetReady() = runBlocking {
            // The owner may need to confirm MIUI's first cross-app launch manually.
            // Inspect only our fixture after checking its root package; never read permission UI.
            // A foreground package alone does not mean the activity's launch animation has settled.
            withTimeout(15_000) {
                var previous: List<String>? = null
                var stableSamples = 0
                while (stableSamples < 8) {
                    val current = withContext(Dispatchers.Main) {
                        val root = ScreenTestAccessibilityService.instance?.rootInActiveWindow
                        try {
                            if (root?.packageName?.toString() == TARGET &&
                                root.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                                root.window?.isActive == true) fixtureLayout(root) else null
                        } finally {
                            @Suppress("DEPRECATION")
                            root?.recycle()
                        }
                    }
                    stableSamples = if (current != null && current == previous) stableSamples + 1
                        else if (current != null) 1 else 0
                    previous = current
                    if (stableSamples < 8) delay(150)
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun fixtureLayout(root: AccessibilityNodeInfo): List<String>? {
            val layout = mutableListOf<String>()
            var hasCounter = false
            var hasIncrement = false
            var visited = 0
            var valid = true
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++visited > 300 || depth > 20 || node.packageName?.toString() != TARGET) {
                    valid = false
                    return
                }
                val bounds = Rect().also(node::getBoundsInScreen)
                val sensitive = node.isPassword
                val text = if (sensitive) "" else node.text?.toString().orEmpty()
                layout += listOf(node.className, node.viewIdResourceName, bounds.toShortString(), text,
                    if (sensitive) "" else node.contentDescription, node.isVisibleToUser, node.isEnabled, node.isClickable,
                    node.isEditable, node.childCount, sensitive).joinToString("\u001f")
                if (node.isVisibleToUser && node.isEnabled) {
                    hasCounter = hasCounter || text == "Counter: 0"
                    hasIncrement = hasIncrement || (text == "Increment" && node.isClickable && !bounds.isEmpty)
                }
                if (sensitive) return // Keep geometry only; never read or visit password contents.
                for (index in 0 until node.childCount.coerceAtMost(300)) {
                    if (!valid) break
                    node.getChild(index)?.let { child ->
                        try { visit(child, depth + 1) } finally { child.recycle() }
                    }
                }
            }
            visit(root, 0)
            return layout.takeIf { valid && hasCounter && hasIncrement }
        }

        private fun nodes(observed: JSONObject): List<JSONObject> = observed.getJSONArray("nodes").let { array ->
            (0 until array.length()).map(array::getJSONObject)
        }
        private fun tool(body: JSONObject, callId: String): String {
            val messages = body.getJSONArray("messages")
            return (0 until messages.length()).map(messages::getJSONObject).last {
                it.optString("role") == "tool" && it.optString("tool_call_id") == callId
            }.getString("content")
        }
        private fun images(body: JSONObject): List<String> = body.getJSONArray("messages").let { messages ->
            (0 until messages.length()).flatMap { i ->
                val content = messages.getJSONObject(i).optJSONArray("content") ?: JSONArray()
                (0 until content.length()).map(content::getJSONObject).filter { it.optString("type") == "image_url" }
                    .map { it.getJSONObject("image_url").getString("url") }
            }
        }
        private fun response(action: String, extra: JSONObject = JSONObject(),
                             id: String = if (action == "observe") "android-observe-initial" else "android-$action",
                             delayMillis: Long = 100): MockResponse {
            extra.put("action", action)
            val call = JSONObject().put("index", 0).put("id", id).put("type", "function").put("function",
                JSONObject().put("name", "android_test").put("arguments", extra.toString()))
            return sse(JSONObject().put("tool_calls", JSONArray().put(call)), "tool_calls").setBodyDelay(delayMillis, TimeUnit.MILLISECONDS)
        }
        private fun finalResponse() = sse(JSONObject().put("content", "已启动调试应用，实际观察点击前后计数由 0 变为 1，文字断言通过，并检查收到的测试截图。此测试未执行构建或安装。"), "stop")
        private fun sse(delta: JSONObject, finish: String): MockResponse {
            val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
            val ended = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", finish)))
            return MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: $ended\n\ndata: [DONE]\n\n")
        }
    }

    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(30) }
    private suspend fun shell(command: String) = withContext(Dispatchers.IO) {
        automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().take(16384) }
        }
    }
    companion object {
        private const val TARGET = "dev.zakocoder.screentest.fixture"
        private const val PROJECT = "ai-android-workflow-fixture"
    }
}

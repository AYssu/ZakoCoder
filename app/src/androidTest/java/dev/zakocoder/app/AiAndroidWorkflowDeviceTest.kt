package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.test.filters.SdkSuppress
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.device.DeviceAccessController
import dev.zakocoder.app.device.VirtualScreenController
import kotlinx.coroutines.flow.first
import java.util.UUID
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
 * Uses the androidTest launchable fixture on a real Root-owned display; no accessibility service.
 * Does not build/install pretend artifacts, change system settings or use a provider credential.
 */
@SdkSuppress(minSdkVersion = 30)
class AiAndroidWorkflowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    @Test(timeout = 180000)
    fun approvedAiTaskObservesClicksVerifiesAndSharesActualPixelsThenRevokesDeviceTask() = runBlocking {
        AiDeviceTest.Fixture(base, timeoutSeconds = 45, readTimeoutSeconds = 30).use { fixture ->
            // The authority's shared strategy must resolve real app roots; resolving it first
            // with an isolated ContextWrapper would contaminate later fixtures in this process.
            FileProvider.getUriForFile(base, "${base.packageName}.apk-files",
                File(base.filesDir, "screen-test/evidence/provider-root-fixture.png"))
            val token = "ai-virtual-${UUID.randomUUID()}-"
            val names = Collections.synchronizedSet(mutableSetOf<String>())
            val isolated = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(base.filesDir, "screen-test/evidence/$token").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$token$name".also { names.add(it) }, mode)
                override fun getNoBackupFilesDir() = File(fixture.root, "controller-private").apply { mkdirs() }
                override fun getCacheDir() = File(fixture.root, "controller-cache").apply { mkdirs() }
            }
            val project = withContext(Dispatchers.IO) { File(fixture.root, "project").apply { check(mkdirs()) } }
            val screenshots = Collections.synchronizedList(mutableListOf<Uri>())
            isolated.getSharedPreferences("device_access", Context.MODE_PRIVATE).edit().putString("mode", "root").commit()
            val access = DeviceAccessController(isolated)
            val virtual = VirtualScreenController(isolated, access)
            val screen = ScreenTestController(isolated, virtual, access)
            var ai: AiController? = null
            try {
                withTimeout(10_000) { access.state.first { it.loaded } }
                assertTrue("A real Root grant is required", access.ensureRoot())
                val model = WorkflowModel(instrumentation.context.packageName)
                fixture.server.dispatcher = model
                main {
                    ai = AiController(isolated, { id -> check(id == PROJECT); project }, { _, _ -> false },
                        { _, _ -> error("This screen workflow must not run a host or guest command") }, {}, {},
                        fixture.transport, fixture.config, history = fixture.history,
                        capabilitySnapshot = { mapOf(dev.zakocoder.app.capabilities.CapabilityPackage.Screen to "screen-package-test") },
                        screenTestTool = { id, args, lease ->
                            check(ai?.state?.value?.let { it.active && it.projectId == id } == true)
                            check(id == PROJECT)
                            val action = args.optString("action")
                            Log.i("ZakoAiVirtualTest", "stage=tool_begin action=$action")
                            screen.execute(id, args, lease).also { result ->
                                result.imageUri?.let(screenshots::add)
                                Log.i("ZakoAiVirtualTest", "stage=tool_complete action=$action")
                            }
                        },
                        screenTestLease = screen::taskLease,
                        screenTestAuthorize = { id, taskId -> screen.authorizeDeviceTask(id, taskId) },
                        screenTestDescription = { _ -> "专用虚拟屏幕" },
                        finishScreenTestTask = { id, lease -> screen.finishTask(id, lease) },
                        stopScreenTest = screen::stop,
                        shareScreenTestImages = { id -> screen.state.value.projectId == id && screen.state.value.shareScreenshots })
                    ai!!.select(PROJECT)
                }
                val controller = requireNotNull(ai)
                await { controller.state.value.configured && !controller.state.value.permissionsLoading &&
                    !controller.state.value.skillsLoading && !controller.models.state.value.loading }
                assertTrue(screen.state.value.enabled)
                assertFalse(screen.state.value.connected)
                assertNull(screen.taskLease(PROJECT))
                assertFalse(screen.state.value.shareScreenshots)

                main { assertTrue(controller.send("在虚拟屏幕打开测试应用：点击 Increment，验证 Count: 1，再检查截图。不要声称执行了构建或安装。")) }
                await { controller.state.value.approval != null }
                assertTrue(controller.state.value.approval!!.detail.contains("虚拟屏幕"))
                assertEquals(0, controller.state.value.events.count { it.status == AiToolStatus.Succeeded })
                assertNull(screen.taskLease(PROJECT))
                main { controller.approve(AiDecision.TaskTool) }
                await { screen.taskLease(PROJECT) != null }
                val originalLease = requireNotNull(screen.taskLease(PROJECT))
                // Explicit fixture-owner screenshot consent follows the actual task lease.
                screen.setScreenshotSharing(PROJECT, true)
                await { !controller.state.value.active }

                assertNull("Local model fixture validation: ${model.failure.get()}", model.failure.get())
                assertEquals("", controller.state.value.error)
                val observationCount = model.readinessObservations.get()
                assertTrue("The fixture waits for consecutive equal real node projections", observationCount in 2..8)
                assertEquals("Exactly one model-requested click is dispatched", 1, model.clickRequests.get())
                assertEquals(7 + observationCount, fixture.server.requestCount)
                assertTrue("Model used an actual observation snapshot and node ID", model.usedObservedNode.get())
                assertTrue("Subsequent observation confirmed the actual UI counter", model.observedIncrement.get())
                assertTrue("Next actual model request contains decodable captured pixels", model.receivedPixels.get())
                assertEquals(6 + observationCount, controller.state.value.events.size)
                assertTrue(controller.state.value.events.all { it.tool == AiTool.AndroidTest && it.status == AiToolStatus.Succeeded })
                assertNull(controller.state.value.approval)

                val report = screen.readReport(PROJECT)
                val entries = report.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                assertEquals(listOf("authorize", "launch") + List(observationCount) { "observe" } +
                    listOf("click", "observe", "assert_text", "screenshot", "finish"),
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
                assertEquals(6 + observationCount, saved.events.size)
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
                virtual.close()
                withTimeout(15_000) { virtual.awaitStopped() }
                access.stop()
                names.toList().forEach(base::deleteSharedPreferences)
                runCatching { screen.readReport(PROJECT) }
                isolated.filesDir.deleteRecursively()
            }
        }
    }

    /** Each response is derived from the preceding real tool result, not a prewritten snapshot. */
    private class WorkflowModel(private val target: String) : Dispatcher() {
        private enum class Stage { Authorize, Launch, LaunchResult, ReadyObservation, ClickResult, UpdatedObservation, Assertion, Screenshot, Complete }
        private var stage = Stage.Authorize
        private var previousProjection: String? = null
        private var observationId = ""
        val readinessObservations = AtomicInteger()
        val clickRequests = AtomicInteger()
        val failure = AtomicReference<String?>()
        val usedObservedNode = AtomicBoolean()
        val observedIncrement = AtomicBoolean()
        val receivedPixels = AtomicBoolean()
        override fun dispatch(request: RecordedRequest): MockResponse = try {
            val body = JSONObject(request.body.readUtf8())
            Log.i("ZakoAiVirtualTest", "stage=model_${stage.name}")
            when (stage) {
                Stage.Authorize -> {
                    stage = Stage.Launch
                    response("authorize")
                }
                Stage.Launch -> {
                    require(tool(body, "android-authorize").isNotBlank())
                    stage = Stage.LaunchResult
                    response("launch", JSONObject().put("package_name", target))
                }
                Stage.LaunchResult -> {
                    require(toolObject(body, "android-launch").getBoolean("verified"))
                    stage = Stage.ReadyObservation
                    requestReadyObservation()
                }
                Stage.ReadyObservation -> {
                    val observed = toolObject(body, observationId)
                    require(observed.getString("package") == target)
                    require(!observed.optBoolean("truncated"))
                    require(nodes(observed).any { it.optString("text") == "Count: 0" })
                    val button = nodes(observed).single { it.optString("text") == "Increment" }
                    require(button.getBoolean("clickable") && !button.getBoolean("sensitive"))
                    val projection = observed.getJSONArray("nodes").toString()
                    if (projection != previousProjection) {
                        previousProjection = projection
                        require(readinessObservations.get() < 8) { "Fixture layout did not settle after eight read-only observations" }
                        requestReadyObservation()
                    } else {
                        // Never replay a write after a stale snapshot: only initial read-only
                        // observations repeat, and the latest unchanged snapshot drives one click.
                        usedObservedNode.set(true)
                        check(clickRequests.incrementAndGet() == 1)
                        stage = Stage.ClickResult
                        response("click", JSONObject().put("snapshot", observed.getString("snapshot")).put("node", button.getString("id")))
                    }
                }
                Stage.ClickResult -> {
                    val clicked = toolObject(body, "android-click")
                    require(clicked.getBoolean("dispatched") && !clicked.getBoolean("verified"))
                    stage = Stage.UpdatedObservation
                    response("observe", id = "android-observe-updated")
                }
                Stage.UpdatedObservation -> {
                    val observed = toolObject(body, "android-observe-updated")
                    require(observed.getString("package") == target)
                    require(nodes(observed).any { it.optString("text") == "Count: 1" })
                    observedIncrement.set(true)
                    stage = Stage.Assertion
                    response("assert_text", JSONObject().put("text", "Count: 1"))
                }
                Stage.Assertion -> {
                    val assertion = toolObject(body, "android-assert_text")
                    require(assertion.getString("assertion") == "passed")
                    stage = Stage.Screenshot
                    response("screenshot")
                }
                Stage.Screenshot -> {
                    val captured = toolObject(body, "android-screenshot")
                    require(captured.getBoolean("captured"))
                    require(captured.getInt("display_id") > 0)
                    val images = images(body)
                    require(images.size == 1)
                    val image = images.single()
                    require(image.startsWith("data:image/jpeg;base64,"))
                    val bytes = Base64.decode(image.substringAfter(','), Base64.DEFAULT)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try { require(bitmap.width > 100 && bitmap.height > 100) } finally { bitmap.recycle() }
                    receivedPixels.set(true)
                    stage = Stage.Complete
                    finalResponse()
                }
                Stage.Complete -> error("Unexpected additional model round")
            }
        } catch (error: Exception) {
            failure.compareAndSet(null, "stage=${stage.name}: ${error.javaClass.simpleName}: ${error.message}")
            MockResponse().setResponseCode(500).setBody("Local workflow fixture rejected the observed request")
        }

        private fun requestReadyObservation(): MockResponse {
            observationId = "android-observe-ready-${readinessObservations.incrementAndGet()}"
            return response("observe", id = observationId, delayMillis = 200)
        }
        private fun toolObject(body: JSONObject, id: String): JSONObject {
            val output = tool(body, id)
            // Preserve the actual controller error in the failure instead of reporting a
            // misleading JSON decoding failure. No failed action is silently retried.
            check(output.trimStart().startsWith("{")) { "Tool $id failed: ${output.take(240)}" }
            return JSONObject(output)
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
        private fun finalResponse() = sse(JSONObject().put("content", "用户已打开目标界面，实际观察点击前后计数由 0 变为 1，文字断言通过，并检查收到的测试截图。此测试未执行构建或安装。"), "stop")
        private fun sse(delta: JSONObject, finish: String): MockResponse {
            val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
            val ended = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", finish)))
            return MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: $ended\n\ndata: [DONE]\n\n")
        }
    }

    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(30) }
    companion object {
        private const val PROJECT = "ai-android-workflow-fixture"
    }
}

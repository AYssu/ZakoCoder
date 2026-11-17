package dev.zakocoder.app

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.zakocoder.app.ai.AiModelDraft
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.settings.BackgroundTaskNotifications
import dev.zakocoder.app.terminal.DevelopmentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Application-owned AI and Service, not an independently constructed controller.
 * Opt-in isolated package only. Uses a local HTTP model and one owned file; no account or Ubuntu.
 * Run on an unlocked device with notifications allowed and without a secure screen lock.
 */
class BackgroundAiExecutionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(25_000) { while (!condition()) delay(25) }
        } catch (failure: TimeoutCancellationException) {
            val workbench = (context.applicationContext as ZakoApplication).workbench
            val app = workbench.state.value
            val ai = workbench.ai.state.value
            throw AssertionError("$label was not observed within 25 seconds; " +
                "serviceRunning=${app.backgroundServiceRunning}, serviceError=${app.backgroundServiceError}, " +
                "aiActive=${ai.active}, phase=${ai.phase}, round=${ai.round}", failure)
        }
    }
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= 8 * 1024 * 1024)
                bytes.write(buffer, 0, count)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
    }
    private suspend fun cpuLockHeld() = shell("dumpsys power").lineSequence().any {
        it.contains("PARTIAL_WAKE_LOCK") && it.contains("${context.packageName}:development-task")
    }
    private suspend fun foregroundService() = shell("dumpsys activity services ${context.packageName}").let {
        it.contains("dev.zakocoder.app.terminal.DevelopmentService") && Regex("isForeground\\s*=\\s*true").containsMatchIn(it)
    }
    private suspend fun assistantReplyVisible(reply: String): Boolean = withContext(Dispatchers.Main) {
        fun hasReply(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Text)
            ?.any { it.text.contains(reply) } == true || node.children.any(::hasReply)
        fun visit(view: android.view.View): Boolean {
            if (!view.isShown) return false
            if (view is ViewRootForTest && hasReply(view.semanticsOwner.unmergedRootSemanticsNode)) return true
            return view is android.view.ViewGroup && (0 until view.childCount).any { visit(view.getChildAt(it)) }
        }
        android.view.inspector.WindowInspector.getGlobalWindowViews().any(::visit)
    }
    private fun response(delta: JSONObject, finish: String): MockResponse {
        val first = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
        val last = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", finish)))
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $first\n\ndata: $last\n\ndata: [DONE]\n\n")
    }

    @Test(timeout = 180_000) fun homeAndScreenOffPreserveRealTaskThenNotifyAndOpenItsOwnConversation() = runBlocking {
        assumeTrue("Never use a production package for this fixture", context.packageName == "dev.zakocoder.app.runtimecheck")
        assumeTrue("Grant the isolated package notifications before running this opt-in test",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assumeFalse("This fixture must not change or bypass a personal lock credential",
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        warmDeviceActivity()
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        val ai = workbench.ai
        await("Application Workbench is ready") { !workbench.state.value.loading && !workbench.state.value.busy && !ai.models.state.value.loading }
        assumeFalse("Complete first-run setup in this isolated package before the opt-in task fixture", workbench.state.value.onboarding.required)
        check(!ai.state.value.active && !workbench.state.value.backgroundServiceRunning && workbench.terminal.sessions.isEmpty()) {
            "The isolated package must have no pre-existing running work"
        }
        val oldProject = workbench.state.value.selectedId
        val oldProfile = ai.models.state.value.catalog.activeId
        val name = "background-owned-${UUID.randomUUID()}"
        val gateEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        var project: String? = null
        var profile: String? = null
        var conversation: String? = null
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(request.path == "/v1/chat/completions")
                    return when (requests.incrementAndGet()) {
                        1 -> response(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                            .put("id", "owned-background-write").put("type", "function")
                            .put("function", JSONObject().put("name", "write_file").put("arguments",
                                JSONObject().put("path", "background-evidence.txt").put("content", "written exactly once").toString())))), "tool_calls")
                        2 -> {
                            gateEntered.countDown()
                            check(release.await(90, TimeUnit.SECONDS)) { "Owned HTTP fixture was not released" }
                            response(JSONObject().put("content", "Background verification finished."), "stop")
                        }
                        else -> MockResponse().setResponseCode(400)
                    }
                }
            }
            server.start()
            // The launcher and notification intentionally reuse the real foreground Activity.
            try {
                main { ai.models.save(AiModelDraft(null, name, server.url("/v1").toString(), "owned-background-model", "fixture-only-placeholder")) }
                await("Owned local profile saved") {
                    !ai.models.state.value.saving && ai.models.state.value.catalog.profiles.any { it.name == name }
                }
                profile = ai.models.state.value.catalog.profiles.single { it.name == name }.id
                main { ai.models.activate(requireNotNull(profile)) }
                await("Owned profile selected") { !ai.models.state.value.saving && ai.models.state.value.catalog.activeId == profile && ai.state.value.configured }
                main { workbench.create(name) }
                await("Owned project selected") { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                project = requireNotNull(workbench.state.value.selectedId)
                await("Application AI selected owned project") {
                    ai.state.value.projectId == project && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading
                }
                conversation = ai.state.value.conversationId
                main { assertTrue(ai.send("Write background-evidence.txt then finish this owned background check.")) }
                await("Owned file tool approval or second request") { ai.state.value.approval != null || gateEntered.count == 0L || !ai.state.value.active }
                if (ai.state.value.approval != null) main { ai.approve(dev.zakocoder.app.ai.AiDecision.Once) }
                withContext(Dispatchers.IO) { assertTrue("The real controller completed its file tool", gateEntered.await(25, TimeUnit.SECONDS)) }
                await("Application foreground service ready") { workbench.state.value.backgroundServiceRunning }
                assertTrue(foregroundService())
                assertTrue(cpuLockHeld())
                assertTrue(notifications.activeNotifications.any { it.id == DevelopmentService.NOTIFICATION_ID })
                val ownedFile = withContext(Dispatchers.IO) { File(workbench.projects.directory(requireNotNull(project)), "background-evidence.txt") }
                val modified = withContext(Dispatchers.IO) {
                    assertEquals("written exactly once", ownedFile.readText()); ownedFile.lastModified()
                }
                val pid = android.os.Process.myPid()
                // Reproduce destruction of only our service after the file side effect.
                // This must not become Stop All or replay the already completed tool.
                // The service is not exported: stop the actual owned instance rather than relying
                // on a shell request that may be denied. Android still dispatches its real onDestroy.
                main {
                    val field = Workbench::class.java.getDeclaredField("foregroundService").apply { isAccessible = true }
                    val ownedService = requireNotNull(field.get(workbench) as? DevelopmentService) {
                        "The real foreground service must be attached before simulating its destruction"
                    }
                    ownedService.stopSelf()
                }
                await("Unexpected service destruction is observed") { !workbench.state.value.backgroundServiceRunning }
                assertTrue("A service callback must not cancel the owning AI task", ai.state.value.active)
                assertEquals("Service loss must not replay a model request", 2, requests.get())
                assertFalse(cpuLockHeld())
                shell("input keyevent KEYCODE_HOME")
                warmDeviceActivity()
                await("Visible Activity restores service ownership") { workbench.state.value.backgroundServiceRunning }
                assertTrue("The same task survives service recovery", ai.state.value.active)
                assertEquals("Recovery must not replay completed tools or requests", 2, requests.get())
                assertTrue(foregroundService())
                assertTrue(cpuLockHeld())
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                await("Real screen is off") { !power.isInteractive }
                delay(1_200)
                assertTrue("Task is still active while the screen is off", ai.state.value.active)
                assertTrue(foregroundService())
                assertTrue(cpuLockHeld())
                release.countDown()
                await("Task completes while backgrounded") { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals("", ai.state.value.error)
                assertTrue(ai.state.value.timeline.any { it.role == AiRole.Assistant && it.text == "Background verification finished." })
                await("Completion notification exists") {
                    notifications.activeNotifications.any { it.tag == conversation && it.notification.channelId == BackgroundTaskNotifications.CHANNEL }
                }
                val notice = notifications.activeNotifications.single { it.tag == conversation && it.notification.channelId == BackgroundTaskNotifications.CHANNEL }
                assertEquals(context.getString(R.string.background_completed_title), notice.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals(Notification.VISIBILITY_PRIVATE, notice.notification.visibility)
                assertEquals(2, requests.get())
                withContext(Dispatchers.IO) {
                    assertEquals("written exactly once", ownedFile.readText()); assertEquals(modified, ownedFile.lastModified())
                }
                assertEquals("Screen off did not restart the process", pid, android.os.Process.myPid())
                await("Task no longer owns the foreground service") { !workbench.state.value.backgroundServiceRunning }
                assertFalse(cpuLockHeld())
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                // Notification identity must survive selecting a different conversation before opening it.
                warmDeviceActivity()
                main { ai.conversation() }
                await("Different conversation selected") { !ai.state.value.permissionsLoading && ai.state.value.conversationId != conversation }
                notice.notification.contentIntent.send()
                await("Notification opens the exact completed conversation") {
                    ai.state.value.projectId == project && ai.state.value.conversationId == conversation && !ai.state.value.permissionsLoading
                }
                await("Notification displays the completed reply on the actual Assistant page") {
                    assistantReplyVisible("Background verification finished.")
                }
                assertEquals(2, requests.get())
            } finally {
                release.countDown()
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                warmDeviceActivity()
                main { ai.stop() }
                await("Owned task stopped") { !ai.state.value.active }
                conversation?.let { notifications.cancel(it, 9) }
                project?.let { owned ->
                    main { workbench.removeProject(owned) }
                    await("Owned project removed") { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == owned } }
                }
                profile?.let { owned ->
                    main { ai.models.delete(owned) }
                    await("Owned model removed") { !ai.models.state.value.saving && ai.models.state.value.catalog.profiles.none { it.id == owned } }
                }
                oldProfile?.let { previous -> main { ai.models.activate(previous) }; await("Previous profile identity restored") { !ai.models.state.value.saving && ai.models.state.value.catalog.activeId == previous } }
                oldProject?.let { previous -> main { workbench.select(previous) }; await("Previous project identity restored") { !workbench.state.value.busy && workbench.state.value.selectedId == previous } }
            }
        }
    }
}

package dev.zakocoder.app.screentest

import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Permission-free boundary checks; live gestures require a separately installed debug fixture. */
class ScreenTestControllerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun noSelectionCannotControlForegroundAndStatusDoesNotClaimInstallation() = runBlocking {
        withController { controller, _ ->
            val output = JSONObject(controller.execute("project", JSONObject().put("action", "status")).output)
            assertTrue(output.getBoolean("taskAuthorizationRequired"))
            assertEquals("current_device", output.getString("scope"))
            assertFalse(output.has("candidates"))
            assertFalse(output.getBoolean("active"))
            assertFalse(controller.state.value.active)
            val error = runCatching { controller.execute("project", JSONObject().put("action", "back")) }.exceptionOrNull()
            assertTrue(error is ScreenTestException)
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun hostApkCannotBeSelectedAsTestTarget() = runBlocking {
        withController { controller, directory ->
            File(context.applicationInfo.sourceDir).copyTo(File(directory, "host.apk"))
            val error = runCatching { controller.selectApk("project", "host.apk") }.exceptionOrNull()
            assertTrue(error is ScreenTestException)
            assertFalse(controller.state.value.active)
            assertTrue(controller.discoverApks("project").isEmpty())
        }
    }

    @Test fun modeChangeAndStopDoNotGrantRootOrScreenshotSharing() = runBlocking {
        withController { controller, _ ->
            assertEquals(ScreenTestAccess.Accessibility, controller.state.value.access)
            controller.setAccess(ScreenTestAccess.Root)
            assertEquals(ScreenTestAccess.Root, controller.state.value.access)
            assertFalse(controller.state.value.rootGranted)
            assertFalse(controller.state.value.shareScreenshots)
            controller.stop()
            assertFalse(controller.state.value.overlaySuppressed)
            assertEquals(ScreenOverlayPhase.Cancelled, controller.state.value.overlayProgress.phase)
            assertTrue(controller.state.value.overlayConversation.isEmpty())
            controller.setAccess(ScreenTestAccess.Accessibility)
            assertFalse(controller.state.value.active)
            assertFalse(controller.state.value.shareScreenshots)
            val error = runCatching { controller.setScreenshotSharing("project", true) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
        }
    }

    @Test fun traversalAndInvalidArchivesCannotCreateASelection() = runBlocking {
        withController { controller, directory ->
            File(directory, "invalid.apk").writeText("not an APK")
            assertTrue(controller.discoverApks("project").isEmpty())
            assertTrue(runCatching { controller.selectApk("project", "../invalid.apk") }.isFailure)
            assertTrue(runCatching { controller.selectApk("project", "invalid.apk") }.isFailure)
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun longMultibyteObservationsStayValidJsonWithinBothBudgets() {
        val nodes = (0 until 300).map { index ->
            JSONObject().put("id", "n$index").put("text", "中文\n\"".repeat(160)).put("editable", false)
        }
        val result = boundedScreenObservation("fixture", "snapshot-token", nodes)
        assertTrue(result.output.length <= 60_000)
        assertTrue(result.output.toByteArray(Charsets.UTF_8).size <= 60_000)
        val parsed = JSONObject(result.output)
        assertTrue(parsed.getBoolean("truncated"))
        assertEquals(result.exposedCount, parsed.getJSONArray("nodes").length())
        assertTrue(result.exposedCount in 1..79)
    }

    @Test fun currentDeviceAuthorizationNeverReadsAProjectOrAnApk() = runBlocking {
        val directory = File(context.cacheDir, "device-screen-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val controller = ScreenTestController(isolated) { error("Device screen must not resolve a workspace directory") }
        try {
            val initial = JSONObject(controller.execute(ScreenTestController.DEVICE_TASK_OWNER, JSONObject().put("action", "status")).output)
            assertTrue(initial.getBoolean("taskAuthorizationRequired"))
            val lease = controller.authorizeDeviceTask(ScreenTestController.DEVICE_TASK_OWNER, "task-one")
            assertEquals(lease, controller.taskLease(ScreenTestController.DEVICE_TASK_OWNER))
            assertEquals("task-one", controller.state.value.taskId)
            assertEquals(null, controller.state.value.apkPath)
            assertEquals(null, controller.state.value.packageName)
            assertTrue(controller.state.value.active)
            val status = JSONObject(controller.execute(ScreenTestController.DEVICE_TASK_OWNER, JSONObject().put("action", "status")).output)
            assertFalse(status.getBoolean("taskAuthorizationRequired"))
            assertFalse(status.has("package")) // Status does not read a screen before an observation.
            controller.finishTask(ScreenTestController.DEVICE_TASK_OWNER, lease)
            assertFalse(controller.state.value.active)
            val audit = controller.readReport(ScreenTestController.DEVICE_TASK_OWNER)
            assertTrue(audit.contains("current_device_task_authorized"))
            assertFalse(audit.contains("selected_debug_apk"))
        } finally {
            controller.stop()
            runCatching { controller.readReport(ScreenTestController.DEVICE_TASK_OWNER) }
            directory.deleteRecursively()
        }
    }

    @Test fun reauthorizationRevokesPreviousTaskAndCannotLeakAcrossOwners() = runBlocking {
        withController { controller, _ ->
            val first = controller.authorizeDeviceTask("owner", "task-one")
            controller.setScreenshotSharing("owner", true)
            val second = controller.authorizeDeviceTask("owner", "task-two")
            assertTrue(first != second)
            assertFalse(controller.state.value.shareScreenshots)
            assertTrue(runCatching { controller.setScreenshotSharing("owner", true, first) }.isFailure)
            assertFalse(controller.state.value.shareScreenshots)
            controller.setScreenshotSharing("owner", true, second)
            assertTrue(controller.state.value.shareScreenshots)
            assertEquals(null, controller.taskLease("different-owner"))
            controller.finishTask("owner", first)
            assertEquals(second, controller.taskLease("owner"))
            val stale = runCatching { controller.execute("owner", JSONObject().put("action", "back"), first) }.exceptionOrNull()
            assertTrue(stale is ScreenTestException)
            val foreign = runCatching { controller.execute("different-owner", JSONObject().put("action", "back"), second) }.exceptionOrNull()
            assertTrue(foreign is ScreenTestException)
            controller.finishTask("owner", second)
            assertEquals(null, controller.taskLease("owner"))
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun operationFailureRecordsProgressAndStopRevokesItsLease() = runBlocking {
        withController { controller, _ ->
            controller.setAccess(ScreenTestAccess.Normal) // Legacy mode can never inspect a screen.
            val lease = controller.authorizeDeviceTask("project", "failure-task")
            val error = runCatching { controller.execute("project", JSONObject().put("action", "observe"), lease) }.exceptionOrNull()
            assertTrue(error is ScreenTestException)
            assertEquals(ScreenOverlayPhase.Failed, controller.state.value.overlayProgress.phase)
            assertEquals("observe", controller.state.value.overlayProgress.action)
            assertFalse(controller.state.value.busy)
            assertFalse(controller.state.value.overlaySuppressed)
            controller.stop()
            controller.updateOverlayConversation("project", lease, "revoked")
            assertEquals("", controller.state.value.overlayConversation)
            assertEquals(null, controller.taskLease("project"))
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun previewDoesNotGrantTargetAndApprovalRequiresTheExactPreviewedBytes() = runBlocking {
        withFixtureController { controller, _, _, _ ->
            controller.stop()
            val proposal = controller.previewApk("project", "app-debug.apk")
            assertEquals("app-debug.apk", proposal.apk.relativePath)
            assertEquals(64, proposal.sha256.length)
            assertFalse(controller.state.value.active)
            assertEquals(null, controller.taskLease("project"))
            val rejected = runCatching { controller.selectApk("project", proposal.apk.relativePath, "0".repeat(64)) }.exceptionOrNull()
            assertTrue(rejected is ScreenTestException)
            assertFalse(controller.state.value.active)
            controller.selectApk("project", proposal.apk.relativePath, proposal.sha256)
            assertFalse(controller.state.value.active)
            assertEquals(null, controller.taskLease("project"))
        }
    }

    @Test fun overlayConversationRejectsOtherProjectsAndRevokedLeases() = runBlocking {
        withController { controller, _ ->
            val lease = controller.authorizeDeviceTask("project", "overlay-task")
            controller.updateOverlayConversation("project", lease, "正在检查构建后的页面")
            assertEquals("正在检查构建后的页面", controller.state.value.overlayConversation)
            controller.updateOverlayConversation("other-project", lease, "不能跨项目")
            controller.updateOverlayConversation("project", "old-lease", "不能跨任务")
            assertEquals("正在检查构建后的页面", controller.state.value.overlayConversation)
            controller.stop()
            controller.updateOverlayConversation("project", lease, "已经撤销")
            assertEquals("", controller.state.value.overlayConversation)
        }
    }

    @Test fun statusNeedsNeitherWorkspaceNorTargetSelection() = runBlocking {
        withController { controller, _ ->
            val output = JSONObject(controller.execute("nonexistent-workspace", JSONObject().put("action", "status")).output)
            assertTrue(output.getBoolean("taskAuthorizationRequired"))
            assertFalse(output.getBoolean("active"))
            assertFalse(output.has("candidates"))
            assertFalse(controller.state.value.shareScreenshots)
        }
    }

    private class ArtifactGate {
        val armed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitIfArmed() {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Artifact test gate timed out" }
            }
        }
    }

    private suspend fun withFixtureController(
        block: suspend (ScreenTestController, File, ArtifactGate, AtomicReference<File>) -> Unit
    ) {
        val directory = File(context.cacheDir, "screen-race-${UUID.randomUUID()}").apply { mkdirs() }
        val project = File(directory, "project").apply { mkdirs() }
        val reportDirectory = AtomicReference(File(directory, "files").apply { mkdirs() })
        val cache = File(directory, "cache").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = reportDirectory.get()
            override fun getCacheDir(): File = cache
        }
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /data/local/tmp/fixture.apk")).use { input ->
                File(project, "app-debug.apk").outputStream().use { input.copyTo(it) }
            }
        }
        val gate = ArtifactGate()
        val controller = ScreenTestController(isolatedContext) { gate.awaitIfArmed(); project }
        try {
            controller.selectApk("project", "app-debug.apk")
            block(controller, directory, gate, reportDirectory)
        } finally {
            gate.release.countDown()
            controller.stop()
            // Drain the controller's asynchronous stop audit before deleting only this test's files.
            runCatching { controller.readReport("project") }
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    private suspend fun withController(block: suspend (ScreenTestController, File) -> Unit) {
        val directory = File(context.cacheDir, "screen-controller-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        }
        val controller = ScreenTestController(isolated) { directory }
        try { block(controller, directory) }
        finally {
            controller.stop()
            runCatching { controller.readReport("project") }
            runCatching { controller.readReport("owner") }
            directory.deleteRecursively()
        }
    }
}

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
            assertTrue(output.getBoolean("targetSelectionRequired"))
            assertEquals(0, output.getJSONArray("candidates").length())
            assertFalse(output.getBoolean("selected"))
            assertFalse(output.getBoolean("installed"))
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
            assertEquals(ScreenTestAccess.Normal, controller.state.value.access)
            controller.setAccess(ScreenTestAccess.Root)
            assertEquals(ScreenTestAccess.Root, controller.state.value.access)
            assertFalse(controller.state.value.rootGranted)
            assertFalse(controller.state.value.shareScreenshots)
            controller.stop()
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

    /** Requires the owner-installed disposable debug fixture, but no accessibility/root grants. */
    @Test fun statusStoppedDuringArtifactReadCannotReturnAnActiveSelection() = runBlocking {
        withFixtureController { controller, _, gate, _ ->
            assertTrue(JSONObject(controller.execute("project", JSONObject().put("action", "status")).output).getBoolean("installed"))
            coroutineScope {
                gate.armed.set(true)
                val pending = async(Dispatchers.IO) {
                    runCatching { controller.execute("project", JSONObject().put("action", "status")) }.exceptionOrNull()
                }
                try {
                    withContext(Dispatchers.IO) { assertTrue("status never reached artifact read", gate.entered.await(5, TimeUnit.SECONDS)) }
                    controller.stop()
                } finally { gate.release.countDown() }
                assertTrue("stale status must fail after stop", withTimeout(10_000) { pending.await() } is ScreenTestException)
                assertFalse(controller.state.value.active)
            }
        }
    }

    @Test fun cancellationRemainsCancellationWhenItsAuditCannotBeWritten() = runBlocking {
        withFixtureController { controller, directory, gate, reportDirectory ->
            coroutineScope {
                gate.armed.set(true)
                val pending = async(Dispatchers.IO) {
                    runCatching { controller.execute("project", JSONObject().put("action", "observe")) }.exceptionOrNull()
                }
                try {
                    withContext(Dispatchers.IO) { assertTrue("observe never reached artifact read", gate.entered.await(5, TimeUnit.SECONDS)) }
                    reportDirectory.set(File(directory, "not-a-directory").apply { writeText("blocked") })
                    controller.stop()
                } finally { gate.release.countDown() }
                val error = withTimeout(10_000) { pending.await() }
                assertTrue("audit failure must preserve cancellation", error is CancellationException)
                assertTrue("audit failure should be retained as suppressed evidence",
                    generateSequence(error) { it.cause }.any { it.suppressed.isNotEmpty() })
                assertFalse(controller.state.value.active)
            }
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
            assertTrue(controller.state.value.active)
        }
    }

    @Test fun overlayConversationRejectsOtherProjectsAndRevokedLeases() = runBlocking {
        withFixtureController { controller, _, _, _ ->
            val lease = requireNotNull(controller.taskLease("project"))
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

    @Test fun statusDiscoversCurrentDebugArtifactsWithoutSelectingOrGranting() = runBlocking {
        withFixtureController { controller, _, _, _ ->
            controller.stop()
            val output = JSONObject(controller.execute("project", JSONObject().put("action", "status")).output)
            assertTrue(output.getBoolean("targetSelectionRequired"))
            assertFalse(output.getBoolean("selected"))
            val candidate = output.getJSONArray("candidates").getJSONObject(0)
            assertEquals("app-debug.apk", candidate.getString("path"))
            assertTrue(candidate.getBoolean("debuggable"))
            assertFalse(controller.state.value.active)
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
        val controller = ScreenTestController(context) { directory }
        try { block(controller, directory) }
        finally { controller.stop(); directory.deleteRecursively() }
    }
}

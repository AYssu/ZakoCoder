package dev.zakocoder.app.screentest

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.device.DeviceAccessController
import dev.zakocoder.app.device.DeviceMode
import dev.zakocoder.app.device.VirtualScreenController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID

/** Permission-free checks. Real Root leases, pixels and revocation are covered by VirtualScreenTaskDeviceTest. */
class ScreenTestControllerDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun statusUsesOnlyVirtualDisplayAndNeverClaimsAnApkOrPhysicalScreen() = runBlocking {
        withController { controller, _ ->
            val output = JSONObject(controller.execute("no-workspace", action("status")).output)
            assertEquals("virtual_display", output.getString("scope"))
            assertFalse(output.getBoolean("physical_screen_control"))
            assertTrue(output.getBoolean("taskAuthorizationRequired"))
            assertFalse(output.getBoolean("active"))
            assertFalse(output.getBoolean("rootModeReady"))
            assertFalse(output.has("candidates"))
            assertFalse(output.has("apkPath"))
            assertFalse(output.has("artifactMatches"))
            assertFalse(output.has("accessibilityEnabled"))
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun basicModeCannotCreateTaskOrDiscoverApplications() = runBlocking {
        withController { controller, access ->
            assertEquals(DeviceMode.Basic, access.state.value.mode)
            assertTrue(runCatching { controller.authorizeDeviceTask("owner", "task") }.isFailure)
            assertNull(controller.taskLease("owner"))
            assertFalse(controller.state.value.active)
            assertFalse(controller.state.value.connected)
            assertFalse(controller.state.value.shareScreenshots)
            listOf("back", "observe", "list_apps", "launch", "screenshot").forEach { name ->
                assertTrue(name, runCatching { controller.execute("owner", action(name)) }.exceptionOrNull() is ScreenTestException)
            }
        }
    }

    @Test fun statusAndStopCannotGrantScreenshotsOrRestoreAnExpiredTask() = runBlocking {
        withController { controller, _ ->
            assertTrue(runCatching { controller.setScreenshotSharing("owner", true, "stale") }.isFailure)
            controller.finishTask("owner", "stale")
            controller.stop()
            assertEquals(ScreenOverlayPhase.Cancelled, controller.state.value.overlayProgress.phase)
            assertFalse(controller.state.value.busy)
            assertNull(controller.taskLease("owner"))
            controller.execute("owner", action("status"))
            assertFalse(controller.state.value.active)
            assertFalse(controller.state.value.shareScreenshots)
        }
    }

    @Test fun oldInstallActionIsNotAHiddenScreenControlEntryPoint() = runBlocking {
        withController { controller, _ ->
            assertTrue(runCatching { controller.execute("owner", action("install").put("path", "any.apk")) }
                .exceptionOrNull() is IllegalArgumentException)
            assertFalse(controller.state.value.active)
            assertNull(controller.taskLease("owner"))
        }
    }

    @Test fun obsoleteApkAndOverlayApisAreAbsent() {
        val methods = ScreenTestController::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.intersect(setOf("discoverApks", "previewApk", "selectApk", "setAccess",
            "setOverlayEnabled", "bindOverlayActions", "updateOverlayConversation")).isEmpty())
        val fields = ScreenTestState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.intersect(setOf("apkPath", "overlayEnabled", "overlayVisible", "overlayConversation")).isEmpty())
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

    private suspend fun withController(block: suspend (ScreenTestController, DeviceAccessController) -> Unit) {
        val token = "virtual-controller-${UUID.randomUUID()}"
        val root = File(base.cacheDir, token).apply { check(mkdirs()) }
        val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token-$name".also { preferences.add(it) }, mode)
        }
        val access = DeviceAccessController(isolated)
        val virtual = VirtualScreenController(isolated, access)
        val controller = ScreenTestController(isolated, virtual, access)
        try {
            withTimeout(10_000) { access.state.first { it.loaded } }
            controller.refresh()
            block(controller, access)
        } finally {
            controller.stop()
            runCatching { controller.readReport("owner") }
            virtual.close()
            access.stop()
            preferences.toList().forEach(base::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private fun action(name: String) = JSONObject().put("action", name)
}

package dev.zakocoder.app.device

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import dev.zakocoder.app.R
import dev.zakocoder.app.screentest.ScreenTestController
import dev.zakocoder.app.screentest.ScreenTestException
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Task authorization plus the real Root display; never obtains instrumentation UiAutomation. */
class VirtualScreenTaskDeviceTest {
    private class Fixture(private val production: Context) : AutoCloseable {
        private val token = "virtual-task-${UUID.randomUUID()}"
        // Keep the isolated evidence within the real provider's configured root. A cacheDir
        // filesDir substitute would fail FileProvider without testing the production URI path.
        val root = File(production.filesDir, "screen-test/evidence/$token").apply { check(mkdirs()) }
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(production) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                production.getSharedPreferences("$token-$name".also { preferences.add(it) }, mode)
        }
        init {
            // FileProvider caches its path strategy process-wide by authority, not Context.
            // Resolve the actual app's roots before any isolated wrapper can seed that cache.
            FileProvider.getUriForFile(production, "${production.packageName}.apk-files",
                File(production.filesDir, "screen-test/evidence/provider-root-fixture.png"))
            check(context.getSharedPreferences("device_access", Context.MODE_PRIVATE).edit()
                .putString("mode", "root").commit())
        }
        val access = DeviceAccessController(context)
        val virtual = VirtualScreenController(context, access)
        val tasks = ScreenTestController(context, virtual, access)
        override fun close() {
            Log.i("ZakoVirtualTaskTest", "stage=fixture_close_task")
            tasks.stop()
            Log.i("ZakoVirtualTaskTest", "stage=fixture_close_virtual")
            virtual.close()
            runBlocking { withTimeout(15_000) { virtual.awaitStopped() } }
            Log.i("ZakoVirtualTaskTest", "stage=fixture_close_access")
            access.stop()
            preferences.toList().forEach(production::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(100)
    }
    private suspend fun awaitDisplayClosed(context: Context, id: Int) {
        val displays = context.getSystemService(DisplayManager::class.java)
        await { displays.getDisplay(id) == null }
    }
    private suspend fun physicalActivity(access: DeviceAccessController): String {
        val output = access.execute("/system/bin/dumpsys activity activities", 20).stdout
        val displays = Regex("(?m)^\\s*Display #(\\d+)").findAll(output).toList()
        val index = displays.indexOfFirst { it.groupValues[1] == "0" }
        check(index >= 0) { "physical_display_activity_not_available" }
        val section = output.substring(displays[index].range.first, displays.getOrNull(index + 1)?.range?.first ?: output.length)
        return Regex("(?:mResumedActivity|topResumedActivity|ResumedActivity):?[^\\n]*?([A-Za-z][A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)")
            .find(section)?.groupValues?.get(1) ?: error("physical_display_activity_not_available")
    }
    private fun stage(name: String) { Log.i("ZakoVirtualTaskTest", "stage=$name") }
    private fun action(name: String) = JSONObject().put("action", name)
    /** Only observations repeat. A node action always uses the latest stable snapshot once. */
    private suspend fun stableObservation(f: Fixture, owner: String, lease: String): JSONObject = withTimeout(15_000) {
        var previous: String? = null
        var sample = 0
        var equalSamples = 0
        val started = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val current = observe(f, owner, lease)
            val projection = current.getJSONArray("nodes").toString()
            sample++
            stage("observe_sample_${sample}_stable_${projection == previous}")
            equalSamples = if (projection == previous) equalSamples + 1 else 0
            // Initial focus/layout transitions can follow the first equal pair. Settle this
            // fixture before dispatching its one write; never replay a dispatched action.
            if (equalSamples >= 2 && android.os.SystemClock.elapsedRealtime() - started >= 1500 &&
                !current.optBoolean("truncated")) return@withTimeout current
            previous = projection
            delay(200)
        }
        @Suppress("UNREACHABLE_CODE") error("stable_observation_unavailable")
    }
    private suspend fun observe(f: Fixture, project: String, lease: String) =
        JSONObject(f.tasks.execute(project, action("observe"), expectedSession = lease).output)
    private fun texts(observation: JSONObject): List<String> = observation.getJSONArray("nodes").let { nodes ->
        (0 until nodes.length()).map { nodes.getJSONObject(it).optString("text") }
    }

    @Test(timeout = 180_000) fun taskLeaseControlsRealDisplayScreenshotSharingFinishAndRootRevocation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val production = instrumentation.targetContext
        Fixture(production).use { f ->
            try {
                stage("lease_preferences_loading")
                withTimeout(10_000) { f.access.state.first { it.loaded } }
                assertEquals(DeviceMode.Root, f.access.state.value.mode)
                assertFalse("Persisted selection alone must not restore a grant", f.access.state.value.rootGranted)
                stage("lease_root_grant")
                assertTrue("This device-only integration case requires real Root", f.access.ensureRoot())
                val project = "task-fixture"
                assertTrue("App discovery cannot create a task", runCatching {
                    f.tasks.execute(project, action("list_apps").put("text", instrumentation.context.packageName))
                }.exceptionOrNull() is ScreenTestException)
                assertFalse(f.virtual.state.value.active)
                stage("lease_first_authorize")
                val lease = f.tasks.authorizeDeviceTask(project, "task-first")
                stage("lease_first_authorized")
                val id = requireNotNull(f.virtual.state.value.displayId)
                assertTrue(id > 0)
                assertEquals(lease, f.tasks.taskLease(project))
                assertTrue(f.tasks.state.value.active)
                assertFalse(f.tasks.state.value.shareScreenshots)
                stage("lease_first_discovery")
                val physicalBeforeDiscovery = physicalActivity(f.access)
                val discovered = JSONObject(f.tasks.execute(project,
                    action("list_apps").put("text", instrumentation.context.packageName), expectedSession = lease).output)
                assertEquals(id, discovered.getInt("display_id"))
                val apps = discovered.getJSONArray("apps")
                val fixtureApp = (0 until apps.length()).map(apps::getJSONObject)
                    .single { it.getString("package_name") == instrumentation.context.packageName }
                assertEquals(id, f.virtual.state.value.displayId)
                assertEquals("App discovery must not move the physical activity", physicalBeforeDiscovery, physicalActivity(f.access))
                assertFalse("Listing applications never launches one", f.virtual.state.value.packageName != null)
                stage("lease_first_launch")
                val launched = JSONObject(f.tasks.execute(project, action("launch")
                    .put("package_name", fixtureApp.getString("package_name")), expectedSession = lease).output)
                assertTrue(launched.getBoolean("verified"))
                assertEquals(id, launched.getInt("display_id"))
                stage("lease_first_observe")
                var observation = observe(f, project, lease)
                assertEquals(id, observation.getInt("display_id"))
                assertTrue(texts(observation).contains("Virtual screen fixture"))
                assertTrue(texts(observation).contains("Count: 0"))

                stage("lease_screenshot_permission_boundaries")
                val noSharing = runCatching {
                    f.tasks.execute(project, action("screenshot"), expectedSession = lease)
                }.exceptionOrNull()
                assertTrue("Screenshots require a separate explicit sharing grant", noSharing is ScreenTestException)
                assertEquals(production.getString(R.string.virtual_screen_sharing_required), noSharing?.message)
                assertFalse(f.tasks.state.value.shareScreenshots)

                val wrongSharingLease = runCatching {
                    f.tasks.setScreenshotSharing(project, true, expectedLease = "expired-$lease")
                }.exceptionOrNull()
                assertTrue(wrongSharingLease is IllegalStateException)
                assertFalse(f.tasks.state.value.shareScreenshots)
                f.tasks.setScreenshotSharing(project, true, expectedLease = lease)
                stage("lease_first_capture")
                val captured = f.tasks.execute(project, action("screenshot"), expectedSession = lease)
                assertTrue(JSONObject(captured.output).getBoolean("captured"))
                assertEquals(id, JSONObject(captured.output).getInt("display_id"))
                val uri = requireNotNull(captured.imageUri)
                assertEquals("content", uri.scheme)
                assertEquals("${production.packageName}.apk-files", uri.authority)
                val png = withContext(Dispatchers.IO) {
                    requireNotNull(production.contentResolver.openInputStream(uri)).use { it.readBytes() }
                }
                withContext(Dispatchers.IO) {
                    val evidence = File(production.cacheDir, "ui-review/screen-testing").apply { check(mkdirs() || isDirectory) }
                    File(evidence, "virtual-task-target.png").writeBytes(png)
                }
                assertTrue(png.size > 8)
                assertTrue(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                    .contentEquals(png.copyOf(8)))
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
                try {
                    assertEquals(f.virtual.state.value.width, bitmap.width)
                    assertEquals(f.virtual.state.value.height, bitmap.height)
                } finally { bitmap.recycle() }

                stage("lease_first_capture_complete")
                observation = observe(f, project, lease)
                val increment = observation.getJSONArray("nodes").let { nodes ->
                    (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "Increment" }
                }
                assertTrue("A task from another workspace cannot use this display", runCatching {
                    f.tasks.execute("different-owner", action("observe"), expectedSession = lease)
                }.exceptionOrNull() is ScreenTestException)
                assertNull(f.tasks.taskLease("different-owner"))
                stage("lease_reject_expired_action")
                val wrongActionLease = runCatching {
                    f.tasks.execute(project, action("click").put("snapshot", observation.getString("snapshot"))
                        .put("node", increment.getString("id")), expectedSession = "expired-$lease")
                }.exceptionOrNull()
                assertTrue(wrongActionLease is ScreenTestException)
                assertTrue("Rejected lease must not inject a click", texts(observe(f, project, lease)).contains("Count: 0"))
                f.tasks.finishTask(project, "expired-$lease")
                assertEquals("An old finish must not revoke a live task", lease, f.tasks.taskLease(project))

                stage("lease_first_finish")
                f.tasks.finishTask(project, lease)
                await { !f.tasks.state.value.active && !f.virtual.state.value.active }
                stage("lease_first_await_display_closed")
                awaitDisplayClosed(production, id)
                assertNull(f.tasks.taskLease(project))
                assertFalse(f.tasks.state.value.shareScreenshots)
                assertTrue(runCatching { observe(f, project, lease) }.isFailure)
                assertTrue(runCatching { f.tasks.setScreenshotSharing(project, true, expectedLease = lease) }.isFailure)

                stage("lease_second_authorize")
                val nextLease = f.tasks.authorizeDeviceTask(project, "task-after-finish")
                stage("lease_second_authorized")
                assertNotEquals(lease, nextLease)
                val nextId = requireNotNull(f.virtual.state.value.displayId)
                assertTrue(nextId > 0)
                assertFalse("A new task never inherits screenshot consent", f.tasks.state.value.shareScreenshots)
                stage("lease_second_launch")
                f.tasks.execute(project, action("launch").put("package_name", instrumentation.context.packageName), nextLease)
                stage("lease_second_observe")
                assertTrue(texts(observe(f, project, nextLease)).contains("Virtual screen fixture"))
                assertTrue(runCatching { observe(f, project, lease) }.isFailure)
                val generation = f.access.generation
                stage("lease_revoke_root")
                f.access.useBasic()
                assertTrue(f.access.generation > generation)
                await { !f.tasks.state.value.active && !f.virtual.state.value.active }
                stage("lease_second_await_display_closed")
                awaitDisplayClosed(production, nextId)
                assertNull(f.tasks.taskLease(project))
                assertTrue(runCatching { observe(f, project, nextLease) }.isFailure)
                assertFalse(f.access.ensureRoot())
                assertTrue(runCatching { f.tasks.authorizeDeviceTask(project, "must-not-reopen-in-basic") }.isFailure)
                assertFalse(f.virtual.state.value.active)
                stage("lease_passed")
            } finally {
                stage("lease_cleanup_stop")
                f.tasks.stop()
                f.virtual.stop()
                stage("lease_cleanup_audit")
                // Drain the controller-owned report chain before deleting only this fixture's files.
                runCatching { f.tasks.readReport("task-fixture") }
                stage("lease_cleanup_complete")
            }
        }
    }

    @Test(timeout = 120_000) fun passwordNodesStayRedactedAndCannotBeEditedCapturedOrAudited() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Fixture(instrumentation.targetContext).use { f ->
            val owner = "sensitive-task-fixture"
            try {
                stage("privacy_preferences_loading")
                withTimeout(10_000) { f.access.state.first { it.loaded } }
                stage("privacy_root_grant")
                assertTrue("A real Root grant is required", f.access.ensureRoot())
                stage("privacy_authorize_display")
                val lease = f.tasks.authorizeDeviceTask(owner, "privacy-task")
                stage("privacy_launch_fixture")
                f.tasks.execute(owner, action("launch").put("package_name", instrumentation.context.packageName), lease)
                stage("privacy_initial_stable_observation")
                var observed = stableObservation(f, owner, lease)
                val button = observed.getJSONArray("nodes").let { nodes ->
                    (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "Password page" }
                }
                stage("privacy_open_password_page")
                f.tasks.execute(owner, action("click").put("snapshot", observed.getString("snapshot"))
                    .put("node", button.getString("id")), lease)
                stage("privacy_password_stable_observation")
                observed = stableObservation(f, owner, lease)
                assertFalse(observed.toString().contains("fixture-only-secret"))
                val protected = observed.getJSONArray("nodes").let { nodes ->
                    (0 until nodes.length()).map(nodes::getJSONObject).first { it.optBoolean("sensitive") }
                }
                assertEquals("[redacted]", protected.getString("text"))
                assertEquals("[redacted]", protected.getString("description"))
                stage("privacy_reject_sensitive_input")
                val inputFailure = runCatching {
                    f.tasks.execute(owner, action("input_text").put("snapshot", observed.getString("snapshot"))
                        .put("node", protected.getString("id")).put("text", "replacement-not-allowed"), lease)
                }.exceptionOrNull()
                assertTrue("Sensitive-node protection, rather than an unrelated stale snapshot, must reject input",
                    inputFailure is ScreenTestException && inputFailure.message.orEmpty().contains("virtual_display_sensitive_node"))
                // A screenshot sharing grant does not bypass sensitive-frame protection.
                stage("privacy_reject_sensitive_capture")
                f.tasks.setScreenshotSharing(owner, true, expectedLease = lease)
                val captureFailure = runCatching { f.tasks.execute(owner, action("screenshot"), lease) }.exceptionOrNull()
                assertTrue("Explicit sharing must still reject sensitive frames",
                    captureFailure is ScreenTestException && captureFailure.message.orEmpty().contains("virtual_display_sensitive_frame"))
                stage("privacy_reject_secret_assertion")
                val assertionFailure = runCatching {
                    f.tasks.execute(owner, action("assert_text").put("text", "fixture-only-secret"), lease)
                }.exceptionOrNull()
                assertTrue("Sensitive text cannot satisfy a text assertion",
                    assertionFailure is ScreenTestException && assertionFailure.message.orEmpty().contains("virtual_display_assert_failed"))
                stage("privacy_finish_task")
                f.tasks.finishTask(owner, lease)
                stage("privacy_drain_audit")
                val report = f.tasks.readReport(owner)
                assertTrue(report.contains("\"action\":\"input_text\""))
                val entries = report.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
                for (action in listOf("input_text", "screenshot", "assert_text")) {
                    assertFalse("The rejected $action must be audited as failed",
                        entries.single { it.getString("action") == action }.getBoolean("operationSucceeded"))
                }
                assertFalse(report.contains("fixture-only-secret"))
                assertFalse(report.contains("replacement-not-allowed"))
                assertFalse(report.contains("[redacted]"))
                stage("privacy_passed")
            } finally {
                stage("privacy_cleanup_stop")
                f.tasks.stop()
                stage("privacy_cleanup_audit")
                runCatching { f.tasks.readReport(owner) }
                stage("privacy_cleanup_complete")
            }
        }
    }

}

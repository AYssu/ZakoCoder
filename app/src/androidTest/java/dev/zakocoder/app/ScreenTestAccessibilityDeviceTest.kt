package dev.zakocoder.app

import android.app.UiAutomation
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.screentest.ScreenTestAccessibilityService
import dev.zakocoder.app.screentest.ScreenTestController
import dev.zakocoder.app.screentest.ScreenTestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/**
 * Requires the disposable signed Debug fixture at /data/local/tmp/fixture.apk and its installed app.
 * The test owner explicitly enables the isolated host's service before running this class.
 * No secure settings, root grants, real projects, or model credentials are changed by this test.
 */
class ScreenTestAccessibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val projectId = "screen-accessibility-fixture-${UUID.randomUUID()}"
    private val ownedInstallCopies = mutableSetOf<File>()
    private val automation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun authorizedDeviceTaskSupportsLiveChineseUiActionsEvidenceAndStop() = runBlocking {
        withFixture { controller, _ ->
            val status = json(controller, "status")
            assertTrue(status.getBoolean("active"))
            assertEquals("current_device", status.getString("scope"))
            assertFalse(status.getBoolean("taskAuthorizationRequired"))
            assertEquals("Accessibility", status.getString("access"))
            assertTrue(status.getBoolean("accessibilityEnabled"))

            val initial = observeUntil(controller, "Counter: 0")
            val increment = node(initial) { it.optString("text") == "Increment" }
            action(controller, "click", initial, increment)
            observeUntil(controller, "Counter: 1")
            assertEquals("passed", json(controller, "assert_text") { it.put("text", "Counter: 1") }.getString("assertion"))
            assertTrue(runCatching { json(controller, "assert_text") { it.put("text", "Counter: 999") } }.exceptionOrNull() is ScreenTestException)
            assertTrue(runCatching { action(controller, "click", initial, increment) }.exceptionOrNull() is ScreenTestException)

            val beforeInput = json(controller, "observe")
            val input = node(beforeInput) { it.optBoolean("editable") && !it.optBoolean("sensitive") }
            action(controller, "input_text", beforeInput, input) { it.put("text", "小明") }
            val afterInput = observeUntil(controller, "小明")
            val greet = node(afterInput) { it.optString("text") == "Greet" }
            action(controller, "click", afterInput, greet)
            observeUntil(controller, "Hello 小明")
            assertEquals("passed", json(controller, "assert_text") { it.put("text", "Hello 小明") }.getString("assertion"))

            val result = controller.execute(projectId, JSONObject().put("action", "screenshot"))
            val uri = requireNotNull(result.imageUri)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.apk-files", uri.authority)
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri).use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(requireNotNull(inputStream))
                        assertNotNull("Screenshot must contain actual decodable image pixels", bitmap)
                        requireNotNull(bitmap)
                        try { assertTrue(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
                    }
                }
            } finally {
                // Remove only the single screenshot created by this test.
                withContext(Dispatchers.IO) { File(context.filesDir, "screen-test/evidence/${uri.lastPathSegment}").delete() }
            }

            // The host's own approval controls stay outside agent control, even in device scope.
            shell("am start -W -n ${context.packageName}/dev.zakocoder.app.MainActivity")
            delay(500)
            assertTrue(runCatching { json(controller, "observe") }.exceptionOrNull() is ScreenTestException)
            controller.stop()
            assertFalse(controller.state.value.active)
            assertFalse(controller.state.value.shareScreenshots)
            assertTrue(runCatching { json(controller, "back") }.exceptionOrNull() is ScreenTestException)
            val report = controller.readReport(projectId)
            assertTrue(report.contains("\"action\":\"click\""))
            assertTrue(report.contains("\"assertionPassed\":false"))
            assertTrue(report.contains("\"action\":\"screenshot\""))
            assertFalse("Typed text must not be persisted in operation audit", report.contains("小明"))
        }
    }

    @Test fun passwordNodesAreRedactedAndCannotBeEditedOrCaptured() = runBlocking {
        withFixture { controller, _ ->
            val initial = observeUntil(controller, "Counter: 0")
            val passwordPage = node(initial) { it.optString("text") == "Password page" }
            action(controller, "click", initial, passwordPage)
            delay(300)
            val password = withTimeout(5000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) {
                    found = automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == TARGET }?.let(::findPassword)
                    if (found == null) delay(100)
                }
                requireNotNull(found)
            }
            val syntheticSecret = "private-screen-test-secret"
            assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, syntheticSecret) }))
            delay(200)
            val observed = json(controller, "observe")
            assertFalse(observed.toString().contains(syntheticSecret))
            val protected = node(observed) { it.optBoolean("sensitive") }
            assertEquals("[redacted]", protected.getString("text"))
            assertEquals("[redacted]", protected.getString("description"))
            assertTrue(runCatching {
                action(controller, "input_text", observed, protected) { it.put("text", "blocked replacement") }
            }.exceptionOrNull() is ScreenTestException)
            assertTrue(runCatching { json(controller, "screenshot") }.exceptionOrNull() is ScreenTestException)
            assertTrue(runCatching { json(controller, "assert_text") { it.put("text", syntheticSecret) } }.exceptionOrNull() is ScreenTestException)
            controller.finishTask(projectId)
            val report = controller.readReport(projectId)
            assertTrue(report.contains("\"action\":\"finish\""))
            assertFalse(report.contains(syntheticSecret))
        }
    }

    @Test fun staleLeaseCannotStopReplacementAndProjectApkChangesDoNotAffectScreenTask() = runBlocking {
        withFixture { controller, project ->
            val firstLease = requireNotNull(controller.taskLease(projectId))
            val secondLease = controller.authorizeDeviceTask(projectId, UUID.randomUUID().toString())
            assertNotEquals(firstLease, secondLease)
            controller.finishTask(projectId, firstLease)
            controller.finishTask(projectId, null)
            assertTrue(controller.state.value.active)
            assertEquals(secondLease, controller.taskLease(projectId))
            assertTrue(runCatching {
                controller.execute(projectId, JSONObject().put("action", "observe"), firstLease)
            }.exceptionOrNull() is ScreenTestException)
            // Screen authority no longer derives from APK bytes, presence, or debug flags.
            withContext(Dispatchers.IO) { File(project, "app-debug.apk").delete() }
            val observed = json(controller, "observe")
            assertEquals(TARGET, observed.getString("package"))
            assertFalse(json(controller, "status").has("artifactMatches"))
            controller.stop()
            val report = controller.readReport(projectId)
            assertTrue(report.contains("\"action\":\"stop\""))
            assertTrue(report.contains("current_device_task_authorized"))
        }
    }

    /** Optional second disposable install; never substitute a user's personal application. */
    @Test fun taskCanSwitchBetweenDisposableAppsButCannotReuseAnotherAppsSnapshot() = runBlocking {
        val other = "dev.zakocoder.screentest.fixture.secondary"
        org.junit.Assume.assumeTrue("Install the optional secondary disposable fixture", shell("pm path $other").contains("package:"))
        withFixture { controller, _ ->
            val first = observeUntil(controller, "Counter: 0")
            val oldButton = node(first) { it.optString("text") == "Increment" }
            shell("am force-stop $other")
            shell("am start -W -n $other/dev.zakocoder.screentest.fixture.MainActivity")
            delay(600)
            try {
                assertTrue(runCatching { action(controller, "click", first, oldButton) }.exceptionOrNull() is ScreenTestException)
                val fresh = observeUntil(controller, "Counter: 0")
                assertEquals(other, fresh.getString("package"))
                val freshButton = node(fresh) { it.optString("text") == "Increment" }
                action(controller, "click", fresh, freshButton)
                observeUntil(controller, "Counter: 1")
                shell("am start -W -n $TARGET/.MainActivity")
                delay(600)
                val returned = observeUntil(controller, "Counter: 0")
                assertEquals(TARGET, returned.getString("package"))
                assertTrue(controller.state.value.active)
            } finally { shell("am force-stop $other") }
        }
    }

    private suspend fun action(controller: ScreenTestController, action: String, snapshot: JSONObject, node: JSONObject,
        extra: (JSONObject) -> Unit = {}): JSONObject = json(controller, action) {
        it.put("snapshot", snapshot.getString("snapshot")).put("node", node.getString("id"))
        extra(it)
    }

    private suspend fun json(controller: ScreenTestController, action: String, extra: (JSONObject) -> Unit = {}): JSONObject {
        val args = JSONObject().put("action", action).also(extra)
        val result = controller.execute(projectId, args)
        return JSONObject(result.output)
    }

    private suspend fun observeUntil(controller: ScreenTestController, expected: String): JSONObject = withTimeout(5000) {
        while (true) {
            val observed = json(controller, "observe")
            val nodes = observed.getJSONArray("nodes")
            if ((0 until nodes.length()).any { expected in nodes.getJSONObject(it).optString("text") }) return@withTimeout observed
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    private fun node(snapshot: JSONObject, predicate: (JSONObject) -> Boolean): JSONObject {
        val nodes = snapshot.getJSONArray("nodes")
        return (0 until nodes.length()).map(nodes::getJSONObject).firstOrNull(predicate)
            ?: error("Expected fixture node missing: $snapshot")
    }

    private fun findPassword(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isPassword && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { findPassword(it)?.let { found -> return found } }
        }
        return null
    }

    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().take(16_384) }
        }
    }

    private fun installCopies(): Set<File> = File(context.cacheDir, "apk-install")
        .listFiles().orEmpty().filter { it.isFile }.toSet()

    private suspend fun withFixture(block: suspend (ScreenTestController, File) -> Unit) {
        // Connecting with this flag preserves the service the test owner explicitly enabled.
        automation
        withTimeout(10_000) { while (!ScreenTestAccessibilityService.isConnected()) delay(50) }
        val directory = withContext(Dispatchers.IO) {
            File(context.cacheDir, "screen-a11y-${UUID.randomUUID()}").apply { check(mkdirs()) }
        }
        val project = withContext(Dispatchers.IO) { File(directory, "project").apply { mkdirs() } }
        val controller = ScreenTestController(context) { id -> check(id == projectId); project }
        try {
            withContext(Dispatchers.IO) {
                automation.executeShellCommand("cat /data/local/tmp/fixture.apk").use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        File(project, "app-debug.apk").outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            controller.setAccess(ScreenTestAccess.Accessibility)
            controller.authorizeDeviceTask(projectId, UUID.randomUUID().toString())
            assertTrue(controller.state.value.enabled)
            assertTrue(controller.state.value.connected)
            shell("am force-stop $TARGET")
            val launched = shell("am start -W -n $TARGET/.MainActivity")
            assertFalse(launched, launched.contains("Error:"))
            delay(600)
            block(controller, project)
        } finally {
            controller.stop()
            try { controller.readReport(projectId) } finally {
                shell("am force-stop $TARGET")
                withContext(Dispatchers.IO) {
                    val reportKey = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val report = File(context.filesDir, "screen-test/reports/$reportKey.jsonl")
                    android.util.AtomicFile(report).delete()
                    ownedInstallCopies.forEach { it.delete() }
                    directory.deleteRecursively()
                }
            }
        }
    }

    companion object {
        private const val TARGET = "dev.zakocoder.screentest.fixture"
    }
}

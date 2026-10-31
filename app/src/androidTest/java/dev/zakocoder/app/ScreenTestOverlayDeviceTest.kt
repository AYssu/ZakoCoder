package dev.zakocoder.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.ScreenTestAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in only: the owner supplies an existing acceptance project, never arbitrary user projects. */
class ScreenTestOverlayDeviceTest {
    @Test(timeout = 120000) fun selectedGameOverlayHidesForObservationAndRejectsOldLease() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("overlayAcceptance") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "dev.zakocoder.app")
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        delay(500)
        val project = arguments.getString("acceptanceProjectId").orEmpty()
        require(UUID.fromString(project).toString() == project)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        withTimeout(30000) { while (workbench.state.value.loading) delay(100) }
        check(!workbench.ai.state.value.active)
        check(workbench.state.value.projects.any { it.id == project })
        val controller = workbench.screenTests
        assertFalse("A process restart must not silently enable an overlay", controller.state.value.overlayEnabled)
        if (!Settings.canDrawOverlays(context)) {
            withContext(Dispatchers.Main) { controller.setOverlayEnabled(true) }
            assertFalse(controller.state.value.overlayEnabled)
            assertFalse(controller.state.value.overlayVisible)
            assumeTrue("Grant the optional system permission through Settings for the integration phase", false)
        }
        var hiddenWhileBusy = false
        val observer = launch(Dispatchers.Unconfined) {
            controller.state.collect { if (it.busy && !it.overlayVisible) hiddenWhileBusy = true }
        }
        try {
            withContext(Dispatchers.Main) { controller.setAccess(ScreenTestAccess.Root) }
            check(controller.requestRootAccess())
            val apk = controller.selectApk(project, "app/build/outputs/apk/debug/app-debug.apk")
            val firstLease = requireNotNull(controller.taskLease(project))
            withContext(Dispatchers.Main) { controller.setOverlayEnabled(true) }
            withTimeout(10000) { while (!controller.state.value.overlayVisible) delay(50) }
            // Foreground assistance is test orchestration, not proof of unattended OEM launch.
            // Root was explicitly authorized above; only the selected acceptance APK is started.
            val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(apk.packageName)?.component)
            check(component.packageName == apk.packageName)
            val target = component.flattenToString()
            require(target.matches(Regex("[A-Za-z0-9_./]+")))
            withContext(Dispatchers.IO) {
                val child = ProcessBuilder("su", "-c", "am start -W -n $target").redirectErrorStream(true).start()
                try {
                    check(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                    val output = child.inputStream.bufferedReader().use { it.readText() }
                    check(child.exitValue() == 0)
                    check(output.contains("Status: ok")) { "The selected activity was not launched" }
                } finally { child.destroy() }
            }
            delay(1200)
            val result = JSONObject(controller.execute(project, JSONObject().put("action", "observe"), firstLease).output)
            assertEquals(apk.packageName, result.getString("package"))
            assertTrue(hiddenWhileBusy)
            val nodes = result.getJSONArray("nodes")
            val restart = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "新游戏" }
            controller.execute(project, JSONObject().put("action", "click").put("snapshot", result.getString("snapshot"))
                .put("node", restart.getString("id")), firstLease)
            withTimeout(10000) { while (!controller.state.value.overlayVisible) delay(50) }
            delay(300)
            val directory = File(context.cacheDir, "screen-overlay-review").apply { mkdirs() }
            val name = if (context.resources.configuration.uiMode and 0x30 == 0x20) "night" else "day"
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, "overlay-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            instrumentation.sendStatus(0, Bundle().apply { putString("overlayScreenshot", directory.absolutePath) })
            val automation = instrumentation.uiAutomation
            val originalInfo = automation.serviceInfo
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            try {
                fun node(text: String): android.view.accessibility.AccessibilityNodeInfo? = automation.windows
                    .mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByText(text) }
                    .firstOrNull { it.packageName?.toString() == context.packageName && it.text?.toString() == text }
                val handle = withTimeout(5000) {
                    var found = node(context.getString(R.string.screen_overlay_handle))
                    while (found == null) { delay(100); found = node(context.getString(R.string.screen_overlay_handle)) }
                    found
                }
                assertTrue(handle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                delay(400)
                val expanded = checkNotNull(automation.takeScreenshot())
                File(directory, "overlay-$name-expanded.png").outputStream().use { expanded.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                expanded.recycle()
                val returnButton = checkNotNull(node(context.getString(R.string.screen_overlay_return)))
                assertTrue(returnButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(10000) { while (controller.state.value.overlayVisible || controller.state.value.overlayEnabled) delay(50) }
                try { withTimeout(10000) {
                    while (automation.rootInActiveWindow?.packageName?.toString() != context.packageName ||
                        workbench.ai.state.value.projectId != project || workbench.assistantNavigation.value != null) delay(100)
                } } finally {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putBoolean("returnHostForeground", automation.rootInActiveWindow?.packageName?.toString() == context.packageName)
                        putBoolean("returnProjectMatches", workbench.ai.state.value.projectId == project)
                        putBoolean("returnNavigationConsumed", workbench.assistantNavigation.value == null)
                    })
                }
                assertEquals(firstLease, controller.taskLease(project))
                withContext(Dispatchers.Main) { controller.setOverlayEnabled(true) }
                withTimeout(10000) { while (!controller.state.value.overlayVisible) delay(50) }
            } finally { automation.serviceInfo = originalInfo }
            controller.selectApk(project, "app/build/outputs/apk/debug/app-debug.apk")
            val secondLease = requireNotNull(controller.taskLease(project))
            assertNotEquals(firstLease, secondLease)
            assertFalse(controller.stopOverlayTask(project, firstLease))
            assertEquals(secondLease, controller.taskLease(project))
            assertTrue(controller.stopOverlayTask(project, secondLease))
            withTimeout(10000) { while (controller.state.value.overlayVisible) delay(50) }
            assertFalse(controller.state.value.active)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { controller.setOverlayEnabled(false); controller.stop() }
            observer.cancelAndJoin()
        }
    }
}

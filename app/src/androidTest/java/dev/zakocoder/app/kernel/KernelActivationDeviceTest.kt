package dev.zakocoder.app.kernel

import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real plugin recovery proof. Run on an isolated main test package with the matching plugin. */
class KernelActivationDeviceTest {
    @Test(timeout = 45_000) fun explicitActivationClearsStoppedStateAndAllowsBinding() = runBlocking {
        assumeTrue("Explicit local opt-in", InstrumentationRegistry.getArguments().getString("kernelPluginE2E") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = KernelPluginManager(context)
        assertTrue("Install a matching plugin first", manager.status.value is KernelPluginStatus.Installed)
        shell(instrumentation, "am force-stop ${KernelPluginTrust.PLUGIN_PACKAGE}")
        await("Plugin must enter the forced-stop state") { pluginStopped(context) }
        val before = manager.diagnostics()
        assertTrue("Diagnostics did not report the forced-stop state:\n$before", before.contains("plugin_stopped=true"))
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(KernelPluginActivationResult.Opened, manager.openActivation(it)) }
            await("Explicit plugin page did not open") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == KernelPluginTrust.PLUGIN_PACKAGE
            }
            await("Plugin remained in the stopped state after explicit activation") { !pluginStopped(context) }
            // Resume main explicitly, as a user returning from the activation page would.
            warmDeviceActivity()
            try {
                val service = withContext(Dispatchers.IO) { manager.bind() }
                assertNotNull("Plugin failed to bind after activation", service)
                assertEquals(1, withContext(Dispatchers.IO) { service!!.contractVersion() })
                val after = manager.diagnostics()
                assertTrue("Diagnostics still report a stopped plugin:\n$after", after.contains("plugin_stopped=false"))
                File(context.getExternalFilesDir(null), "kernel-activation.txt").writeText("BEFORE\n$before\nAFTER\n$after")
            } finally { manager.unbind() }
        }
    }

    private fun shell(instrumentation: android.app.Instrumentation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    private fun pluginStopped(context: android.content.Context): Boolean =
        context.packageManager.getApplicationInfo(KernelPluginTrust.PLUGIN_PACKAGE, 0).flags and ApplicationInfo.FLAG_STOPPED != 0

    private suspend fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) fail("$label within 10 seconds")
            delay(100)
        }
    }
}

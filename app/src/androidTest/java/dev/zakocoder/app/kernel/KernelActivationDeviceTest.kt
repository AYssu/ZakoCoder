package dev.zakocoder.app.kernel

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
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
        warmDeviceActivity()
        val before = manager.diagnostics()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals(KernelPluginActivationResult.Opened, manager.openActivation(it)) }
            var active = false
            repeat(40) {
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == KernelPluginTrust.PLUGIN_PACKAGE) active = true
                if (!active) Thread.sleep(100)
            }
            assertTrue("Explicit plugin page did not open", active)
            val app = context.packageManager.getApplicationInfo(KernelPluginTrust.PLUGIN_PACKAGE, 0)
            assertEquals(0, app.flags and ApplicationInfo.FLAG_STOPPED)
            // Resume main explicitly, as a user returning from the activation page would.
            warmDeviceActivity()
            try {
                val service = withContext(Dispatchers.IO) { manager.bind() }
                assertNotNull("Plugin failed to bind after activation", service)
                assertEquals(1, withContext(Dispatchers.IO) { service!!.contractVersion() })
                File(context.getExternalFilesDir(null), "kernel-activation.txt").writeText("BEFORE\n$before\nAFTER\n${manager.diagnostics()}")
            } finally { manager.unbind() }
        }
    }
}

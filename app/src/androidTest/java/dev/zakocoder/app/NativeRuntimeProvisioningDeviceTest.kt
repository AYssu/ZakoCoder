package dev.zakocoder.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.ToolStatus
import dev.zakocoder.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in provisioning only for the isolated verification package, never the user's application. */
class NativeRuntimeProvisioningDeviceTest {
    @Test(timeout = 1200000) fun installIsolatedEnvironmentAndTools() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.zakocoder.app.runtimecheck", context.packageName)
        val runtime = LinuxRuntime(context)
        runtime.workspaces.mkdirs()
        val settings = AppSettings()
        if (!runtime.isInstalled()) {
            val image = File(context.cacheDir, "native-runtime-verification/rootfs.tar.xz")
            assertTrue("Provide the pinned image using checks/device.py", image.isFile)
            runtime.install(settings, Uri.fromFile(image)) {}
        }
        val tools = DevelopmentTools.required(setOf(ProjectKind.Python, ProjectKind.Node))
        val before = runtime.execute(DevelopmentTools.doctorScript(tools), null, settings)
        val packages = DevelopmentTools.packages(DevelopmentTools.parse(before.output, tools))
        if (packages.isNotEmpty()) {
            val installed = runtime.execute(DevelopmentTools.installScript(packages), null, settings,
                timeoutMillis = 15 * 60 * 1000L)
            assertEquals(installed.output.takeLast(5000), 0, installed.exitCode)
        }
        val after = runtime.execute(DevelopmentTools.doctorScript(tools), null, settings)
        val checks = DevelopmentTools.parse(after.output, tools)
        assertTrue(checks.toString(), checks.all { it.status == ToolStatus.Ready })
    }
}

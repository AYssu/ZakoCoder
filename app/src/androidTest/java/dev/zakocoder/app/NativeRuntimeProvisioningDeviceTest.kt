package dev.zakocoder.app

import android.net.Uri
import android.os.Build
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
import java.util.UUID

/** Opt-in provisioning only for the isolated verification package, never the user's application. */
class NativeRuntimeProvisioningDeviceTest {
    /** Ubuntu only: does not install Node, Python, SDK, NDK, or any project dependencies. */
    @Test(timeout = 1200000) fun installIsolatedBaseEnvironment() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireIsolatedDevice()
        val runtime = LinuxRuntime(context)
        runtime.workspaces.mkdirs()
        installBase(runtime)
        val result = runtime.execute("printf 'BASE_RUNTIME_OK\\n'; /bin/pwd; id", null, AppSettings())
        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("BASE_RUNTIME_OK"))
    }

    /** Explicit network provisioning for the HTTP matrix; does not install Node/npm or Android tools. */
    @Test(timeout = 1200000) fun installIsolatedPythonEnvironment(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireIsolatedDevice()
        val runtime = LinuxRuntime(context)
        runtime.workspaces.mkdirs()
        val settings = AppSettings()
        installBase(runtime)
        val tools = DevelopmentTools.required(setOf(ProjectKind.Python)).filter { it.id in setOf("bash", "python") }
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

    private fun requireIsolatedDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (context.packageName == "dev.zakocoder.app.runtimecheck") return
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("compatibilityProvisionRuntime") == "true") { "Provisioning requires an explicit opt-in" }
        UUID.fromString(requireNotNull(arguments.getString("compatibilityRunId")))
        require(Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm", "cutf_ivshmem", "cuttlefish", "vsoc_x86_64")) {
            "Compatibility provisioning is limited to a disposable emulator, never the user's phone"
        }
        require(context.packageName == "dev.zakocoder.app")
    }

    private suspend fun installBase(runtime: LinuxRuntime) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!runtime.isInstalled()) {
            val image = File(context.cacheDir, "native-runtime-verification/rootfs.tar.xz")
            assertTrue("Host must provide the pinned image for this installed app ABI", image.isFile)
            runtime.install(AppSettings(), Uri.fromFile(image)) {}
        }
    }

    @Test(timeout = 1200000) fun installIsolatedEnvironmentAndTools() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireIsolatedDevice()
        val runtime = LinuxRuntime(context)
        runtime.workspaces.mkdirs()
        val settings = AppSettings()
        installBase(runtime)
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

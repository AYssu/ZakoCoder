package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.ToolStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit opt-in provisioning check: downloads the pinned rootfs and installs Node/Python packages.
 * Invoke this class separately; never run against an unspecified device. No fixture project is retained.
 */
class RuntimeProvisioningTest {
    @Test fun provisionUsingApplicationActions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val workbench = (instrumentation.targetContext.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            val proxy = InstrumentationRegistry.getArguments().getString("proxy").orEmpty()
            instrumentation.runOnMainSync { workbench.saveSettings(workbench.state.value.settings.copy(proxy = proxy)) }
            awaitIdle(workbench, 10000)
            assertEquals(workbench.state.value.message, proxy, workbench.state.value.settings.proxy)
            if (!workbench.state.value.installed) {
                instrumentation.runOnMainSync { workbench.installRuntime() }
                awaitIdle(workbench, 10 * 60 * 1000L)
                assertTrue(workbench.state.value.message, workbench.state.value.installed)
            }
            if (InstrumentationRegistry.getArguments().getString("repair") == "true") {
                instrumentation.runOnMainSync { workbench.repairPackages() }
                awaitIdle(workbench, 5 * 60 * 1000L)
            }
            val tools = DevelopmentTools.required(setOf(ProjectKind.Node, ProjectKind.Python))
            val result = workbench.runtime.execute(DevelopmentTools.doctorScript(tools), null, workbench.state.value.settings)
            val missing = DevelopmentTools.packages(DevelopmentTools.parse(result.output, tools))
            if (missing.isNotEmpty()) {
                instrumentation.runOnMainSync { workbench.installPackages(missing) }
                awaitIdle(workbench, 30 * 60 * 1000L)
            }
            val check = workbench.runtime.execute(DevelopmentTools.doctorScript(tools), null, workbench.state.value.settings)
            val checks = DevelopmentTools.parse(check.output, tools)
            assertTrue(workbench.state.value.message + "\n" + workbench.state.value.log.takeLast(4000) + "\n" + checks, checks.all { tool -> tool.status == ToolStatus.Ready })
        }
    }
    private suspend fun awaitIdle(workbench: Workbench, timeout: Long) {
        withTimeout(timeout) { while (workbench.state.value.busy) delay(200) }
    }
}

package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.ToolStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit integration check for the temporary project imported through the real SAF picker.
 * Pass its exact UUID as -e projectId. Does not create a preloaded application project.
 */
class ProjectDependencyDeviceTest {
    @Test fun installAndUseRootDependencies() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("projectId") ?: error("Pass the imported fixture UUID")
        val workbench = (instrumentation.targetContext.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            val project = workbench.state.value.project!!
            assertEquals(id, project.id)
            assertTrue(project.name.startsWith("ZakoCoder-check-"))
            assertTrue(project.kinds.containsAll(setOf(ProjectKind.Node, ProjectKind.Python)))
            instrumentation.runOnMainSync { workbench.doctor() }
            awaitIdle(workbench)
            assertTrue(workbench.state.value.tools.toString(), workbench.state.value.tools.all { it.status == ToolStatus.Ready })
            val plans = workbench.state.value.plans
            assertEquals(2, plans.size)
            plans.forEach { plan ->
                instrumentation.runOnMainSync { workbench.installDependencies(id, plan) }
                awaitIdle(workbench)
                assertTrue(workbench.state.value.message + workbench.state.value.log, workbench.state.value.log.contains("进程退出码：0"))
            }
            val result = workbench.runtime.execute("node_modules/.bin/tsc --noEmit index.ts && .venv/bin/python -c 'from packaging.version import Version; assert Version(\"1.2\") < Version(\"2.0\"); print(\"PROJECT_DEPENDENCIES_OK\")'", id, workbench.state.value.settings)
            assertEquals(result.output, 0, result.exitCode)
            assertTrue(result.output, result.output.contains("PROJECT_DEPENDENCIES_OK"))
        }
    }
    private suspend fun awaitIdle(workbench: Workbench) {
        withTimeout(5 * 60 * 1000L) { while (workbench.state.value.busy || workbench.state.value.checkingTools) delay(100) }
    }
}

package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.ToolStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run on an explicitly selected ARM64 device. Creates and removes only its own UUID project. */
class RuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as ZakoApplication
    @Test fun guestExecutionCwdUnicodeExitCancellationAndPty() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val workbench = application.workbench
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            assertTrue("Install the runtime using the app before running this test", workbench.state.value.installed)
            val project = withContext(Dispatchers.IO) { workbench.projects.create("device-check-${System.currentTimeMillis()}") }
            var terminalId: String? = null
            try {
                val settings = workbench.state.value.settings
                val result = workbench.runtime.execute("printf '中文终端\\n'; pwd; printf 'real file\\n' > proof.txt; exit 7", project.id, settings)
                assertEquals(result.output, 7, result.exitCode)
                assertTrue(result.output, result.output.contains("中文终端"))
                assertTrue(result.output, result.output.contains("/workspace/${project.id}"))
                withContext(Dispatchers.IO) { assertEquals("real file\n", File(workbench.projects.directory(project.id), "proof.txt").readText()) }
                android.util.Log.i("RuntimeCheck", "execution passed")
                val child = File(workbench.projects.directory(project.id), "child.pid")
                val longCommand = launch(Dispatchers.IO) { workbench.runtime.execute("sleep 600 & echo ${'$'}! > child.pid; wait", project.id, settings) }
                withTimeout(10000) { while (!withContext(Dispatchers.IO) { child.exists() }) delay(50) }
                android.util.Log.i("RuntimeCheck", "child started")
                val childPid = withContext(Dispatchers.IO) { child.readText().trim().toInt() }
                withContext(Dispatchers.IO) { longCommand.cancelAndJoin() }
                withTimeout(10000) { while (withContext(Dispatchers.IO) { File("/proc/$childPid").exists() }) delay(100) }
                android.util.Log.i("RuntimeCheck", "cancellation passed")
                val prepared = workbench.runtime.command(project.id, settings, null, projectName = project.name)
                instrumentation.runOnMainSync {
                    val item = workbench.terminal.create(project.id, project.name, prepared)
                    terminalId = item.id
                    val terminalView = workbench.terminal.view(instrumentation.targetContext, item, settings.terminalFontSp)
                    terminalView.layout(0, 0, 800, 480)
                    assertSame(item.session, terminalView.currentSession)
                    workbench.terminal.detach(terminalView)
                    item.session.updateSize(80, 24, 10, 20)
                    item.session.write("printf 'PTY_中文_OK\\n'; printf 'pty-file' > pty.txt\n")
                }
                withTimeout(15000) { while (!withContext(Dispatchers.IO) { File(workbench.projects.directory(project.id), "pty.txt").exists() }) delay(100) }
                withTimeout(15000) {
                    var output = ""
                    while (!output.contains("PTY_中文_OK")) {
                        instrumentation.runOnMainSync { output = workbench.terminal.sessions.single { it.id == terminalId }.session.emulator.screen.transcriptText }
                        delay(100)
                    }
                }
                val originalPid = workbench.terminal.sessions.single { it.id == terminalId }.session.pid
                scenario.recreate()
                assertEquals(originalPid, workbench.terminal.sessions.single { it.id == terminalId }.session.pid)
                instrumentation.runOnMainSync {
                    workbench.terminal.sessions.single { it.id == terminalId }.session.write("sleep 600 & echo ${'$'}! > pty-child.pid\n")
                }
                val ptyChild = File(workbench.projects.directory(project.id), "pty-child.pid")
                withTimeout(10000) { while (!withContext(Dispatchers.IO) { ptyChild.exists() && ptyChild.length() > 0 }) delay(50) }
                val ptyChildPid = withContext(Dispatchers.IO) { ptyChild.readText().trim().toInt() }
                withContext(Dispatchers.Main) { workbench.terminal.close(terminalId!!) }
                withTimeout(10000) { while (withContext(Dispatchers.IO) { File("/proc/$ptyChildPid").exists() }) delay(100) }
                terminalId = null
            } finally {
                terminalId?.let { id -> withContext(NonCancellable + Dispatchers.Main) { workbench.terminal.close(id) } }
                withContext(NonCancellable + Dispatchers.IO) { workbench.projects.directory(project.id).deleteRecursively() }
            }
        }
    }

    @Test fun installedProjectToolsActuallyExecute() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workbench = application.workbench
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            assertTrue(workbench.state.value.installed)
            val tools = DevelopmentTools.required(setOf(ProjectKind.Python, ProjectKind.Node))
            val result = workbench.runtime.execute(DevelopmentTools.doctorScript(tools), null, workbench.state.value.settings)
            assertEquals(result.output, 0, result.exitCode)
            val checks = DevelopmentTools.parse(result.output, tools)
            assertTrue(checks.toString(), checks.all { check -> check.status == ToolStatus.Ready })
            android.util.Log.i("RuntimeCheck", checks.joinToString("\n"))
            val execute = workbench.runtime.execute("python3 -c 'print(6 * 7)'; node -e 'console.log(21 * 2)'", null, workbench.state.value.settings)
            assertEquals(execute.output, 0, execute.exitCode)
            assertEquals(execute.output, 2, execute.output.lineSequence().count { line -> line == "42" })
        }
    }
    @Test fun rapidTerminalStartStopKeepsApplicationAlive() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workbench = application.workbench
            withTimeout(10000) { while (workbench.state.value.loading) delay(50) }
            assertNotNull("Select the imported fixture before this check", workbench.state.value.project)
            repeat(3) {
                instrumentation.runOnMainSync { workbench.startTerminal() }
                withTimeout(10000) { while (workbench.state.value.busy) delay(20) }
                val item = workbench.terminal.sessions.last()
                assertTrue("A visible session must already have its own positive PID", item.session.pid > 0)
                instrumentation.runOnMainSync { workbench.closeTerminal(item.id) }
                withTimeout(10000) { while (workbench.terminal.sessions.any { it.id == item.id }) delay(20) }
            }
            // Covers the system's delayed foreground-service start timeout as well as immediate close.
            delay(12000)
            assertTrue(workbench.terminal.sessions.isEmpty())
        }
    }
}

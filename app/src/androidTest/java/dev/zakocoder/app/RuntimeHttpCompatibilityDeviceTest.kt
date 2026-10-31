package dev.zakocoder.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Real guest Python server in the app's managed PTY; no public network or model request. */
class RuntimeHttpCompatibilityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(25_000) {
        while (!condition()) delay(75)
    }

    @Test(timeout = 120_000) fun loopbackServiceSurvivesStopResumeAndClosesWithOwnedTerminal(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val workbench = (context.applicationContext as ZakoApplication).workbench
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            assertTrue("Explicitly provision Ubuntu before this test", workbench.state.value.installed)
            val python = workbench.runtime.execute("/usr/bin/python3 --version", null, workbench.state.value.settings)
            assertEquals("Explicitly install Python before this test; it never installs dependencies: ${python.output}", 0, python.exitCode)
            assertTrue("Need one available managed terminal slot", workbench.state.value.sessions.size < 4)
            val previous = workbench.state.value.selectedId
            val run = UUID.randomUUID().toString()
            var projectId: String? = null
            var terminalId: String? = null
            var ownedSession: com.termux.terminal.TerminalSession? = null
            val port = withContext(Dispatchers.IO) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            }
            val content = "COMPATIBILITY_HTTP_$run"
            suspend fun page() = withContext(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 700)
                    socket.soTimeout = 1_500
                    socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            try {
                withContext(Dispatchers.Main) { workbench.create("compatibility-http-$run") }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == "compatibility-http-$run" }
                val id = requireNotNull(workbench.state.value.selectedId); projectId = id
                withContext(Dispatchers.IO) { File(workbench.projects.directory(id), "index.html").writeText(content) }
                withContext(Dispatchers.Main) { workbench.startTerminal(id) }
                await { !workbench.state.value.busy && workbench.state.value.sessions.any { it.projectId == id } }
                val item = workbench.state.value.sessions.single { it.projectId == id }; terminalId = item.id
                ownedSession = item.session
                withContext(Dispatchers.Main) {
                    item.session.write("exec /usr/bin/python3 -u -m http.server $port --bind 127.0.0.1\n")
                }
                await { try { page().contains(content) } catch (_: IOException) { false } }
                assertTrue(page().contains("200 OK"))
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue("Managed session must survive the stopped Activity", item.session.isRunning)
                assertTrue("Real guest response must remain reachable while Activity is stopped", page().contains(content))
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertTrue(page().contains(content))
                withContext(Dispatchers.Main) { workbench.closeTerminal(item.id) }
                await { workbench.state.value.sessions.none { it.id == item.id } && !item.session.isRunning }
                await { try { page(); false } catch (_: IOException) { true } }
                terminalId = null
                println("COMPATIBILITY_HTTP_OK port=$port background_response=true closed_with_terminal=true")
            } finally {
                withContext(NonCancellable) {
                    try {
                        terminalId?.let { id ->
                            withContext(Dispatchers.Main) { workbench.closeTerminal(id) }
                            await { workbench.state.value.sessions.none { it.id == id } && ownedSession?.isRunning != true }
                        }
                        projectId?.let { id ->
                            withContext(Dispatchers.Main) { workbench.removeProject(id) }
                            await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } }
                        }
                    } finally {
                        previous?.let { id -> withContext(Dispatchers.Main) { workbench.select(id) }; await { !workbench.state.value.busy } }
                    }
                }
            }
        }
    }
}

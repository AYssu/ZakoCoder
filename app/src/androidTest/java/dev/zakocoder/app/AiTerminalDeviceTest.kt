package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Local TLS model fixture + real phone PTY/network. Never reads a user's model configuration. */
class AiTerminalDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(block: suspend () -> Boolean) = withTimeout(20000) { while (!block()) delay(50) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    @Test(timeout = 120000) fun approvedAiServerIsVisibleSurvivesTurnAndSupportsReadInterruptClose() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            assertTrue(w.state.value.installed)
            assertTrue(w.terminal.sessions.size < 4)
            val previous = w.state.value.selectedId
            var project: String? = null
            var ai: AiController? = null
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            suspend fun page(): String = withContext(Dispatchers.IO) {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500); s.soTimeout = 1000
                    s.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                    s.getInputStream().bufferedReader().readText()
                }
            }
            try {
                val name = "ai-terminal-check-${UUID.randomUUID()}"
                main { w.create(name) }
                await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; project = id
                withContext(Dispatchers.IO) { File(w.projects.directory(id), "index.html").writeText("<h1>AI_TERMINAL_REAL_HTTP</h1>") }
                AiDeviceTest.Fixture(context).use { f ->
                    val wrapper = object : ContextWrapper(context) {
                        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
                        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
                    }
                    f.enqueue(calls = listOf(AiCall("start", "start_terminal", JSONObject().put("command", "python3 -u -m http.server $port --bind 127.0.0.1").put("reason", "Start project preview").toString())))
                    f.enqueue("服务已交给可见终端；需要检查 URL。")
                    main {
                        ai = AiController(wrapper, w.projects::directory, { _, _ -> false }, { _, _ -> error("No finite command expected") }, {}, {},
                            f.transport, f.config, permissionStore = AiPermissionStore(wrapper), history = f.history, terminalTool = w::executeAiTerminal)
                        ai!!.select(id)
                    }
                    val controller = ai!!
                    await { controller.state.value.configured && !controller.state.value.permissionsLoading && !controller.state.value.skillsLoading }
                    main { assertTrue(controller.send("帮我启动当前项目页面服务")) }
                    await { controller.state.value.approval != null }
                    assertTrue(w.terminal.sessions.none { it.projectId == id })
                    assertTrue(controller.state.value.approval!!.detail.contains("/workspace/$id"))
                    main { controller.approve(true) }
                    await { !controller.state.value.active }
                    assertEquals("", controller.state.value.error)
                    val terminal = w.terminal.sessions.single { it.projectId == id }
                    assertTrue(controller.state.value.events.single().output.contains(terminal.id))
                    await { try { page().contains("AI_TERMINAL_REAL_HTTP") } catch (_: java.io.IOException) { false } }
                    scenario.recreate(); delay(300)
                    assertTrue(page().contains("200 OK"))
                    assertTrue(w.state.value.sessions.any { it.id == terminal.id })
                    val listed = JSONObject(w.executeAiTerminal(id, AiTool.ListTerminals, JSONObject()))
                    assertEquals(terminal.id, listed.getJSONArray("terminals").getJSONObject(0).getString("terminal_id"))
                    try {
                        w.executeAiTerminal(w.state.value.projects.firstOrNull { it.id != id }?.id ?: UUID.randomUUID().toString(), AiTool.ReadTerminal, JSONObject().put("terminal_id", terminal.id))
                        fail("Cross-project terminal access must fail")
                    } catch (_: IllegalStateException) { }
                    f.enqueue(calls = listOf(AiCall("read", "read_terminal", JSONObject().put("terminal_id", terminal.id).toString())))
                    f.enqueue("已读取真实服务输出。")
                    main { assertTrue(controller.send("看看终端输出")) }
                    await { controller.state.value.approval != null }
                    main { controller.approve(true) }
                    await { !controller.state.value.active }
                    assertTrue(controller.state.value.events.last().output.contains("HTTP"))
                    f.enqueue(calls = listOf(AiCall("interrupt", "send_terminal_input", JSONObject().put("terminal_id", terminal.id).put("key", "ctrl_c").toString())))
                    f.enqueue("已发送停止信号。")
                    main { assertTrue(controller.send("停止这个服务")) }
                    await { controller.state.value.approval != null }
                    assertTrue(page().contains("200 OK"))
                    main { controller.approve(true) }
                    await { !controller.state.value.active && !terminal.session.isRunning }
                    await { try { page(); false } catch (_: java.io.IOException) { true } }
                    f.enqueue(calls = listOf(AiCall("close", "close_terminal", JSONObject().put("terminal_id", terminal.id).toString())))
                    f.enqueue("终端已关闭。")
                    main { assertTrue(controller.send("关闭这个终端")) }
                    await { controller.state.value.approval != null }
                    main { controller.approve(false) }
                    await { !controller.state.value.active }
                    assertTrue(w.terminal.sessions.any { it.id == terminal.id })
                    assertEquals(AiToolStatus.Denied, controller.state.value.events.last().status)
                    f.enqueue(calls = listOf(AiCall("close-approved", "close_terminal", JSONObject().put("terminal_id", terminal.id).toString())))
                    f.enqueue("已确认关闭。")
                    main { assertTrue(controller.send("确认关闭终端")) }
                    await { controller.state.value.approval != null }
                    main { controller.approve(true) }
                    await { !controller.state.value.active }
                    assertTrue(w.terminal.sessions.none { it.id == terminal.id })
                    val typed = JSONObject(w.executeAiTerminal(id, AiTool.StartTerminal,
                        JSONObject().put("command", "IFS= read -r value; printf 'TYPED:%s\\n' \"${'$'}value\"")))
                    val typedId = typed.getString("terminal_id")
                    w.executeAiTerminal(id, AiTool.SendTerminalInput, JSONObject().put("terminal_id", typedId).put("text", "literal test").put("key", "enter"))
                    await { w.terminal.sessions.single { it.id == typedId }.session.isRunning.not() }
                    val output = JSONObject(w.executeAiTerminal(id, AiTool.ReadTerminal, JSONObject().put("terminal_id", typedId)))
                    assertTrue(output.getString("output").contains("TYPED:literal test"))
                    assertEquals(0, output.getInt("exit_code"))
                    w.executeAiTerminal(id, AiTool.CloseTerminal, JSONObject().put("terminal_id", typedId))
                    val originalIds = w.terminal.sessions.map { it.id }.toSet()
                    try {
                        repeat(4 - originalIds.size) { w.executeAiTerminal(id, AiTool.StartTerminal, JSONObject().put("command", "sleep 30")) }
                        assertEquals(4, w.terminal.sessions.size)
                        try { w.executeAiTerminal(id, AiTool.StartTerminal, JSONObject().put("command", "echo must-not-start")); fail("Fifth terminal must fail") }
                        catch (expected: AiTerminalException) { assertEquals(context.getString(R.string.terminal_limit), expected.message) }
                        assertEquals(4, w.terminal.sessions.size)
                    } finally {
                        withContext(Dispatchers.Main) { w.terminal.sessions.filter { it.id !in originalIds && it.projectId == id }.toList().forEach { w.terminal.close(it.id) } }
                    }
                    assertEquals(AiPermission.Ask, AiPolicy().permission(AiTool.ReadTerminal))
                    assertEquals(AiPermission.Ask, AiPolicy().permission(AiTool.StartTerminal))
                    assertEquals(AiTool.entries.size, AiController.schemas().length())
                }
            } finally {
                main { ai?.stop() }
                withContext(Dispatchers.Main) { w.terminal.sessions.filter { it.projectId == project }.toList().forEach { w.terminal.close(it.id) } }
                project?.let { id -> main { w.removeProject(id) }; await { !w.state.value.busy && w.state.value.projects.none { it.id == id } } }
                if (previous != null) { main { w.select(previous) }; await { !w.state.value.busy && w.state.value.selectedId == previous } }
            }
        }
    }
}

package dev.zakocoder.app

import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import dev.zakocoder.app.ui.ZakoCoderApp
import dev.zakocoder.app.runtime.PrivateFiles
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import java.io.File
import dev.zakocoder.app.settings.TerminalShell

/** Opt-in real device regression. Owns only its UUID fixtures and restores the selected project. */
class TerminalInteractionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ZakoApplication

    @Before fun inspectOwnedPopupWindows() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    /** A real PTY-owned server, accessed through Android loopback, never an AI/host shell. */
    @Test(timeout = 120000) fun pythonHttpServerSurvivesNavigationAndStopsWithTerminal(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val w = app.workbench
            await { !w.state.value.loading && !w.state.value.busy }
            assertTrue(w.state.value.installed)
            assertTrue(w.state.value.sessions.size < 4)
            val previous = w.state.value.selectedId
            var owned: String? = null
            var sessionId: String? = null
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            suspend fun page(): String = withContext(Dispatchers.IO) {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000); socket.soTimeout = 2000
                    socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            try {
                val name = "http-preview-check-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { w.create(name) }
                await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; owned = id
                withContext(Dispatchers.IO) { File(w.projects.directory(id), "index.html").writeText("<!doctype html><meta name='viewport' content='width=device-width'><title>ZakoCoder preview test</title><h1>ZakoCoder HTTP preview works</h1>") }
                instrumentation.runOnMainSync { w.startTerminal(id) }
                await { !w.state.value.busy && w.state.value.sessions.any { it.projectId == id } }
                val item = w.state.value.sessions.single { it.projectId == id }; sessionId = item.id
                // Fixed test command enters the same PTY exposed by the terminal UI.
                instrumentation.runOnMainSync { item.session.write("python3 -m http.server $port --bind 127.0.0.1\n") }
                await("Python HTTP server ready") { try { page().contains("ZakoCoder HTTP preview works") } catch (_: java.io.IOException) { false } }
                println("HTTP_PREVIEW_READY http://127.0.0.1:$port/")
                click("终端"); click("进入终端 ${item.number}")
                awaitView(scenario, item.session.pid)
                click("终端操作"); click("预览网页")
                await { find("http://127.0.0.1:$port/") != null }
                assertTrue(previewButton().isEnabled)
                screenshot("terminal-preview-ready")
                setText("http://external.example:8080/")
                assertFalse(previewButton().isEnabled)
                assertFalse("URL editor must not type into PTY", transcript(scenario).contains("external.example"))
                setText("http://127.0.0.1:$port/")
                assertTrue(previewButton().isEnabled)
                // Explicit opt-in browser interaction; ordinary lifecycle regression stays in-app.
                if (InstrumentationRegistry.getArguments().getString("openPreviewEntry") == "true") {
                    click("在浏览器打开")
                    var stableSince = 0L
                    await("Real system browser rendered fixture without optional prompt") {
                        // A visible page behind an optional overlay is insufficient; decline only this known recommendation prompt.
                        if (find("选择兴趣，获取精准推荐") != null && find("跳过") != null) {
                            click("跳过"); stableSince = 0L; println("HTTP_PREVIEW_BROWSER_OPTIONAL_PROMPT_SKIPPED")
                            false
                        } else if (find("ZakoCoder HTTP preview works") != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (stableSince == 0L) stableSince = now
                            now - stableSince >= 1500
                        } else { stableSince = 0L; false }
                    }
                    screenshot("terminal-preview-browser")
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    delay(300)
                    val returned = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName
                    println("HTTP_PREVIEW_BROWSER_RETURN native_back=$returned")
                    // Browser task behavior varies. MIUI blocks background self-launch, so any fallback is developer shell only.
                    if (!returned) instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.zakocoder.app/.MainActivity").use { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader -> assertTrue(reader.readText().contains("Status: ok")) }
                    }
                    await("Return to preview panel after external browser") { find("预览网页") != null }
                }
                click("关闭")
                click("返回终端列表")
                click("环境")
                assertTrue("Leaving the terminal keeps its server", page().contains("200 OK"))
                scenario.recreate(); delay(500)
                assertTrue("Activity recreation keeps its server", page().contains("ZakoCoder HTTP preview works"))
                if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("previewBrowser") == "true") {
                    withContext(Dispatchers.IO) { File(instrumentation.targetContext.cacheDir, "http-preview-test-port.txt").writeText(port.toString()) }
                    delay(20000)
                }
                withContext(Dispatchers.Main) { w.terminal.close(item.id) }
                sessionId = null
                await("Closing terminal releases HTTP port") { try { page(); false } catch (_: java.io.IOException) { true } }
                println("HTTP_PREVIEW_STOPPED")
            } finally {
                withContext(Dispatchers.IO) { File(instrumentation.targetContext.cacheDir, "http-preview-test-port.txt").delete() }
                sessionId?.let { withContext(Dispatchers.Main) { w.terminal.close(it) } }
                owned?.let { id -> instrumentation.runOnMainSync { w.removeProject(id) }; await { !w.state.value.busy && w.state.value.projects.none { it.id == id } } }
                previous?.let { id -> instrumentation.runOnMainSync { w.select(id) }; await { !w.state.value.busy } }
            }
        }
    }

    @Test(timeout = 120000) fun previewEmptyInvalidAndBrowserFailureKeepTerminalInputIsolated(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val w = app.workbench
            await { !w.state.value.loading && !w.state.value.busy }
            assertTrue(w.state.value.installed); assertTrue(w.state.value.sessions.size < 4)
            val previous = w.state.value.selectedId
            var projectId: String? = null; var terminalId: String? = null
            val launches = java.util.concurrent.atomic.AtomicInteger()
            try {
                // Override browser handling only in this instrumentation composition, never production config.
                scenario.onActivity { activity -> activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                        override fun openUri(uri: String) { launches.incrementAndGet(); throw android.content.ActivityNotFoundException("Fixture missing browser") }
                    }) { ZakoCoderApp(w) }
                } }
                val name = "preview-empty-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { w.create(name) }
                await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; projectId = id
                instrumentation.runOnMainSync { w.startTerminal(id) }
                await { !w.state.value.busy && w.state.value.sessions.any { it.projectId == id } }
                val item = w.state.value.sessions.single { it.projectId == id }; terminalId = item.id
                click("终端"); click("进入终端 ${item.number}"); awaitView(scenario, item.session.pid)
                click("终端操作"); click("预览网页")
                await { find("没有识别到地址。请先启动开发服务，或填写实际的本机地址。") != null }
                assertFalse(previewButton().isEnabled); assertEquals(0, launches.get())
                screenshot("terminal-preview-empty")
                click("本机地址"); setText("http://127.0.0.1:12345/?fixture=INPUT_MUST_NOT_REACH_PTY")
                assertTrue(previewButton().isEnabled)
                assertFalse(transcript(scenario).contains("INPUT_MUST_NOT_REACH_PTY"))
                await("Address keyboard visible") { instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                val imeBounds = android.graphics.Rect(); instrumentation.uiAutomation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }.getBoundsInScreen(imeBounds)
                val buttonBounds = android.graphics.Rect(); previewButton().getBoundsInScreen(buttonBounds)
                assertTrue("Open action must remain above IME: $buttonBounds / $imeBounds", buttonBounds.height() > 0 && buttonBounds.bottom <= imeBounds.top)
                screenshot("terminal-preview-keyboard")
                click("在浏览器打开")
                await { find("无法打开浏览器，请检查是否已安装并允许浏览器运行。地址保留，可稍后重试。") != null }
                assertEquals(1, launches.get()); assertTrue(item.session.isRunning)
                screenshot("terminal-preview-browser-failed")
                click("关闭")
                click("终端操作"); click("预览网页")
                assertFalse("Closing must discard the manual URL", previewButton().isEnabled)
                assertEquals(1, launches.get())
                click("关闭")
                input(scenario, "printf 'focus-restored' > preview-focus.txt\n")
                await { withContext(Dispatchers.IO) { File(w.projects.directory(id), "preview-focus.txt").exists() } }
                assertEquals("focus-restored", withContext(Dispatchers.IO) { File(w.projects.directory(id), "preview-focus.txt").readText() })
            } finally {
                terminalId?.let { withContext(Dispatchers.Main) { w.terminal.close(it) } }
                projectId?.let { id -> instrumentation.runOnMainSync { w.removeProject(id) }; await { !w.state.value.busy && w.state.value.projects.none { it.id == id } } }
                previous?.let { id -> instrumentation.runOnMainSync { w.select(id) }; await { !w.state.value.busy } }
                scenario.onActivity { it.setContent { ZakoCoderApp(w) } }
            }
        }
    }

    @Test(timeout = 120000) fun directImeKeysAndSessionSwitcherPreserveIndependentSessions(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val workbench = app.workbench
            await { !workbench.state.value.loading }
            assertTrue("Install the runtime before running this opt-in test", workbench.state.value.installed)
            assertTrue("Leave two free terminal slots for this test", workbench.state.value.sessions.size <= 2)
            val previousSelection = workbench.state.value.selectedId
            val ownedProjects = mutableListOf<String>()
            val ownedSessions = mutableListOf<String>()
            try {
                val stamp = System.currentTimeMillis()
                val projectName = "terminal-check-$stamp"
                instrumentation.runOnMainSync { workbench.create(projectName) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == projectName }
                val project = workbench.state.value.project!!
                ownedProjects += project.id
                val root = withContext(Dispatchers.IO) { workbench.projects.directory(project.id).also {
                    File(it, "说明.txt").writeText("终端直接输入验证。\n")
                } }
                val longName = "项目库长名称验证-多语言开发项目-保留完整名称-$stamp"
                instrumentation.runOnMainSync { workbench.create(longName) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == longName }
                ownedProjects += workbench.state.value.project!!.id
                screenshot("library-long-name")
                instrumentation.runOnMainSync { workbench.select(project.id) }
                await { !workbench.state.value.busy && workbench.state.value.selectedId == project.id }
                screenshot("library-projects")
                click("浏览文件")
                click("说明.txt")
                await { workbench.editor.state.value.document?.content?.toString() == "终端直接输入验证。\n" }
                screenshot("file-editor")
                click("返回文件列表")
                click("返回项目库")
                val beforeCount = workbench.state.value.sessions.size
                click("打开终端")
                assertNull("Navigation must show native cards, not a PTY", terminal(scenario))
                assertEquals(beforeCount, workbench.state.value.sessions.size)
                screenshot("terminal-library-empty")
                click("新建终端")
                screenshot("terminal-create-sheet")
                click("创建终端")
                await { !workbench.state.value.busy && workbench.state.value.sessions.any { it.projectId == project.id } }
                val first = workbench.state.value.sessions.last { it.projectId == project.id }
                ownedSessions += first.id
                val firstPid = first.session.pid
                assertNull("Creating a card must not automatically enter it", terminal(scenario))
                screenshot("terminal-library-card")
                click("进入终端 ${first.number}")
                awaitView(scenario, firstPid)
                input(scenario, "printf '中文输入' > ime-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "ime-proof.txt").exists() } }
                assertEquals("中文输入", withContext(Dispatchers.IO) { File(root, "ime-proof.txt").readText() })
                input(scenario, "THIS_MUST_NOT_RUN")
                click("Ctrl")
                assertTrue(workbench.terminal.modifiers.value.control)
                input(scenario, "u")
                assertFalse("Ctrl is consumed after one input", workbench.terminal.modifiers.value.control)
                input(scenario, "printf CONTROL_OK > control-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "control-proof.txt").exists() } }
                assertEquals("CONTROL_OK", withContext(Dispatchers.IO) { File(root, "control-proof.txt").readText() })
                input(scenario, "printf 'alpha beta'")
                click("Alt")
                input(scenario, "b")
                assertFalse(workbench.terminal.modifiers.value.alt)
                input(scenario, "new-")
                click("End")
                input(scenario, " > alt-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "alt-proof.txt").exists() } }
                assertEquals("alpha new-beta", withContext(Dispatchers.IO) { File(root, "alt-proof.txt").readText() })
                input(scenario, "printf DELETE_OKX")
                val editingView = terminal(scenario)!!
                instrumentation.runOnMainSync { editingView.onCreateInputConnection(EditorInfo()).deleteSurroundingText(1, 0) }
                input(scenario, " > deletion-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "deletion-proof.txt").exists() } }
                assertEquals("DELETE_OK", withContext(Dispatchers.IO) { File(root, "deletion-proof.txt").readText() })
                input(scenario, "sleep 600 & echo ${'$'}! > interrupt-child.pid; fg\n")
                await { withContext(Dispatchers.IO) { File(root, "interrupt-child.pid").length() > 0 } }
                val interruptedPid = withContext(Dispatchers.IO) { File(root, "interrupt-child.pid").readText().trim().toInt() }
                click("Ctrl")
                click("Alt")
                click("Ctrl+C")
                assertFalse(workbench.terminal.modifiers.value.control)
                assertFalse(workbench.terminal.modifiers.value.alt)
                await { withContext(Dispatchers.IO) { !File("/proc/$interruptedPid").exists() } }
                input(scenario, "printf INTERRUPT_OK > interrupt-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "interrupt-proof.txt").exists() } }
                assertEquals("INTERRUPT_OK", withContext(Dispatchers.IO) { File(root, "interrupt-proof.txt").readText() })
                input(scenario, "printf EDIT_12")
                click("向左移动光标")
                input(scenario, "3\n")
                await { transcript(scenario).contains("EDIT_132") }
                input(scenario, "printf COMPLETION_OK > completion-target.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "completion-target.txt").exists() } }
                input(scenario, "cat completion-tar")
                click("Tab")
                input(scenario, "\n")
                await { transcript(scenario).contains("COMPLETION_OK") }
                input(scenario, "mkdir session-one; cd session-one\n")
                screenshot("terminal-direct")
                click("键盘")
                delay(400)
                screenshot("terminal-ime")
                val originalView = terminal(scenario)!!
                val observedHeights = CopyOnWriteArraySet<Int>()
                val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { observedHeights += originalView.height }
                scenario.onActivity { originalView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener); observedHeights += originalView.height }
                click("终端操作")
                click("新建终端")
                await { !workbench.state.value.busy && workbench.state.value.sessions.count { it.projectId == project.id } == 2 }
                val second = workbench.state.value.sessions.last { it.projectId == project.id }
                ownedSessions += second.id
                val secondPid = second.session.pid
                awaitView(scenario, secondPid)
                delay(350)
                scenario.onActivity { originalView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
                assertSame("New session must retain the native view and IME token", originalView, terminal(scenario))
                assertEquals("New session must not resize the terminal viewport", 1, observedHeights.size)
                assertTrue(observedHeights.first() > 0)
                input(scenario, "pwd > second-cwd.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "second-cwd.txt").exists() } }
                assertEquals("/workspace/${project.id}", withContext(Dispatchers.IO) { File(root, "second-cwd.txt").readText().trim() })
                click("切换终端")
                screenshot("terminal-switcher")
                click("终端 ${first.number}")
                awaitView(scenario, firstPid)
                assertSame("Switching session retains the native view", originalView, terminal(scenario))
                assertEquals(firstPid, first.session.pid)
                input(scenario, "pwd > ../first-cwd.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "first-cwd.txt").exists() } }
                assertEquals("/workspace/${project.id}/session-one", withContext(Dispatchers.IO) { File(root, "first-cwd.txt").readText().trim() })
                // ZLE may explicitly wrap a command echo between CJK characters. Verify
                // persistence with a short real output line; IME bytes were checked above.
                input(scenario, "printf '\\nRECREATE_BUFFER_OK\\n'\n")
                await { transcript(scenario).contains("RECREATE_BUFFER_OK") }
                scenario.recreate()
                awaitView(scenario, firstPid)
                assertTrue(transcript(scenario).contains("RECREATE_BUFFER_OK"))
                click("切换终端")
                longClick("终端 ${first.number}")
                click("关闭会话")
                await { workbench.state.value.sessions.none { it.id == first.id } }
                assertEquals(secondPid, second.session.pid)
                click("终端 ${second.number}")
                awaitView(scenario, secondPid)
                input(scenario, "printf SURVIVED > survivor.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "survivor.txt").exists() } }
                assertEquals("SURVIVED", withContext(Dispatchers.IO) { File(root, "survivor.txt").readText() })
                click("返回终端列表")
                await { find("进入终端 ${second.number}") != null }
                assertNull(terminal(scenario))
                scenario.onActivity {
                    assertFalse(androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
                }
                assertEquals(secondPid, second.session.pid)
                screenshot("terminal-library-running")
                assertFalse(find("进入终端 ${second.number}")?.text?.toString() == "进入终端 ${second.number}")
                assertNull("Cards must not expose a close button", find("关闭终端 ${second.number}"))
                await { workbench.terminal.previews.value[second.id]?.contains("SURVIVED") == true }
                assertTrue(second.session.isRunning)
                click("项目库")
                click("打开终端")
                assertNull(terminal(scenario))
                click("进入终端 ${second.number}")
                awaitView(scenario, secondPid)
                assertEquals(1, workbench.state.value.sessions.count { it.projectId == project.id })
                input(scenario, "printf RETURNED > return-proof.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "return-proof.txt").exists() } }
                assertEquals("RETURNED", withContext(Dispatchers.IO) { File(root, "return-proof.txt").readText() })
            } catch (failure: Throwable) {
                screenshot("failure")
                throw failure
            } finally {
                withContext(NonCancellable) {
                    ownedSessions.forEach { id -> withContext(Dispatchers.Main) { workbench.terminal.close(id) } }
                    ownedProjects.reversed().forEach { id ->
                        if (workbench.state.value.projects.any { it.id == id }) {
                            await { !workbench.state.value.busy }
                            instrumentation.runOnMainSync { workbench.removeProject(id) }
                            await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } }
                        }
                    }
                    previousSelection?.takeIf { id -> workbench.state.value.projects.any { it.id == id } }?.let { id ->
                        instrumentation.runOnMainSync { workbench.select(id) }
                        await { !workbench.state.value.busy && workbench.state.value.selectedId == id }
                    }
                }
            }
        }
    }

    /** Network install and keeping Zsh selected require explicit instrumentation arguments. */
    @Test(timeout = 240000) fun zshSelectionInstallAndDirectInput(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val workbench = app.workbench
            await { !workbench.state.value.loading && !workbench.state.value.busy && !workbench.state.value.checkingTools }
            assertTrue(workbench.state.value.installed)
            val original = workbench.state.value.settings
            val previousSelection = workbench.state.value.selectedId
            val ownedSessions = mutableListOf<String>()
            var projectId: String? = null
            var completed = false
            val arguments = InstrumentationRegistry.getArguments()
            try {
                // Exercise the initial font size independently of a device's saved choice.
                instrumentation.runOnMainSync { workbench.saveSettings(original.copy(terminalFontSp = dev.zakocoder.app.settings.AppSettings().terminalFontSp)) }
                await { !workbench.state.value.busy }
                click("设置")
                click("默认 Shell")
                if (workbench.state.value.bundleTools.none { it.id == "zsh" && it.status == dev.zakocoder.app.runtime.ToolStatus.Ready }) {
                    org.junit.Assume.assumeTrue("Pass installZsh=true to opt into downloading the Zsh package", arguments.getString("installZsh") == "true")
                    click("安装 Zsh")
                    await { find(instrumentation.targetContext.getString(R.string.package_confirmation, "zsh")) != null }
                    screenshot("zsh-install-confirmation")
                    click(instrumentation.targetContext.getString(R.string.run))
                    withTimeout(180000) { while (workbench.state.value.busy || workbench.state.value.checkingTools) delay(100) }
                    assertTrue(workbench.state.value.log, workbench.state.value.bundleTools.any { it.id == "zsh" && it.status == dev.zakocoder.app.runtime.ToolStatus.Ready })
                    click("默认 Shell")
                }
                click("Zsh")
                click("保存设置")
                await { !workbench.state.value.busy && workbench.state.value.settings.terminalShell == TerminalShell.Zsh }
                assertEquals(TerminalShell.Zsh, withContext(Dispatchers.IO) { dev.zakocoder.app.settings.SettingsRepository(instrumentation.targetContext).load().terminalShell })
                screenshot("settings-zsh")
                val name = "zsh-check-${System.currentTimeMillis()}"
                instrumentation.runOnMainSync { workbench.create(name) }
                await { !workbench.state.value.busy && workbench.state.value.project?.name == name }
                val project = workbench.state.value.project!!
                projectId = project.id
                val root = withContext(Dispatchers.IO) { workbench.projects.directory(project.id) }
                click("项目库")
                click("终端")
                click("新建终端")
                click("创建终端")
                await { !workbench.state.value.busy && workbench.state.value.sessions.any { it.projectId == project.id } }
                val zsh = workbench.state.value.sessions.last { it.projectId == project.id }
                ownedSessions += zsh.id
                click("进入终端 ${zsh.number}")
                awaitView(scenario, zsh.session.pid)
                input(scenario, "printf '%s' \"${'$'}ZSH_VERSION\" > zsh-version.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "zsh-version.txt").exists() } }
                assertTrue(withContext(Dispatchers.IO) { File(root, "zsh-version.txt").readText().trim().matches(Regex("[0-9].*")) })
                input(scenario, "printf '中文输入' > zsh-ime.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "zsh-ime.txt").exists() } }
                assertEquals("中文输入", withContext(Dispatchers.IO) { File(root, "zsh-ime.txt").readText() })
                input(scenario, "if (( ${'$'}+functions[compdef] )); then printf ready > completion.txt; fi\n")
                await { withContext(Dispatchers.IO) { File(root, "completion.txt").exists() } }
                await("Zsh project prompt") { transcript(scenario).contains(name) }
                screenshot("terminal-zsh")
                val bashResult = workbench.runtime.execute("test -n \"${'$'}BASH_VERSION\" && test -z \"${'$'}{ZSH_VERSION-}\" && printf BASH_SCRIPT_OK", project.id, workbench.state.value.settings)
                assertEquals(0, bashResult.exitCode)
                assertEquals("BASH_SCRIPT_OK", bashResult.output.trim())
                val revision = workbench.state.value.toolCheckRevision
                instrumentation.runOnMainSync { workbench.saveSettings(original.copy(terminalShell = TerminalShell.Bash)) }
                await { !workbench.state.value.busy && workbench.state.value.settings.terminalShell == TerminalShell.Bash }
                assertEquals(revision, workbench.state.value.toolCheckRevision)
                assertTrue(zsh.session.isRunning)
                input(scenario, "printf '%s' \"${'$'}ZSH_VERSION\" > still-zsh.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "still-zsh.txt").let { it.exists() && it.readText().isNotBlank() } } }
                assertTrue(withContext(Dispatchers.IO) { File(root, "still-zsh.txt").readText().isNotBlank() })
                click("返回终端列表")
                click("新建终端")
                click("创建终端")
                await { !workbench.state.value.busy && workbench.state.value.sessions.count { it.projectId == project.id } == 2 }
                val bash = workbench.state.value.sessions.last { it.projectId == project.id }
                ownedSessions += bash.id
                click("进入终端 ${bash.number}")
                awaitView(scenario, bash.session.pid)
                input(scenario, "printf '%s' \"${'$'}BASH_VERSION\" > bash-version.txt\n")
                await { withContext(Dispatchers.IO) { File(root, "bash-version.txt").let { it.exists() && it.readText().isNotBlank() } } }
                assertTrue(withContext(Dispatchers.IO) { File(root, "bash-version.txt").readText().isNotBlank() })
                completed = true
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.state.value.busy }
                    ownedSessions.forEach { id -> withContext(Dispatchers.Main) { workbench.terminal.close(id) } }
                    projectId?.let { id ->
                        instrumentation.runOnMainSync { workbench.removeProject(id) }
                        await { !workbench.state.value.busy && workbench.state.value.projects.none { it.id == id } }
                    }
                    previousSelection?.let { id ->
                        instrumentation.runOnMainSync { workbench.select(id) }
                        await { !workbench.state.value.busy && workbench.state.value.selectedId == id }
                    }
                    val restored = if (completed && arguments.getString("keepZsh") == "true") original.copy(terminalShell = TerminalShell.Zsh) else original
                    instrumentation.runOnMainSync { workbench.saveSettings(restored) }
                    await { !workbench.state.value.busy && workbench.state.value.settings == restored }
                }
            }
        }
    }

    @Test(timeout = 150000) fun environmentAndSettingsUseGroupedEditorsAndRealStorage(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val workbench = app.workbench
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            val original = workbench.state.value.settings
            if (workbench.state.value.installed) {
                await { !workbench.state.value.checkingTools && workbench.state.value.toolCheckRevision > 0 }
                assertEquals(dev.zakocoder.app.runtime.DevelopmentTools.catalog.size, workbench.state.value.bundleTools.size)
                val revision = workbench.state.value.toolCheckRevision
                instrumentation.runOnMainSync {
                    workbench.doctor()
                    assertTrue(workbench.state.value.checkingTools)
                    assertFalse(workbench.state.value.busy)
                    workbench.doctor()
                    workbench.doctor()
                }
                await { !workbench.state.value.checkingTools && workbench.state.value.toolCheckRevision >= revision + 2 }
                assertEquals(revision + 2, workbench.state.value.toolCheckRevision)
            }
            try {
                screenshot("library-projects-final")
                click("环境")
                await { workbench.state.value.usedBytes != null }
                assertTrue(workbench.state.value.usedBytes!! > 0)
                screenshot("environment-overview")
                if (workbench.state.value.installed && !workbench.state.value.checkingTools && workbench.state.value.bundleTools.isEmpty()) {
                    click("管理")
                    click("选择 Git 工具包")
                    click("安装所选工具包（1）")
                    await { find(instrumentation.targetContext.getString(R.string.package_confirmation, "ca-certificates, curl, git")) != null }
                    screenshot("tool-bundles-confirmation")
                    click("取消")
                    assertFalse(workbench.state.value.busy)
                    assertTrue(workbench.state.value.bundleTools.isEmpty())
                }
                click("查看 Git 工具包")
                screenshot("tool-detail-sheet")
                if (workbench.state.value.project != null && find("在终端中使用")?.isEnabled == true) {
                    val sessionCount = workbench.state.value.sessions.size
                    click("在终端中使用")
                    await { find("新建终端") != null }
                    assertEquals("Tool details navigation must not create a PTY", sessionCount, workbench.state.value.sessions.size)
                    assertNull(terminal(scenario))
                    click("环境")
                } else click("关闭")
                val missingBundle = dev.zakocoder.app.runtime.ToolBundles.catalog.firstOrNull {
                    !it.manual && dev.zakocoder.app.runtime.ToolBundles.status(it, workbench.state.value.bundleTools) != dev.zakocoder.app.runtime.BundleStatus.Ready
                }
                if (missingBundle != null) {
                    click("管理")
                    click(instrumentation.targetContext.getString(R.string.select_bundle, instrumentation.targetContext.getString(missingBundle.title)))
                    click("安装所选工具包（1）")
                    val packages = dev.zakocoder.app.runtime.ToolBundles.packages(setOf(missingBundle.id), workbench.state.value.bundleTools)
                    await { find(instrumentation.targetContext.getString(R.string.package_confirmation, packages.joinToString(", "))) != null }
                    screenshot("tool-bundles-confirmation")
                    click("取消")
                    click("完成")
                    assertFalse(workbench.state.value.busy)
                }
                click("环境维护")
                await { find("关闭") != null }
                screenshot("environment-maintenance")
                click("关闭")
                if (workbench.state.value.project != null) {
                    click(workbench.state.value.project!!.name)
                    await { find("配置项目类型") != null }
                    screenshot("environment-types")
                    click("取消")
                    if (workbench.state.value.installed) {
                        await { !workbench.state.value.checkingTools }
                        val revision = workbench.state.value.toolCheckRevision
                        click("检测开发工具")
                        assertFalse(workbench.state.value.busy)
                        await { !workbench.state.value.checkingTools && workbench.state.value.toolCheckRevision > revision }
                        screenshot("environment-tools")
                        assertEquals(dev.zakocoder.app.runtime.DevelopmentTools.catalog.size, workbench.state.value.bundleTools.size)
                    }
                }
                click("设置")
                await { find("字体大小") != null }
                screenshot("settings-overview")
                val revisionBeforeFont = workbench.state.value.toolCheckRevision
                click(if (original.terminalFontSp < 28) "增大终端字号" else "减小终端字号")
                await { !workbench.state.value.busy && workbench.state.value.settings.terminalFontSp != original.terminalFontSp }
                screenshot("settings-font")
                click(if (original.terminalFontSp < 28) "减小终端字号" else "增大终端字号")
                await { !workbench.state.value.busy && workbench.state.value.settings == original }
                assertEquals(revisionBeforeFont, workbench.state.value.toolCheckRevision)
                click("DNS")
                setText("999.0.0.1")
                click("保存设置")
                await { find(instrumentation.targetContext.getString(R.string.invalid_dns)) != null }
                assertEquals(original, workbench.state.value.settings)
                screenshot("settings-validation")
                click("取消")
                assertEquals(original, workbench.state.value.settings)
            } finally {
                withContext(NonCancellable) {
                    await { !workbench.state.value.busy }
                    if (workbench.state.value.settings != original) {
                        instrumentation.runOnMainSync { workbench.saveSettings(original) }
                        await { !workbench.state.value.busy && workbench.state.value.settings == original }
                    }
                }
                assertEquals(original, workbench.state.value.settings)
            }
        }
    }

    @Test(timeout = 90000) fun emptyLibraryCreateSearchAndClearPreserveOriginalProjects(): Unit = runBlocking {
        val base = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val privateDirectory = File(base.cacheDir, "ui-library-$token")
        val preferenceNames = CopyOnWriteArraySet<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(privateDirectory, "files")
            override fun getNoBackupFilesDir() = File(privateDirectory, "no-backup").apply { mkdirs() }
            override fun stopService(service: Intent) = false
            override fun getCacheDir() = File(privateDirectory, "cache")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "ui-library-$token-$name"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val originalProjects = withContext(Dispatchers.IO) { app.workbench.projects.load() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val isolated = Workbench(context)
            try {
                await { !isolated.state.value.loading }
                instrumentation.runOnMainSync { isolated.doctor() }
                assertFalse(isolated.state.value.installed)
                assertFalse(isolated.state.value.checkingTools)
                assertEquals(0, isolated.state.value.toolCheckRevision)
                assertTrue(isolated.state.value.bundleTools.isEmpty())
                scenario.onActivity { it.setContent { ZakoCoderApp(isolated) } }
                await { find("还没有项目") != null }
                screenshot("library-empty")
                click("终端")
                await { find("还没有终端") != null }
                assertNull(terminal(scenario))
                assertTrue(isolated.state.value.sessions.isEmpty())
                screenshot("terminal-library-no-projects")
                click("环境")
                await { find("工具包") != null }
                assertTrue(isolated.state.value.projects.isEmpty())
                screenshot("tool-bundles-no-projects")
                click("项目库")
                click("添加项目")
                click("克隆仓库")
                await("existing clone form") { find(base.getString(R.string.git_clone_help)) != null }
                assertTrue(isolated.state.value.projects.isEmpty())
                click(base.getString(R.string.close))
                await("clone cancellation returns to library") { find("还没有项目") != null }
                assertTrue(isolated.state.value.projects.isEmpty())
                click("添加项目")
                click("创建空项目")
                setText("library-search-$token")
                click("创建")
                await { !isolated.state.value.busy && isolated.state.value.projects.size == 1 }
                val id = isolated.state.value.project!!.id
                assertTrue(withContext(Dispatchers.IO) { isolated.projects.directory(id).listFiles()!!.isEmpty() })
                click("搜索项目名称")
                setText("no-matching-project")
                await { find("没有匹配的项目，试试其他名称。") != null }
                screenshot("library-search-empty")
                assertEquals(id, isolated.state.value.selectedId)
                assertEquals(1, isolated.state.value.projects.size)
                click("清除搜索")
                await("search cleared") { find("没有匹配的项目，试试其他名称。") == null }
                click("浏览文件")
                await { find("新建文件") != null }
                assertNull("File detail hides bottom navigation", find("环境"))
                click("新建文件")
                setText("fresh.txt")
                click("创建")
                await("new file written to project") { !isolated.state.value.busy && withContext(Dispatchers.IO) { File(isolated.projects.directory(id), "fresh.txt").exists() } }
                await("new file visible without manual refresh") { find("fresh.txt") != null }
                assertEquals(0, withContext(Dispatchers.IO) { File(isolated.projects.directory(id), "fresh.txt").length() })
                withContext(Dispatchers.IO) {
                    val root = isolated.projects.directory(id)
                    File(root, "fresh.txt").writeText("KEEP")
                    try { isolated.projects.createFile(id, "", "fresh.txt"); fail("Existing files must not be overwritten") } catch (_: IllegalArgumentException) { }
                    assertEquals("KEEP", File(root, "fresh.txt").readText())
                    try { isolated.projects.createFile(id, "", "../outside.txt"); fail("Path traversal must be rejected") } catch (_: IllegalArgumentException) { }
                    assertFalse(File(root.parentFile, "outside.txt").exists())
                }
                screenshot("project-files-populated")
                assertEquals(originalProjects, withContext(Dispatchers.IO) { app.workbench.projects.load() })
            } catch (failure: Throwable) {
                screenshot("empty-failure")
                throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    PrivateFiles.deleteTree(privateDirectory)
                    preferenceNames.forEach { base.deleteSharedPreferences(it) }
                }
            }
        }
    }

    private suspend fun longClick(label: String) {
        var target: AccessibilityNodeInfo? = null
        await {
            target = find(label)
            while (target != null && !target!!.isLongClickable) target = target!!.parent
            target != null
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        delay(250)
    }

    private suspend fun setText(text: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { editable(it)?.let { found -> return found } }
            return null
        }
        var target: AccessibilityNodeInfo? = null
        await {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            target = instrumentation.uiAutomation.rootInActiveWindow?.let(::editable)
            target != null
        }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        delay(250)
    }

    private suspend fun await(description: String = "condition", condition: suspend () -> Boolean) {
        try { withTimeout(20000) { while (!condition()) delay(100) } }
        catch (timeout: TimeoutCancellationException) { throw AssertionError("Timed out: $description; " + accessibilitySummary(), timeout) }
    }
    private fun terminal(scenario: ActivityScenario<MainActivity>): TerminalView? {
        var found: TerminalView? = null
        fun walk(view: View) {
            if (view is TerminalView) found = view
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        scenario.onActivity { walk(it.window.decorView) }
        return found
    }
    private suspend fun awaitView(scenario: ActivityScenario<MainActivity>, pid: Int) = await { terminal(scenario)?.currentSession?.pid == pid }
    private fun input(scenario: ActivityScenario<MainActivity>, text: String) {
        val view = terminal(scenario) ?: error("TerminalView is missing")
        instrumentation.runOnMainSync {
            assertTrue("Terminal must receive keyboard focus directly", view.hasFocus())
            assertTrue(view.onCheckIsTextEditor())
            assertTrue(view.onCreateInputConnection(EditorInfo()).commitText(text, 1))
        }
    }
    private fun transcript(scenario: ActivityScenario<MainActivity>): String {
        var output = ""
        val view = terminal(scenario)
        instrumentation.runOnMainSync { output = view?.currentSession?.emulator?.screen?.transcriptText.orEmpty() }
        return output
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        // MIUI may retain stale Compose nodes across scrolling or setContent replacement.
        // Request fresh application windows without changing accessibility configuration.
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let { walk(it)?.let { found -> return found } }
        // Terminal menus preserve IME focus; they are separate, nonfocusable application windows.
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName?.toString() == instrumentation.targetContext.packageName }
            .mapNotNull(::walk).firstOrNull()
    }
    private fun accessibilitySummary(): String {
        val out = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.text != null || node.contentDescription != null) out += "text=${node.text}, description=${node.contentDescription}, visible=${node.isVisibleToUser}"
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return out.joinToString("; ")
    }
    private fun previewButton(): AccessibilityNodeInfo {
        var node = find("在浏览器打开") ?: error("Preview button label missing")
        while (!node.isClickable && node.isEnabled && node.parent != null) node = node.parent
        assertTrue("Find actionable/disabled semantics ancestor, not platform class: ${node.className}", node.isClickable || !node.isEnabled)
        return node
    }
    private suspend fun click(label: String) {
        val scrollableKeys = setOf("End", "Home", "Esc", "Tab", "Ctrl", "Alt", "Ctrl+C", "向左移动光标", "向右移动光标", "向上移动光标", "向下移动光标")
        if (label in scrollableKeys && find(label)?.isVisibleToUser != true) {
            val action = if (label in setOf("End", "Home", "向右移动光标")) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            assertTrue("Shortcut bar must support accessible scrolling", find("终端快捷键")?.performAction(action) == true)
            delay(400)
            // Reapplying unchanged service info clears stale nodes on API 26–33 as well.
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        }
        await("UI action $label") { find(label) != null }
        var node = find(label) ?: error("Missing action $label")
        if (!node.isVisibleToUser) { node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); delay(200); node = find(label) ?: error("Hidden action $label") }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Action failed: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(250)
    }
    private suspend fun screenshot(name: String) {
        delay(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        withContext(Dispatchers.IO) {
            File(instrumentation.targetContext.cacheDir, "ui-review/" + (InstrumentationRegistry.getArguments().getString("theme") ?: "system")).apply { mkdirs() }.resolve("$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        bitmap.recycle()
    }
}

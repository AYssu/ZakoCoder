package dev.zakocoder.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in public dependency download and real browser interaction. No model requests or user configuration reads. */
class ViteBrowserDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, predicate: suspend () -> Boolean) {
        try { withTimeout(30000) { while (!predicate()) delay(100) } }
        catch (cancelled: TimeoutCancellationException) { throw AssertionError("Timed out: $label", cancelled) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let { walk(it)?.let { found -> return found } }
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.mapNotNull(::walk).firstOrNull()
    }
    private suspend fun click(label: String) {
        await("action $label") { find(label) != null }
        var node = find(label) ?: error("Missing $label")
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Click $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(250)
    }
    private suspend fun tapBrowserControl(label: String) {
        await("browser control $label") { find(label) != null }
        val bounds = android.graphics.Rect()
        find(label)!!.getBoundsInScreen(bounds)
        assertFalse("Browser control has real screen bounds", bounds.isEmpty)
        val downAt = android.os.SystemClock.uptimeMillis()
        for ((action, at) in listOf(android.view.MotionEvent.ACTION_DOWN to downAt, android.view.MotionEvent.ACTION_UP to downAt + 50)) {
            val event = android.view.MotionEvent.obtain(downAt, at, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            delay(50)
        }
    }
    private fun stage(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("viteBrowserStage", value) })
    private suspend fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/vite-browser/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout = 300000) fun visibleTerminalServesInteractiveViteAndHotUpdatesWithoutReload(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkDependencyProbe") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("openPreviewEntry") == "true")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await("workbench ready") { !w.state.value.loading && !w.state.value.busy }
            check(w.state.value.installed && !w.ai.state.value.active && !w.projectTasks.state.value.busy)
            check(w.terminal.sessions.size < 4)
            val previous = w.state.value.selectedId
            var project: String? = null
            var terminal: String? = null
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            suspend fun page(): String = withContext(Dispatchers.IO) {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000); socket.soTimeout = 2000
                    socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
            }
            val npm = """
                export NPM_CONFIG_USERCONFIG="${'$'}PWD/.cache/user.npmrc" NPM_CONFIG_GLOBALCONFIG="${'$'}PWD/.cache/global.npmrc"
                export NPM_CONFIG_CACHE="${'$'}PWD/.cache/npm" NPM_CONFIG_REGISTRY=https://registry.npmjs.org
                export NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false NPM_CONFIG_UPDATE_NOTIFIER=false
                export NPM_CONFIG_FETCH_RETRIES=0 NPM_CONFIG_FETCH_TIMEOUT=30000
            """.trimIndent() + "\n"
            try {
                val name = "vite-browser-${UUID.randomUUID()}"
                main { w.create(name) }
                await("fixture created") { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; project = id
                val root = w.projects.directory(id)
                withContext(Dispatchers.IO) {
                    File(root, ".cache").mkdirs()
                    File(root, ".cache/user.npmrc").writeText("")
                    File(root, ".cache/global.npmrc").writeText("")
                    File(root, "package.json").writeText("""{"private":true,"type":"module","scripts":{"build":"vite build","dev":"vite --host 127.0.0.1 --port $port --strictPort"},"devDependencies":{"vite":"5.4.21"}}""")
                    File(root, "index.html").writeText("""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZakoCoder Vite browser fixture</title></head><body><h1 id="title"></h1><button id="counter" style="font-size:24px;padding:20px">Count 0</button><p id="updates">Updates 0</p><script type="module" src="/main.js"></script></body></html>""")
                    File(root, "message.js").writeText("export const title = 'Vite initial ready';\n")
                    File(root, "main.js").writeText("""
                        import {title} from './message.js';
                        document.querySelector('#title').textContent = title;
                        let count = 0, updates = 0;
                        document.querySelector('#counter').onclick = () => {
                          document.querySelector('#counter').textContent = 'Count ' + (++count);
                        };
                        if (import.meta.hot) import.meta.hot.accept('./message.js', mod => {
                          document.querySelector('#title').textContent = mod.title;
                          document.querySelector('#updates').textContent = 'Updates ' + (++updates);
                        });
                    """.trimIndent())
                }
                stage("isolated npm install/build started")
                val install = w.runtime.execute(npm + "set -eu\nnode --version\nnpm --version\nnpm install\nnpm run build\ntest -s dist/index.html", id, w.state.value.settings, timeoutMillis = 120000)
                assertEquals("Isolated npm install/build: ${install.output.takeLast(4000)}", 0, install.exitCode)
                stage("isolated npm install/build exit=0")
                main { w.startTerminal(id) }
                await("owned terminal") { !w.state.value.busy && w.state.value.sessions.any { it.projectId == id } }
                val item = w.state.value.sessions.single { it.projectId == id }; terminal = item.id
                main { item.session.write(npm + "npm run dev\n") }
                await("Vite HTTP ready") { try { page().contains("200 OK") } catch (_: java.io.IOException) { false } }
                click("终端"); click("进入终端 ${item.number}")
                click("终端操作"); click("预览网页")
                await("actual Vite address candidate") { find("http://127.0.0.1:$port/") != null }
                screenshot("candidate")
                click("在浏览器打开")
                var stable = 0L
                await("browser initial module rendered") {
                    if (find("选择兴趣，获取精准推荐") != null && find("跳过") != null) {
                        click("跳过"); stable = 0L; false
                    } else if (find("Vite initial ready") != null && find("Count 0") != null) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (stable == 0L) stable = now
                        now - stable >= 1500
                    } else { stable = 0L; false }
                }
                screenshot("loaded")
                tapBrowserControl("Count 0")
                await("real browser JavaScript interaction") { find("Count 1") != null }
                screenshot("interactive")
                stage("browser click count=1")
                // A full refresh resets the counter. Require a module update with the existing page state intact.
                withContext(Dispatchers.IO) { File(root, "message.js").writeText("export const title = 'Vite hot update ready';\n") }
                await("Vite HMR visible without manual reload") { find("Vite hot update ready") != null && find("Updates 1") != null }
                assertNotNull("Counter state must survive hot update", find("Count 1"))
                assertNull("Old module text must be gone", find("Vite initial ready"))
                screenshot("hot-update")
                stage("HMR http://127.0.0.1:$port/ counter=1 updates=1")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                delay(300)
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) {
                    instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.zakocoder.app/.MainActivity").use { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader -> assertTrue(reader.readText().contains("Status: ok")) }
                    }
                }
                await("return to app") { find("预览网页") != null }
                click("关闭"); click("返回终端列表"); click("环境")
                assertTrue("Service remains alive after browser and app navigation", page().contains("200 OK"))
                assertTrue(item.session.isRunning)
                withContext(Dispatchers.Main) { w.terminal.close(item.id) }; terminal = null
                await("owned service stopped") { try { page(); false } catch (_: java.io.IOException) { true } }
                stage("service survived navigation; close released port")
            } finally {
                withContext(NonCancellable) {
                    terminal?.let { id -> withContext(Dispatchers.Main) { w.terminal.close(id) } }
                    project?.let { id ->
                        main { w.removeProject(id) }
                        await("fixture cleanup") { !w.state.value.busy && w.state.value.projects.none { it.id == id } }
                    }
                    previous?.let { id -> main { w.select(id) }; await("restore selection") { !w.state.value.busy } }
                }
            }
        }
    }
}

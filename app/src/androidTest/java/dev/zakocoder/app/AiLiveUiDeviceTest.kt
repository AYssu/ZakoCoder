package dev.zakocoder.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in paid-model UI acceptance. Uses the actual application owner; all task data belongs to a generated project. */
class AiLiveUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String = "condition", condition: suspend () -> Boolean) {
        try { withTimeout(30000) { while (!condition()) delay(100) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
    }
    private fun stage(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("liveUiStage", value) })
    private fun reviewText(): String? {
        var result: String? = null
        fun walk(view: android.view.View) {
            if (view is io.github.rosemoe.sora.widget.CodeEditor && !view.isEditable && view.isShown) result = view.text.toString()
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        instrumentation.runOnMainSync { android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::walk) }
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                var ancestor = node
                while (!ancestor.isClickable && ancestor.parent != null) ancestor = ancestor.parent
                if (ancestor.isClickable) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.mapNotNull(::walk).firstOrNull()
    }
    private suspend fun click(label: String, long: Boolean = false) {
        val tabLabels = listOf(context.getString(R.string.ai_process), context.getString(R.string.ai_requests), context.getString(R.string.ai_skills), context.getString(R.string.project_validation))
        if (label in tabLabels && find(label) == null) {
            repeat(3) {
                if (find(label) == null) {
                    fun scrollHorizontal(node: AccessibilityNodeInfo): Boolean {
                        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
                        if (node.isScrollable && rect.width() > rect.height() * 3 && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                        return (0 until node.childCount).any { index -> node.getChild(index)?.let(::scrollHorizontal) == true }
                    }
                    instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollHorizontal); delay(250)
                }
            }
        }
        try { await { find(label) != null } } catch (error: TimeoutCancellationException) { throw AssertionError("Clickable label not found: $label", error) }
        var node = find(label)!!
        if (long) { while (!node.isLongClickable && node.parent != null) node = node.parent }
        else { while (!node.isClickable && node.parent != null) node = node.parent }
        assertTrue(label, node.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)); delay(300)
    }
    private fun visibleText(label: String): Boolean {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        fun walk(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.text?.toString() == label) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::walk) == true }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.any(::walk)
    }
    private suspend fun scrollProcess(forward: Boolean = true): Boolean {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
        var candidate: AccessibilityNodeInfo? = null; var height = 0
        fun walk(node: AccessibilityNodeInfo) {
            val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
            if (node.isScrollable && node.isVisibleToUser && rect.height() > height) { candidate = node; height = rect.height() }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        instrumentation.uiAutomation.windows.mapNotNull { it.root }.forEach(::walk)
        val moved = candidate?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        delay(350)
        return moved
    }
    private suspend fun field(value: String, label: String? = null, keyboard: Boolean = false) {
        var edit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isEditable) {
                fun hasLabel(candidate: AccessibilityNodeInfo): Boolean = candidate.text?.toString() == label || candidate.contentDescription?.toString() == label || candidate.hintText?.toString() == label ||
                    (0 until candidate.childCount).any { index -> candidate.getChild(index)?.let(::hasLabel) == true }
                val matches = label == null || hasLabel(node)
                if (matches) edit = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        try { await {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == context.packageName }.forEach(::walk)
            edit != null
        } } catch (error: TimeoutCancellationException) { throw AssertionError("Editable field not found: $label", error) }
        if (keyboard) { edit!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); edit!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); delay(400) }
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })); delay(200)
    }
    private suspend fun reveal(label: String) {
        repeat(5) {
            if (find(label)?.isVisibleToUser == true) return
            scrollProcess()
        }
        assertNotNull(label, find(label))
    }
    private suspend fun screenshot(name: String) {
        delay(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        withContext(Dispatchers.IO) {
            val theme = InstrumentationRegistry.getArguments().getString("theme") ?: "system"
            val directory = File(context.cacheDir, "ui-review/$theme").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    @Test(timeout = 720000) fun realModelRepairsReviewsVerifiesAndOpensItsServerThroughUi(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCoding") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("fullUi") == "true")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val host = (context.applicationContext as ZakoApplication).workbench
            await { !host.state.value.loading && !host.state.value.busy }
            check(host.state.value.installed && !host.ai.state.value.active && !host.projectTasks.state.value.busy)
            check(host.terminal.sessions.isEmpty()) { "Acceptance must not compete with existing terminals" }
            val previous = host.state.value.selectedId
            val previousAi = host.ai.state.value.projectId
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            var project: String? = null
            var injected: Workbench? = null
            try {
                val name = "live-ui-${UUID.randomUUID()}"
                main { host.create(name) }
                await { !host.state.value.busy && host.state.value.project?.name == name }
                val id = host.state.value.selectedId!!; project = id
                val root = host.projects.directory(id)
                withContext(Dispatchers.IO) {
                    File(root, "src").mkdirs(); File(root, "tests").mkdirs()
                    File(root, "AGENTS.md").writeText("Only edit src/cart.py and src/view.py. Preserve tests, server.py and project rules. No dependencies or package installation. Use the configured test/build commands. Keep the server in a visible managed terminal; no detached processes.\n")
                    File(root, "README.md").writeText("Python standard-library mobile cart. src/cart.py calculates totals; src/view.py supplies the page title. Test: python3 -m unittest discover -s tests -v. Build: python3 -m py_compile src/cart.py src/view.py server.py. Run: python3 -u server.py, serving http://127.0.0.1:$port/. The page's Calculate button fetches /quote. Only implementation files may change.\n")
                    File(root, "src/cart.py").writeText("def total(items):\n    return sum(item['price'] for item in items)\n")
                    File(root, "src/view.py").writeText("TITLE = 'Draft cart'\n")
                    File(root, "tests/test_cart.py").writeText("""
                        import unittest
                        from src.cart import total
                        from src.view import TITLE
                        class CartTest(unittest.TestCase):
                            def test_quantities(self): self.assertEqual(total([{'price':12.5,'quantity':2},{'price':3,'quantity':2}]),31)
                            def test_empty(self): self.assertEqual(total([]),0)
                            def test_zero(self): self.assertEqual(total([{'price':8,'quantity':0}]),0)
                            def test_heading(self): self.assertEqual(TITLE,'Mobile cart ready')
                    """.trimIndent() + "\n")
                    File(root, "server.py").writeText("""
                        from http.server import BaseHTTPRequestHandler, HTTPServer
                        from src.cart import total
                        from src.view import TITLE
                        class Handler(BaseHTTPRequestHandler):
                            def do_GET(self):
                                if self.path == '/quote':
                                    data = ('Total: %.2f' % total([{'price':12.5,'quantity':2},{'price':3,'quantity':2}])).encode()
                                else:
                                    data = ('<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Mobile cart acceptance</title></head><body><h1>' + TITLE + '</h1><button onclick="fetch(`/quote`).then(r=>r.text()).then(t=>document.querySelector(`#result`).textContent=t)">Calculate total</button><p id="result">Not calculated</p></body></html>').encode()
                                self.send_response(200)
                                self.send_header('Content-Type','text/html; charset=utf-8')
                                self.send_header('Content-Length',str(len(data)))
                                self.end_headers()
                                self.wfile.write(data)
                        print('http://127.0.0.1:$port/',flush=True)
                        HTTPServer(('127.0.0.1',$port),Handler).serve_forever()
                    """.trimIndent() + "\n")
                }
                val protected = withContext(Dispatchers.IO) {
                    listOf("AGENTS.md", "README.md", "server.py", "tests/test_cart.py").associateWith { File(root, it).readBytes().toList() }
                }
                val baseline = host.runtime.execute("python3 -m unittest discover -s tests -v", id, host.state.value.settings, timeoutMillis = 30000)
                assertNotEquals("Actual baseline tests must fail", 0, baseline.exitCode)
                val w = host; injected = w
                main { w.ai.select(id); w.projectTasks.select(id) }
                await { w.ai.state.value.projectId == id && w.ai.state.value.configured && !w.ai.state.value.permissionsLoading && !w.ai.state.value.skillsLoading && !w.projectTasks.state.value.loading }
                // A new project has the default policy. Never persist test permission overrides.
                assertEquals(AiPolicy(), w.ai.state.value.policy)
                for (command in listOf(ProjectCommand("test", "python3 -m unittest discover -s tests -v", timeoutSeconds = 60),
                    ProjectCommand("build", "python3 -m py_compile src/cart.py src/view.py server.py", timeoutSeconds = 60))) {
                    main { w.projectTasks.save(command) }; await { !w.projectTasks.state.value.saving }
                    assertTrue(w.projectTasks.state.value.commands.contains(command))
                }
                click(context.getString(R.string.nav_ai))
                field("修好这个手机购物清单：金额按单价乘数量汇总，页面标题改成 Mobile cart ready。自行定位文件并遵守项目规则，修改用可以预览的文件工具，审批时让我审阅。执行项目已配置的测试、构建，再用可见项目终端启动服务，实际请求网页及 /quote 确认金额。结束后保留服务运行，告诉我修改文件、实际验证结果和预览地址。不要安装依赖或改测试。")
                click(context.getString(R.string.ai_send))
                stage("sent through UI; baseline failed")
                var approvals = 0; var reviewedApproval = false; var observedRound = -1
                withTimeout(480000) {
                    while (w.ai.state.value.active) {
                        val state = w.ai.state.value
                        if (state.round != observedRound) { observedRound = state.round; stage("model round=$observedRound tools=${state.toolCount} phase=${state.phase}") }
                        if (state.round > 32) { main { w.ai.stop() }; error("Acceptance-only cost guard reached") }
                        state.approval?.let { approval ->
                            if (approval.review != null && !reviewedApproval) {
                                click(context.getString(R.string.ai_review_content))
                                await { reviewText()?.isNotBlank() == true }
                                screenshot("live-ui-approval-diff")
                                click(context.getString(R.string.close)); reviewedApproval = true
                            }
                            click(context.getString(R.string.ai_approve_once)); approvals++
                        }
                        delay(100)
                    }
                }
                assertEquals("Real model phase: ${w.ai.state.value.failureKind}", AiPhase.Completed, w.ai.state.value.phase)
                assertTrue("At least one actual write approval was reviewed", reviewedApproval)
                stage("model completed; approvals=$approvals")
                withContext(Dispatchers.IO) {
                    val state = w.ai.state.value
                    val audit = org.json.JSONObject().put("report", AiLogRedactor("").clean(state.timeline.last { it.role == AiRole.Assistant }.text))
                        .put("rounds", state.round).put("tools", state.toolCount).put("approvals", approvals)
                        .put("events", org.json.JSONArray(state.events.map { org.json.JSONObject().put("tool", it.tool?.name).put("status", it.status.name).put("exit", it.exitCode ?: org.json.JSONObject.NULL) }))
                    File(context.cacheDir, "ui-review/live-ui-audit.json").apply { parentFile!!.mkdirs() }.writeText(audit.toString())
                }
                withContext(Dispatchers.IO) { protected.forEach { (path, original) -> assertEquals(path, original, File(root, path).readBytes().toList()) } }
                val source = withContext(Dispatchers.IO) { ProjectSourceEvidence(w.projects::directory).capture(id, currentCoroutineContext()) }
                val runs = withContext(Dispatchers.IO) { w.projectTasks.runs(id) }
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "ui-review/live-ui-audit.json")
                    val audit = org.json.JSONObject(file.readText()).put("commands", org.json.JSONArray(runs.map { run ->
                        org.json.JSONObject().put("run_id", run.id).put("kind", run.kind).put("script", AiLogRedactor("").clean(run.script))
                            .put("status", run.status).put("exit", run.exit ?: org.json.JSONObject.NULL).put("source_verification", run.verification(source).name)
                    }))
                    file.writeText(audit.toString())
                }
                for (kind in listOf("test", "build")) {
                    val run = runs.firstOrNull { it.kind == kind } ?: error("Model did not execute configured $kind")
                    assertEquals(kind, 0, run.exit); assertEquals(ProjectVerification.Unchanged, run.verification(source))
                }
                click(context.getString(R.string.project_validation))
                await { !w.projectTasks.state.value.sourceChecking && w.projectTasks.state.value.currentSource != null }
                repeat(8) { if (!visibleText(context.getString(R.string.project_check_passed))) scrollProcess() }
                assertTrue("Actual test result visible", visibleText(context.getString(R.string.project_check_passed)))
                screenshot("live-ui-validation")
                val changes = withContext(Dispatchers.IO) { w.ai.checkpointChanges(w.ai.state.value.checkpointId!!) }
                assertEquals(setOf("src/cart.py", "src/view.py"), changes.map { it.path }.toSet())
                for (path in listOf("src/cart.py", "src/view.py")) {
                    reveal(path); click(path)
                    val change = changes.single { it.path == path }
                    await { reviewText() == fileDiff(change.before.orEmpty(), change.after.orEmpty()) }
                    click(context.getString(R.string.ai_review_before)); await { reviewText() == change.before }
                    click(context.getString(R.string.ai_review_after)); await { reviewText() == change.after }
                    click(context.getString(R.string.ai_review_diff)); await { reviewText() == fileDiff(change.before.orEmpty(), change.after.orEmpty()) }
                    screenshot("live-ui-final-${File(path).nameWithoutExtension}"); click(context.getString(R.string.close))
                }
                val item = w.terminal.sessions.single { it.projectId == id }
                assertTrue(item.session.isRunning)
                click(context.getString(R.string.ai_process))
                val open = context.getString(R.string.ai_open_terminal, item.number)
                // Process history is a vertical list; reveal the model's terminal event from the end.
                repeat(24) { if (find(open)?.isVisibleToUser != true) scrollProcess(false) }
                assertNotNull("Direct entry on the model's actual terminal event", find(open))
                click(open); click("终端操作"); click(context.getString(R.string.terminal_preview))
                await { visibleText("http://127.0.0.1:$port/") }
                screenshot("live-ui-preview-candidate")
                click(context.getString(R.string.terminal_preview_open))
                var stable = 0L
                await {
                    if (browserFind("选择兴趣，获取精准推荐") != null && browserFind("跳过") != null) { tapBrowserControl("跳过"); false }
                    else if (browserFind("Mobile cart ready") != null && browserFind("Calculate total") != null) {
                        val now = android.os.SystemClock.elapsedRealtime(); if (stable == 0L) stable = now; now - stable >= 1500
                    } else { stable = 0L; false }
                }
                tapBrowserControl("Calculate total")
                await { browserFind("Total: 31.00") != null }
                screenshot("live-ui-browser-result"); stage("real browser fetched corrected total=31.00")
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); delay(300)
                if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) {
                    instrumentation.uiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.zakocoder.app/.MainActivity").use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> assertTrue(reader.readText().contains("Status: ok")) }
                    }
                }
                await("return to actual application terminal") {
                    find(context.getString(R.string.terminal_preview)) != null || find("终端操作") != null || find("进入终端 ${item.number}") != null
                }
                if (find(context.getString(R.string.terminal_preview)) != null) click(context.getString(R.string.close))
                if (find("返回终端列表") != null) click("返回终端列表")
                await("owned terminal card") { find("进入终端 ${item.number}") != null }
                assertTrue("Server survived browser/navigation", item.session.isRunning)
                val restored = withContext(Dispatchers.IO) { AiHistoryStore(context).load(id).state!! }
                assertEquals(w.ai.state.value.timeline, restored.timeline)
                // Monotonic timestamps are deliberately persisted as elapsed durations, not raw clock values.
                val persistedEvents = w.ai.state.value.events.map { event ->
                    val duration = if (event.started != null && event.finished != null) (event.finished - event.started).coerceAtLeast(0) else null
                    event.copy(started = duration?.let { 0L }, finished = duration)
                }
                assertEquals(persistedEvents, restored.events)
                val independent = w.runtime.execute("python3 -m unittest discover -s tests -v && python3 -m py_compile src/cart.py src/view.py server.py", id, w.state.value.settings, timeoutMillis = 30000)
                assertEquals("Independent final test/build", 0, independent.exitCode)
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "ui-review/live-ui-audit.json")
                    file.writeText(org.json.JSONObject(file.readText()).put("independentTestBuildExit", independent.exitCode)
                        .put("browserTotal", "31.00").put("historyReload", true).toString())
                }
                stage("PASS full UI task; requests=${w.ai.state.value.round}; tools=${w.ai.state.value.toolCount}; test/build=0; exact two source diffs; browser=31.00; historyReload=true")
            } finally {
                withContext(NonCancellable) {
                    injected?.let { w ->
                        main { w.ai.stop(); w.projectTasks.stop() }
                        await { !w.ai.state.value.active && !w.projectTasks.state.value.busy }
                        withContext(Dispatchers.Main) { w.terminal.sessions.filter { it.projectId == project }.toList().forEach { w.terminal.close(it.id) } }
                    }
                    project?.let { id -> main { host.removeProject(id) }; await { !host.state.value.busy && host.state.value.projects.none { it.id == id } } }
                    previous?.let { id -> main { host.select(id) }; await { !host.state.value.busy } }
                    await("AI selection ready") { !host.ai.state.value.permissionsLoading && !host.ai.state.value.permissionsSaving }
                    main { host.ai.select(previousAi) }
                    await("restore AI selection") { host.ai.state.value.projectId == previousAi && !host.ai.state.value.permissionsLoading }
                }
            }
        }
    }
    private fun browserFind(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
    }
    private suspend fun tapBrowserControl(label: String) {
        val bounds = android.graphics.Rect(); (browserFind(label) ?: error("Missing browser control $label")).getBoundsInScreen(bounds)
        assertFalse(bounds.isEmpty)
        val downAt = android.os.SystemClock.uptimeMillis()
        for ((action, at) in listOf(android.view.MotionEvent.ACTION_DOWN to downAt, android.view.MotionEvent.ACTION_UP to downAt + 50)) {
            val event = android.view.MotionEvent.obtain(downAt, at, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            delay(50)
        }
    }
}

package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.workflow.ProjectTasks
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit opt-in real-model acceptance. Device credentials stay inside AiConfigStore; no bodies exported. */
class AiLiveCodingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(300000) { while (!block()) delay(50) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test(timeout = 360000) fun realModelLocatesFixesTwoFilesAndValidatesWithoutUserFileReferences() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCoding") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading }
            assertTrue(w.state.value.installed)
            check(!w.state.value.busy && !w.ai.state.value.active && !w.projectTasks.state.value.busy)
            val owned = File(context.cacheDir, "live-coding-${UUID.randomUUID()}").apply { mkdirs() }
            val wrapper = object : ContextWrapper(context) {
                override fun getFilesDir() = File(owned, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(owned, "private").apply { mkdirs() }
            }
            val project = withContext(Dispatchers.IO) { w.projects.create("live-coding-owned") }
            val directory = w.projects.directory(project.id)
            val tasks = ProjectTasks(wrapper, w.projects::directory, { id, command, timeout, output -> w.runtime.execute(command, id, w.state.value.settings, timeoutMillis = minOf(timeout, 60000), output = output) }, { true }, {})
            var controller: AiController? = null
            val start = android.os.SystemClock.elapsedRealtime()
            var approvals = 0
            try {
                withContext(Dispatchers.IO) {
                    File(directory, "README.md").writeText("Checkout utility. Verification: python3 -m unittest discover -s tests -v\nOnly Python standard library is needed.\n")
                    File(directory, "AGENTS.md").writeText("Change implementation only; never modify tests. Preserve public function names and Decimal values. Verify with the documented test command.\n")
                    File(directory, "pricing.py").writeText("from decimal import Decimal, ROUND_HALF_UP\n\ndef quote(subtotal, discount, tax):\n    subtotal, discount, tax = map(Decimal, (subtotal, discount, tax))\n    return (subtotal * (1 + tax) - discount).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)\n")
                    File(directory, "presenter.py").writeText("def receipt(amount):\n    return f'应付: ${'$'}{amount:.1f}'\n")
                    File(directory, "tests").mkdirs()
                    File(directory, "tests/test_checkout.py").writeText("from decimal import Decimal\nimport unittest\nfrom pricing import quote\nfrom presenter import receipt\n\nclass CheckoutTest(unittest.TestCase):\n    def test_discount_before_tax(self):\n        self.assertEqual(Decimal('99.00'), quote('100', '10', '0.10'))\n    def test_rounding(self):\n        self.assertEqual(Decimal('9.05'), quote('10', '1', '0.005'))\n    def test_receipt(self):\n        self.assertEqual('应付: ¥99.00', receipt(Decimal('99')))\n")
                }
                val testFile = File(directory, "tests/test_checkout.py")
                val originalTests = withContext(Dispatchers.IO) { hash(testFile) }
                val baseline = w.runtime.execute("python3 -m unittest discover -s tests -v", project.id, w.state.value.settings, timeoutMillis = 30000)
                assertNotEquals("Acceptance fixture must actually fail before editing", 0, baseline.exitCode)
                main {
                    controller = AiController(wrapper, w.projects::directory, { _, _ -> false }, { _, _ -> error("Use recorded commands") }, {}, {},
                        config = AiConfigStore(context), permissionStore = AiPermissionStore(wrapper), history = AiHistoryStore(File(owned, "history")),
                        longCommand = { id, script, timeout, output -> tasks.observed(id, script, timeout, output, source = "ai") }, commandLog = tasks::logPage)
                    controller!!.select(project.id)
                }
                val ai = controller!!
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                main {
                    val allowed = setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.UpdatePlan)
                    val ask = setOf(AiTool.RunCommand, AiTool.WriteFile, AiTool.ReadCommandLog)
                    ai.setPolicy(AiPolicy(AiPermission.Deny, AiTool.entries.associateWith { if (it in allowed) AiPermission.Allow else if (it in ask) AiPermission.Ask else AiPermission.Deny }))
                }
                await { !ai.state.value.permissionsSaving }
                main { assertTrue(ai.send("请自行定位并修复订单结算：先扣折扣再计税，Decimal 金额采用四舍五入保留两位；摘要用人民币符号并保留两位小数。不要修改测试或安装依赖。只操作当前项目，使用现有测试验证。完成后列出实际修改和验证结果，不要向我索要文件路径。")) }
                withTimeout(300000) {
                    while (ai.state.value.active) {
                        val approval = ai.state.value.approval
                        if (approval != null) {
                            approvals++
                            main { ai.approve(AiDecision.TaskTool) }
                            delay(100)
                        }
                        if (ai.state.value.requests.size > 24) { main { ai.stop() }; error("Acceptance cost guard reached; not an app task limit") }
                        delay(50)
                    }
                }
                assertEquals("Real model failed: ${ai.state.value.failureKind}", AiPhase.Completed, ai.state.value.phase)
                val verification = w.runtime.execute("python3 -m unittest discover -s tests -v", project.id, w.state.value.settings, timeoutMillis = 30000)
                assertEquals("Actual tests must pass, model prose is not evidence", 0, verification.exitCode)
                assertEquals("AI must not change tests", originalTests, withContext(Dispatchers.IO) { hash(testFile) })
                assertTrue(ai.state.value.events.any { it.tool == AiTool.RunCommand && it.exitCode == 0 })
                val changed = withContext(Dispatchers.IO) { ai.checkpointChanges(ai.state.value.checkpointId!!) }
                assertEquals(setOf("pricing.py", "presenter.py"), changed.map { it.path }.toSet())
                val requests = ai.state.value.requests
                instrumentation.sendStatus(0, Bundle().apply { putString("codingAcceptance", "phase=${ai.state.value.phase}; requests=${requests.size}; tools=${ai.state.value.toolCount}; approvalScopes=$approvals; inputTokens=${requests.sumOf { it.inputTokens ?: 0 }}; outputTokens=${requests.sumOf { it.outputTokens ?: 0 }}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}; changedFiles=${changed.size}; realTestExit=${verification.exitCode}") })
            } finally {
                controller?.let { ai -> main { ai.stop() }; await { !ai.state.value.active } }
                withContext(Dispatchers.IO) { tasks.deleteProject(project.id); PrivateFiles.deleteTree(w.projects.directory(project.id)); PrivateFiles.deleteTree(owned) }
            }
        }
    }
    @Test(timeout = 360000) fun realModelEditsVerifiesAndStartsPhonePreviewInVisibleTerminal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCoding") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            assertTrue(w.state.value.installed); assertTrue(w.terminal.sessions.size < 4)
            check(!w.ai.state.value.active && !w.projectTasks.state.value.busy)
            val previous = w.state.value.selectedId
            val owned = File(context.cacheDir, "live-preview-${UUID.randomUUID()}").apply { mkdirs() }
            val wrapper = object : ContextWrapper(context) {
                override fun getFilesDir() = File(owned, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(owned, "private").apply { mkdirs() }
            }
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            var project: String? = null; var controller: AiController? = null
            var tasks: ProjectTasks? = null; var approvals = 0
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                val name = "live-preview-${UUID.randomUUID()}"
                main { w.create(name) }; await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; project = id
                val directory = w.projects.directory(id)
                withContext(Dispatchers.IO) {
                    File(directory, "README.md").writeText("Static mobile page. No dependencies required. Verify: python3 -m unittest discover -s tests -v\nPreview: python3 -u -m http.server $port --bind 127.0.0.1\nThe server must remain in a visible project terminal, not a detached finite command.\n")
                    File(directory, "AGENTS.md").writeText("Preserve tests. Only modify the page. Use existing standard-library tools; never install dependencies.\n")
                    File(directory, "index.html").writeText("<!doctype html><html><head><title>Draft</title></head><body><h1>Draft</h1></body></html>\n")
                    File(directory, "tests").mkdirs()
                    File(directory, "tests/test_page.py").writeText("from pathlib import Path\nimport unittest\nfrom html.parser import HTMLParser\n\nclass PageParser(HTMLParser):\n    def __init__(self):\n        super().__init__(); self.mobile = False; self.heading = False; self.in_h1 = False\n    def handle_starttag(self, tag, attrs):\n        attrs = dict(attrs)\n        if tag == 'meta' and attrs.get('name') == 'viewport' and 'width=device-width' in attrs.get('content', ''): self.mobile = True\n        if tag == 'h1': self.in_h1 = True\n    def handle_endtag(self, tag):\n        if tag == 'h1': self.in_h1 = False\n    def handle_data(self, data):\n        if self.in_h1 and data.strip() == 'Zako Preview': self.heading = True\n\nclass PageTest(unittest.TestCase):\n    def test_mobile_page(self):\n        parser = PageParser(); parser.feed(Path('index.html').read_text())\n        self.assertTrue(parser.mobile); self.assertTrue(parser.heading)\n")
                }
                val test = File(directory, "tests/test_page.py")
                val baselineHash = withContext(Dispatchers.IO) { hash(test) }
                val recorded = ProjectTasks(wrapper, w.projects::directory, { pid, script, timeout, output -> w.runtime.execute(script, pid, w.state.value.settings, timeoutMillis = minOf(timeout, 60000), output = output) }, { true }, {})
                tasks = recorded
                main {
                    controller = AiController(wrapper, w.projects::directory, { _, _ -> false }, { _, _ -> error("Use recorded commands") }, {}, {},
                        config = AiConfigStore(context), permissionStore = AiPermissionStore(wrapper), history = AiHistoryStore(File(owned, "history")),
                        longCommand = { pid, script, timeout, output -> recorded.observed(pid, script, timeout, output, source = "ai") }, commandLog = recorded::logPage,
                        terminalTool = w::executeAiTerminal)
                    controller!!.select(id)
                }
                val ai = controller!!
                await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
                main { ai.send("完成当前页面：主标题改成 Zako Preview，适配手机视口。不要修改测试或安装依赖。自行查看项目规则与文档，运行既有测试，然后用可见项目终端启动文档中的预览服务，实际请求 URL 验证后告诉我可访问地址。服务需要在任务结束后继续运行。不要向我索要文件路径或端口。") }
                withTimeout(300000) {
                    while (ai.state.value.active) {
                        if (ai.state.value.approval != null) { approvals++; main { ai.approve(AiDecision.TaskTool) }; delay(100) }
                        if (ai.state.value.requests.size > 24) { main { ai.stop() }; error("Acceptance cost guard reached") }
                        delay(50)
                    }
                }
                assertEquals("Real model failed: ${ai.state.value.failureKind}", AiPhase.Completed, ai.state.value.phase)
                assertEquals(baselineHash, withContext(Dispatchers.IO) { hash(test) })
                val verification = w.runtime.execute("python3 -m unittest discover -s tests -v", id, w.state.value.settings, timeoutMillis = 30000)
                assertEquals(0, verification.exitCode)
                assertTrue(ai.state.value.events.any { it.tool == AiTool.RunCommand && it.exitCode == 0 })
                assertTrue(ai.state.value.events.any { it.tool == AiTool.StartTerminal && it.status == AiToolStatus.Succeeded })
                val terminal = w.terminal.sessions.single { it.projectId == id }
                assertTrue("Service must outlive the AI turn", terminal.session.isRunning)
                val response = withContext(Dispatchers.IO) {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000); socket.soTimeout = 3000
                        socket.getOutputStream().write("GET / HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                        socket.getInputStream().bufferedReader().readText()
                    }
                }
                assertTrue(response.contains("200 OK")); assertTrue(response.contains("Zako Preview"))
                assertTrue("AI must actually request the preview rather than only announce it", ai.state.value.events.any { it.tool == AiTool.RunCommand && it.output.contains("Zako Preview") })
                val requests = ai.state.value.requests
                instrumentation.sendStatus(0, Bundle().apply { putString("previewAcceptance", "phase=${ai.state.value.phase}; requests=${requests.size}; tools=${ai.state.value.toolCount}; approvalScopes=$approvals; inputTokens=${requests.sumOf { it.inputTokens ?: 0 }}; outputTokens=${requests.sumOf { it.outputTokens ?: 0 }}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}; realTestExit=${verification.exitCode}; http=200; terminalAlive=true") })
            } finally {
                controller?.let { ai -> main { ai.stop() }; await { !ai.state.value.active } }
                withContext(Dispatchers.Main) { w.terminal.sessions.filter { it.projectId == project }.toList().forEach { w.terminal.close(it.id) } }
                project?.let { id ->
                    withContext(Dispatchers.IO) { tasks?.deleteProject(id) }
                    main { w.removeProject(id) }; await { !w.state.value.busy && w.state.value.projects.none { it.id == id } }
                }
                previous?.let { id -> main { w.select(id) }; await { !w.state.value.busy } }
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(owned) }
            }
        }
    }

}

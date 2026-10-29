package dev.zakocoder.app

import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.workflow.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in paid-model acceptance, not a canned response. Credentials stay inside the device config store. */
class AiLiveViteDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(30000) { while (!predicate()) delay(50) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    // Every test command/PTY uses generated empty npm configs and a private fixture cache, never user npm auth.
    private val npmEnvironment = """
        export NPM_CONFIG_USERCONFIG="${'$'}PWD/.cache/user.npmrc" NPM_CONFIG_GLOBALCONFIG="${'$'}PWD/.cache/global.npmrc"
        export NPM_CONFIG_CACHE="${'$'}PWD/.cache/npm" NPM_CONFIG_REGISTRY=https://registry.npmjs.org
        export NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false NPM_CONFIG_UPDATE_NOTIFIER=false
        export NPM_CONFIG_FETCH_RETRIES=0 NPM_CONFIG_FETCH_TIMEOUT=30000
    """.trimIndent() + "\n"

    @Test(timeout = 600000) fun realModelFixesInstallsBuildsAndServesViteProject(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realCoding") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkDependencyProbe") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            check(w.state.value.installed && !w.ai.state.value.active && !w.projectTasks.state.value.busy)
            check(w.terminal.sessions.size < 4)
            val previous = w.state.value.selectedId
            val owned = File(context.cacheDir, "live-vite-${UUID.randomUUID()}").apply { mkdirs() }
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(owned, "control").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(owned, "private").apply { mkdirs() }
            }
            val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            var project: String? = null
            var ai: AiController? = null
            var tasks: ProjectTasks? = null
            var approvals = 0
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                val name = "live-vite-${UUID.randomUUID()}"
                main { w.create(name) }
                await { !w.state.value.busy && w.state.value.project?.name == name }
                val id = w.state.value.selectedId!!; project = id
                val root = w.projects.directory(id)
                withContext(Dispatchers.IO) {
                    File(root, "src").mkdirs(); File(root, "tests").mkdirs(); File(root, ".cache").mkdirs()
                    File(root, ".cache/user.npmrc").writeText(""); File(root, ".cache/global.npmrc").writeText("")
                    File(root, "AGENTS.md").writeText("Source edits only in src/. Documented npm commands may create package-lock.json, node_modules, .cache and dist. Preserve package.json, tests, README and these rules. No global installation, no dependency/version changes, no detached servers. Use the existing project commands; report actual exits.\n")
                    File(root, "README.md").writeText("Mobile cart, JavaScript modules, Vite 5.4.21. npm install installs project dependencies. npm test uses only Node standard library. npm run build builds dist/. npm run dev starts the preview at http://127.0.0.1:$port/ in a visible terminal. This fixture provides isolated empty npm configuration and cache for all commands. Only modify implementation.\n")
                    File(root, "package.json").writeText("""{"private":true,"type":"module","scripts":{"test":"node tests/cart.test.mjs","build":"vite build","dev":"vite --host 127.0.0.1 --port $port --strictPort"},"devDependencies":{"vite":"5.4.21"}}""")
                    File(root, "index.html").writeText("<!doctype html><html><head><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Cart</title></head><body><main id=app></main><script type=module src=/src/main.js></script></body></html>\n")
                    File(root, "src/cart.js").writeText("export function total(items) { return items.reduce((sum, item) => sum + item.price, 0); }\n")
                    File(root, "src/main.js").writeText("import { total } from './cart.js';\nconst items = [{price: 12.5, quantity: 2}, {price: 3, quantity: 2}];\ndocument.querySelector('#app').innerHTML = `<h1>Draft</h1><p>合计: ¥\u0024{total(items).toFixed(2)}</p>`;\n")
                    File(root, "tests/cart.test.mjs").writeText("import assert from 'node:assert/strict';\nimport {readFileSync} from 'node:fs';\nimport {total} from '../src/cart.js';\nassert.equal(total([{price:12.5,quantity:2},{price:3,quantity:2}]),31);\nassert.equal(total([]),0);\nassert.equal(total([{price:8,quantity:0}]),0);\nassert.ok(readFileSync(new URL('../src/main.js',import.meta.url),'utf8').includes('手机购物清单'));\nconsole.log('CART_TESTS_PASSED');\n")
                }
                val protectedNames = listOf("AGENTS.md", "README.md", "package.json", "tests/cart.test.mjs", "index.html")
                val originals = withContext(Dispatchers.IO) { protectedNames.associateWith { hash(File(root, it)) } }
                val baseline = w.runtime.execute(npmEnvironment + "npm test", id, w.state.value.settings, timeoutMillis = 30000)
                assertNotEquals("Fixture must fail before the model acts", 0, baseline.exitCode)
                val versions = w.runtime.execute(npmEnvironment + "node --version && npm --version", id, w.state.value.settings, timeoutMillis = 30000)
                assertEquals(0, versions.exitCode)
                instrumentation.sendStatus(0, Bundle().apply { putString("viteRuntime", versions.output.trim().take(200)) })
                val recorded = ProjectTasks(isolated, w.projects::directory, { pid, script, timeout, output ->
                    w.runtime.execute(npmEnvironment + script, pid, w.state.value.settings, timeoutMillis = minOf(timeout, 180000), output = output)
                }, { true }, {})
                tasks = recorded
                main { recorded.select(id) }; await { !recorded.state.value.loading }
                for (command in listOf(ProjectCommand("test", "npm test", timeoutSeconds = 120), ProjectCommand("build", "npm run build", timeoutSeconds = 120))) {
                    main { recorded.save(command) }
                    await { !recorded.state.value.saving && recorded.state.value.commands.any { it == command } }
                }
                val controller = withContext(Dispatchers.Main) {
                    AiController(isolated, w.projects::directory, { _, _ -> false }, { _, _ -> error("Use recorded commands") }, {}, {},
                        config = AiConfigStore(context), permissionStore = AiPermissionStore(isolated), history = AiHistoryStore(File(owned, "history")),
                        projectCommandContext = { pid -> recorded.modelContext(pid, ai!!.state.value.policy.permission(AiTool.ReadFile) == AiPermission.Allow) },
                        longCommand = { pid, script, timeout, output -> recorded.observed(pid, script, timeout, output,
                            conversation = ai!!.state.value.conversationId, turn = ai!!.state.value.turn, source = "ai",
                            inspectSource = { ai!!.state.value.policy.permission(AiTool.ReadFile) == AiPermission.Allow }) },
                        commandLog = recorded::logPage, terminalTool = { pid, tool, args ->
                            val guarded = if (tool == AiTool.StartTerminal) JSONObject(args.toString()).put("command", npmEnvironment + args.getString("command")) else args
                            w.executeAiTerminal(pid, tool, guarded)
                        }).also { ai = it; it.select(id) }
                }
                await { controller.state.value.configured && !controller.state.value.permissionsLoading && !controller.state.value.skillsLoading }
                main { controller.setPolicy(AiPolicy(AiPermission.Ask, setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.UpdatePlan).associateWith { AiPermission.Allow })) }
                await { !controller.state.value.permissionsSaving }
                main { assertTrue(controller.send("修好这个手机购物清单：金额必须按单价乘数量汇总，页面主标题显示“手机购物清单”。自行定位实现，遵守项目规则，不修改测试或项目配置。安装所需的项目依赖，用已配置的项目命令完成测试与构建，然后在可见项目终端运行开发服务，并实际请求页面和页面模块确认修改已提供。服务在你结束后继续运行。最后列出修改文件、实际验证命令及退出码、可访问地址和未验证项，不要让我提供路径或端口。")) }
                var observedRequests = -1
                withTimeout(480000) {
                    while (controller.state.value.active) {
                        if (observedRequests != controller.state.value.requests.size) {
                            observedRequests = controller.state.value.requests.size
                            instrumentation.sendStatus(0, Bundle().apply { putString("viteProgress", "phase=${controller.state.value.phase}; requests=$observedRequests; tools=${controller.state.value.toolCount}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}") })
                        }
                        if (controller.state.value.approval != null) { approvals++; main { controller.approve(AiDecision.TaskTool) }; delay(100) }
                        // The retained diagnostics list itself is capped; use the actual task counter.
                        if (controller.state.value.round > 32) { main { controller.stop() }; error("Acceptance-only cost guard reached") }
                        delay(50)
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("viteResults", "phase=${controller.state.value.phase}; failure=${controller.state.value.failureKind}; diagnostics=${controller.state.value.requests.map { it.diagnostic }}; events=${controller.state.value.events.map { "${it.tool}:${it.status}:${it.exitCode}" }}") })
                assertEquals("Real model phase; failure=${controller.state.value.failureKind}", AiPhase.Completed, controller.state.value.phase)
                withContext(Dispatchers.IO) { originals.forEach { (name, digest) -> assertEquals(name, digest, hash(File(root, name))) } }
                val source = withContext(Dispatchers.IO) { ProjectSourceEvidence(w.projects::directory).capture(id, currentCoroutineContext()) }
                val runs = withContext(Dispatchers.IO) { recorded.runs(id) }
                for (kind in listOf("test", "build")) {
                    val run = runs.firstOrNull { it.kind == kind } ?: error("Model did not run configured $kind command")
                    assertEquals(kind, 0, run.exit); assertEquals(ProjectVerification.Unchanged, run.verification(source))
                    assertTrue(withContext(Dispatchers.IO) { recorded.log(id, run.id).isNotBlank() })
                }
                assertTrue(withContext(Dispatchers.IO) { File(root, "dist/index.html").isFile })
                val terminal = w.terminal.sessions.single { it.projectId == id }
                assertTrue("Development server must outlive model completion", terminal.session.isRunning)
                suspend fun get(path: String): String = withContext(Dispatchers.IO) {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500); socket.soTimeout = 5000
                        socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
                        socket.getInputStream().bufferedReader().readText()
                    }
                }
                val page = get("/"); val module = get("/src/main.js")
                assertTrue(page.contains("200 OK") && page.contains("/src/main.js"))
                assertTrue(module.contains("200 OK") && module.contains("手机购物清单"))
                assertTrue("Model must check the served module", controller.state.value.events.any {
                    it.tool == AiTool.RunCommand && it.exitCode == 0 && it.target.contains("http") &&
                        it.target.contains("/src/main.js") && it.output.contains("手机购物清单")
                })
                val independentlyChecked = w.runtime.execute(npmEnvironment + "npm test && npm run build", id, w.state.value.settings, timeoutMillis = 120000)
                assertEquals("Independent post-model verification", 0, independentlyChecked.exitCode)
                val restored = withContext(Dispatchers.IO) { AiHistoryStore(File(owned, "history")).load(id).state!! }
                assertEquals(controller.state.value.timeline, restored.timeline)
                val changed = withContext(Dispatchers.IO) { controller.checkpointChanges(controller.state.value.checkpointId!!) }
                assertTrue(changed.any { it.path == "src/cart.js" }); assertTrue(changed.any { it.path == "src/main.js" })
                val report = controller.state.value.timeline.last { it.role == AiRole.Assistant }.text
                // Only this generated project: no request headers/config/history bodies or user project data.
                // Retain the public handoff for human review; keyword assertions cannot judge report honesty.
                val audit = JSONObject().put("fixture", "mobile-cart-vite-5.4.21").put("runtime", versions.output.trim())
                    .put("report", AiLogRedactor("").clean(report)).put("independentTestBuildExit", independentlyChecked.exitCode)
                    .put("commands", JSONArray(runs.map { run -> JSONObject().put("run_id", run.id).put("kind", run.kind)
                        .put("script", AiLogRedactor("").clean(run.script)).put("status", run.status).put("exit", run.exit ?: JSONObject.NULL)
                        .put("source_verification", run.verification(source).name) }))
                withContext(Dispatchers.IO) {
                    File(context.cacheDir, "ui-review/live-vite-audit.json").apply { parentFile!!.mkdirs() }.writeText(audit.toString())
                }
                for (fact in listOf("cart.js", "main.js", "npm test", "npm run build", "http://127.0.0.1:$port"))
                    assertTrue("Final report missing required fixture fact: $fact", report.contains(fact))
                val requests = controller.state.value.requests
                instrumentation.sendStatus(0, Bundle().apply { putString("viteAcceptance", "phase=${controller.state.value.phase}; requests=${requests.size}; tools=${controller.state.value.toolCount}; approvalScopes=$approvals; inputTokens=${requests.sumOf { it.inputTokens ?: 0 }}; outputTokens=${requests.sumOf { it.outputTokens ?: 0 }}; elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}; buildExit=0; testExit=0; sourceUnchanged=true; http=200; moduleContent=true; terminalAlive=true; historyReload=true") })
            } finally {
                withContext(NonCancellable) {
                    ai?.let { controller -> main { controller.stop() }; await { !controller.state.value.active } }
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
}

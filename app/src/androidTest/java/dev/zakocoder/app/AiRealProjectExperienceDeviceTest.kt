package dev.zakocoder.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit real-service acceptance. Opt in with -e allowRealModelApi true; never a CI smoke test.
 * Uses the existing application and model, creates and retains exactly one new project.
 * Does not read configuration files, keys, raw HTTP requests or any existing project's source.
 */
class AiRealProjectExperienceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val redactor = AiLogRedactor("")
    private lateinit var evidence: File
    private val checks = JSONArray()
    private val decisions = JSONArray()
    private var ownedProject: String? = null
    private var ownedConversation: String? = null
    private var originalProject: String? = null
    private var originalConversation: String? = null
    private var outcome = "not_started"

    private suspend fun await(label: String, test: () -> Boolean) {
        try { withTimeout(45000) { while (!test()) delay(100) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private suspend fun screenshot(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(evidence, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { bitmap.recycle() }
    }
    private suspend fun snapshot(workbench: Workbench) {
        val state = workbench.ai.state.value
        // Explicit fields only: never serialize AiState, model profiles, requests or endpoint details.
        val report = JSONObject().put("projectId", ownedProject).put("conversationId", ownedConversation)
            .put("originalProjectId", originalProject).put("originalConversationId", originalConversation)
            .put("model", state.model).put("outcome", outcome).put("phase", state.phase.name)
            .put("active", state.active).put("turn", state.turn).put("round", state.round)
            .put("failureKind", state.failureKind?.name).put("error", redactor.clean(state.error))
            .put("contextCompactions", state.contextCompactions).put("toolCount", state.toolCount)
            .put("checks", checks).put("decisions", decisions)
            .put("timeline", JSONArray(state.timeline.map { entry -> JSONObject()
                .put("role", entry.role.name).put("turn", entry.turn).put("toolId", entry.toolId)
                .put("text", redactor.clean(entry.text)).put("reasoning", redactor.clean(entry.reasoning)) }))
            .put("tools", JSONArray(state.events.map { event -> JSONObject()
                .put("id", event.id).put("tool", event.tool?.wire).put("status", event.status.name)
                .put("target", redactor.clean(event.target)).put("reason", redactor.clean(event.reason))
                .put("input", redactor.clean(event.input).take(24000))
                .put("output", redactor.clean(event.output).takeLast(32000)).put("exitCode", event.exitCode)
                .put("started", event.started).put("finished", event.finished)
                .put("durationMs", event.started?.let { start -> (event.finished ?: System.currentTimeMillis()) - start }) }))
            .put("changes", JSONArray(state.changes.map { change -> JSONObject()
                .put("path", change.path).put("turn", change.turn)
                .put("beforeChars", change.before?.text?.length).put("afterChars", change.after.text.length) }))
            .put("requests", JSONArray(state.requests.map { request -> JSONObject()
                .put("id", request.id).put("round", request.round).put("status", request.status)
                .put("elapsedMs", request.elapsed).put("inputTokens", request.inputTokens)
                .put("outputTokens", request.outputTokens).put("cachedTokens", request.cachedTokens)
                .put("complete", request.complete).put("failure", request.failure?.name) }))
        withContext(Dispatchers.IO) { File(evidence, "experience.json").writeText(report.toString(2)) }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && (node.text?.toString()?.lines()?.contains(label) == true ||
                    node.contentDescription?.toString()?.lines()?.contains(label) == true)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.firstNotNullOfOrNull(::walk)
    }
    private suspend fun openAssistant() {
        val navigation = context.getString(R.string.app_navigation_open)
        await("Real navigation menu") { find(navigation) != null }
        var menu = requireNotNull(find(navigation))
        while (!menu.isClickable && menu.parent != null) menu = menu.parent
        assertTrue("Open navigation drawer", menu.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val label = context.getString(R.string.nav_ai)
        await("Real navigation assistant entry") { find(label) != null }
        var node = requireNotNull(find(label))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Assistant navigation click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await("Real assistant page") { find(context.getString(R.string.ai_more)) != null }
    }
    private suspend fun scriptsMatch(root: File): Boolean = withContext(Dispatchers.IO) {
        scripts.all { (name, expected) ->
            val file = File(root, name)
            file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.parentFile == root.canonicalFile &&
                file.readText().replace("\r\n", "\n").trim() == expected.trim()
        }
    }
    private suspend fun commandAllowed(event: AiToolEvent, root: File): Boolean {
        if (event.tool != AiTool.RunCommand) return false
        val command = runCatching { JSONObject(event.input).getString("command").trim() }.getOrNull() ?: return false
        if (command in setOf("pwd", "ls", "ls -la", "g++ --version", "command -v g++", "which g++")) return true
        return command in setOf("bash build.sh", "bash test.sh", "./dungeon --self-test", compileCommand) && scriptsMatch(root)
    }
    private suspend fun verify(workbench: Workbench, projectId: String, name: String, command: String,
        expected: List<String> = emptyList()) {
        val started = SystemClock.elapsedRealtime()
        val result = workbench.runtime.execute(command, projectId, workbench.state.value.settings, timeoutMillis = 60000)
        checks.put(JSONObject().put("name", name).put("command", command).put("exitCode", result.exitCode)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("output", redactor.clean(result.output)))
        snapshot(workbench)
        assertEquals("$name real exit code", 0, result.exitCode)
        for (marker in expected) assertTrue("$name expected output marker $marker", marker in result.output)
    }

    @Test(timeout = 900000) fun createCompileTestAndPlayRealCppDungeon() = runBlocking {
        assertEquals("Real paid API requires explicit instrumentation opt-in", "true",
            InstrumentationRegistry.getArguments().getString("allowRealModelApi"))
        assertEquals("Acceptance must use the existing production application", "dev.zakocoder.app", context.packageName)
        val workbench = (context.applicationContext as ZakoApplication).workbench
        evidence = File(context.cacheDir, "ui-real-experience/${System.currentTimeMillis()}").apply { mkdirs() }
        await("Existing application ready") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading }
        assertFalse("Do not interrupt the user's AI task", workbench.ai.state.value.active)
        assertFalse("Do not interrupt an installation or other operation", workbench.state.value.busy)
        assertFalse("Do not interrupt project commands", workbench.projectTasks.state.value.busy)
        assertFalse("Do not interrupt Git", workbench.git.state.value.busy)
        assertFalse("Do not interrupt history restoration", workbench.ai.state.value.restoring)
        assertTrue("Existing Ubuntu is required; do not install implicitly", workbench.state.value.installed)
        assertTrue("Existing selected model must be an approved GPT Sol model",
            workbench.ai.models.state.value.catalog.active?.model in setOf("gpt-6.1-sol", "gpt-6-sol"))
        originalProject = workbench.state.value.selectedId
        originalConversation = workbench.ai.state.value.conversationId
        var ownTaskStarted = false
        try {
            withTimeout(840000) {
                val title = "C++小游戏验收${System.currentTimeMillis()}"
                withContext(Dispatchers.Main) { workbench.create(title) }
                await("Unique new project registered") {
                    !workbench.state.value.busy && workbench.state.value.project?.name == title &&
                        workbench.state.value.selectedId != originalProject
                }
                val projectId = requireNotNull(workbench.state.value.selectedId).also { ownedProject = it }
                val root = withContext(Dispatchers.IO) { workbench.projects.directory(projectId) }
                // Read/write stays inside the fresh project. Commands remain explicitly reviewed below.
                withContext(Dispatchers.IO) {
                    val allowed = setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles,
                        AiTool.WriteFile, AiTool.MakeDirectory, AiTool.UpdatePlan, AiTool.ReadCommandLog)
                    AiPermissionStore(context).save(projectId, AiPolicy(default = AiPermission.Deny,
                        overrides = allowed.associateWith { AiPermission.Allow } + (AiTool.RunCommand to AiPermission.Ask)))
                }
                withContext(Dispatchers.Main) { workbench.ai.select(projectId) }
                await("New project permissions loaded") {
                    workbench.ai.state.value.projectId == projectId && !workbench.ai.state.value.permissionsLoading &&
                        !workbench.ai.state.value.skillsLoading && workbench.ai.state.value.configured
                }
                val initialConversation = workbench.ai.state.value.conversationId
                withContext(Dispatchers.Main) { workbench.ai.conversation() }
                await("Fresh conversation created") { !workbench.ai.state.value.permissionsLoading &&
                    workbench.ai.state.value.conversationId != initialConversation && workbench.ai.state.value.turn == 0 }
                ownedConversation = workbench.ai.state.value.conversationId
                assertTrue(workbench.ai.state.value.model in setOf("gpt-6.1-sol", "gpt-6-sol"))
                verify(workbench, projectId, "compiler-preflight", "g++ --version")
                instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                warmDeviceActivity()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activity.setContent { ZakoCoderApp(workbench) }
                    }
                    openAssistant()
                    screenshot("00-new-real-project")
                    withContext(Dispatchers.Main) { assertTrue("Single real user task accepted", workbench.ai.send(prompt)) }
                    ownTaskStarted = true
                    outcome = "in_progress"
                    val seen = mutableSetOf<String>()
                    val approved = mutableSetOf<String>()
                    var lastSaved = 0L
                    withTimeout(720000) {
                        while (true) {
                            val state = workbench.ai.state.value
                            assertEquals("AI remains in owned project", projectId, state.projectId)
                            assertEquals("AI remains in owned conversation", ownedConversation, state.conversationId)
                            val stage = when {
                                state.approval != null -> "approval"
                                state.reasoningEntryId != null -> "thinking"
                                state.events.any { it.status == AiToolStatus.Running } -> "tool-running"
                                state.phase == AiPhase.Receiving -> "streaming"
                                else -> state.phase.name.lowercase()
                            }
                            if (seen.size < 14 && seen.add(stage)) screenshot("stage-$stage")
                            state.approval?.let { approval ->
                                val id = requireNotNull(approval.eventId)
                                if (approved.add(id)) {
                                    val event = state.events.single { it.id == id }
                                    val allow = commandAllowed(event, root)
                                    decisions.put(JSONObject().put("eventId", id).put("allowed", allow)
                                        .put("reason", if (allow) "Exact local build/test command and verified scripts" else "Outside approved local acceptance commands"))
                                    withContext(Dispatchers.Main) { workbench.ai.approve(allow) }
                                }
                            }
                            if (SystemClock.elapsedRealtime() - lastSaved > 5000) {
                                snapshot(workbench); lastSaved = SystemClock.elapsedRealtime()
                            }
                            if (!state.active && state.phase != AiPhase.Idle) break
                            delay(200)
                        }
                    }
                    snapshot(workbench)
                    assertEquals("Actual task must complete; details retained on failure", AiPhase.Completed, workbench.ai.state.value.phase)
                    assertTrue("Real API requests were made", workbench.ai.state.value.requests.isNotEmpty())
                    assertTrue("Actual C++ source generated", withContext(Dispatchers.IO) { File(root, "main.cpp").isFile })
                    assertTrue("User-facing build and play instructions generated", withContext(Dispatchers.IO) {
                        File(root, "README.md").let { it.isFile && it.length() > 0 }
                    })
                    assertTrue("Generated build/run/test scripts match the authorized local commands", scriptsMatch(root))
                    screenshot("completed-before-independent-validation")
                    verify(workbench, projectId, "independent-build", "bash build.sh")
                    verify(workbench, projectId, "independent-tests", "bash test.sh", listOf("SELF_TEST_OK", "GOODBYE"))
                    verify(workbench, projectId, "interactive-movement-wall-inventory-quit",
                        "printf 'i\\na\\nd\\nq\\n' | ./dungeon", listOf("DUNGEON_READY", "INVENTORY", "BLOCKED", "MOVED", "GOODBYE"))
                    verify(workbench, projectId, "interactive-treasure-victory",
                        "printf 'd\\nd\\n' | ./dungeon", listOf("DUNGEON_READY", "VICTORY"))
                    outcome = "real_build_tests_and_play_passed"
                    screenshot("completed-verified")
                }
            }
        } catch (error: Throwable) {
            outcome = "failed:${error.javaClass.simpleName}"
            if (ownTaskStarted && workbench.ai.state.value.projectId == ownedProject &&
                workbench.ai.state.value.conversationId == ownedConversation && workbench.ai.state.value.active)
                withContext(NonCancellable + Dispatchers.Main) { workbench.ai.stop() }
            throw error
        } finally {
            // Deliberately retain the user's new playable project, conversation and project-specific policy.
            // Do not stopAll(), restore an unrelated task, clear data or delete any existing project.
            withContext(NonCancellable) { snapshot(workbench) }
        }
    }

    companion object {
        private const val compileCommand = "g++ -std=c++17 -O2 -Wall -Wextra -pedantic main.cpp -o dungeon"
        private val scripts = linkedMapOf(
            "build.sh" to "#!/usr/bin/env bash\nset -eu\n$compileCommand\n",
            "run.sh" to "#!/usr/bin/env bash\nset -eu\nbash build.sh\nexec ./dungeon\n",
            "test.sh" to "#!/usr/bin/env bash\nset -eu\nbash build.sh\n./dungeon --self-test\nprintf 'q\\n' | ./dungeon\n"
        )
        private val prompt = """
            请在当前这个全新项目里从零实现并验证一个真正能玩的 C++17 纯终端回合制地牢寻宝小游戏。
            只操作当前项目，不访问其他项目、网络、Android 屏幕、SSH、MCP或终端会话；不安装依赖。
            使用现有 g++，源码为 main.cpp，不依赖第三方库；提供 README.md 和 build.sh、run.sh、test.sh。
            游戏用 w/a/s/d 移动、i 查看背包、q 退出，有墙壁、生命、金币、怪物和宝藏，输入与 EOF 要稳健。
            为便于真实交互验收，初始位置(1,1)，左边是墙，向右两步的(3,1)为宝藏；随机因素不要阻碍这条基础路径。
            显示棋盘和中文帮助，并输出稳定状态标记：启动 DUNGEON_READY；查看背包 INVENTORY；
            撞墙 BLOCKED；有效移动 MOVED；q退出 GOODBYE；抵达宝藏 VICTORY，获胜后退出。
            支持 ./dungeon --self-test，真实测试移动/撞墙/拾取/战斗/胜负/非法输入等核心逻辑，通过打印 SELF_TEST_OK 并返回0，失败返回非0。
            这是一轮自主完成任务，需求已明确，请直接实现，不必反问；先用本地读写工具，不要用命令生成或替换文件。
            以下三个脚本内容请逐字采用（脚本允许末尾换行），以便审批仅批准明确的本地构建与测试：
            build.sh:
            ${scripts.getValue("build.sh")}
            run.sh:
            ${scripts.getValue("run.sh")}
            test.sh:
            ${scripts.getValue("test.sh")}
            写完后必须调用 run_command 执行 bash build.sh 和 bash test.sh，基于真实编译和测试输出修复，不要只描述。
            允许的命令仅为 bash build.sh、bash test.sh、./dungeon --self-test、$compileCommand，以及 pwd、ls、ls -la、g++ --version、command -v g++、which g++。
            其他 shell 命令不会获准；用文件工具查阅和修改源码即可。完成后说明实际验证及运行方式。
        """.trimIndent()
    }
}

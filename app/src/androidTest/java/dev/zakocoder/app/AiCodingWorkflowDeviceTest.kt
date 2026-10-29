package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.workflow.ProjectTasks
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Developer workflow regressions, isolated TLS model and UUID workspace only. No commercial API. */
class AiCodingWorkflowDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(block: () -> Boolean) = withTimeout(25000) { while (!block()) delay(40) }
    private fun wrapper(f: AiDeviceTest.Fixture) = object : ContextWrapper(context) {
        override fun getFilesDir() = File(f.root, "control").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
    }
    private fun call(id: String, name: String, vararg fields: Pair<String, Any>) = AiCall(id, name, JSONObject().apply { fields.forEach { put(it.first, it.second) } }.toString())
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }

    @Test(timeout = 90000) fun overviewScopedSearchLineReadAndTaskToolGrantReduceManualGuidance() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "package.json").writeText("""{"scripts":{"test":"python3 -m unittest discover"},"devDependencies":{"vite":"5.4.21"}}""")
            File(project, "src/auth").mkdirs()
            File(project, "src/auth/login.py").writeText("def login():\n    return False\n")
            File(project, "node_modules").mkdirs(); File(project, "node_modules/noise.js").writeText("SECRET_DEPENDENCY_NOISE")
            File(project, ".env").writeText("NEVER_SEND_SECRET")
            File(project, ".git").writeText("NEVER_READ_VCS_METADATA")
            var ai: AiController? = null; var commands = 0
            try {
                f.enqueue(calls = listOf(call("search", "search_files", "query" to "login", "path" to "src", "glob" to "**/*.py")))
                f.enqueue(calls = listOf(call("read", "read_file", "path" to "src/auth/login.py", "start_line" to 1, "end_line" to 2)))
                f.enqueue(calls = listOf(call("patch", "run_command", "command" to "first command"), call("test", "run_command", "command" to "different verification command")))
                f.enqueue("实际工具完成。")
                main {
                    ai = AiController(wrapper(f), { project }, { _, _ -> false }, { _, _ -> commands++; CommandResult(0, "verified") }, {}, {},
                        f.transport, f.config, history = f.history)
                    ai!!.select("coding-fixture")
                }
                val c = ai!!; ready(c)
                main { assertTrue(c.send("修复登录问题并验证")) }
                await { c.state.value.approval != null }
                assertEquals(0, commands)
                main { c.approve(AiDecision.TaskTool) }
                await { !c.state.value.active }
                assertEquals("", c.state.value.error); assertEquals(2, commands)
                assertTrue(c.state.value.events.all { it.status == AiToolStatus.Succeeded })
                val requests = (1..4).map { f.server.takeRequest() }
                val first = requests.first().body.readUtf8()
                assertTrue("Overview must be in the first request", first.contains("current_project_overview"))
                val messages = JSONObject(first).getJSONArray("messages")
                val metadata = (0 until messages.length()).map { messages.getJSONObject(it).optString("content") }.first { it.contains("current_project_overview") }
                val overviewJson = JSONObject(metadata.trimEnd().substringAfterLast("\n\n"))
                assertEquals("not_inspected", overviewJson.getString("repository_state"))
                val instructions = messages.getJSONObject(0).getString("content")
                assertTrue(instructions.contains("Git repository membership is not automatically verified"))
                assertTrue(instructions.contains("Never initialize Git merely to produce a review"))
                val paths = overviewJson.getJSONArray("paths")
                assertTrue("Nested source directory must be discoverable", (0 until paths.length()).any { paths.getString(it) == "src/auth/" })
                assertTrue("Actual manifest scripts must be supplied", first.contains("unittest discover"))
                assertFalse(first.contains("NEVER_SEND_SECRET")); assertFalse(first.contains("SECRET_DEPENDENCY_NOISE"))
                assertFalse(first.contains("NEVER_READ_VCS_METADATA"))
                assertFalse((0 until paths.length()).any { paths.getString(it).startsWith(".git") })
                val last = requests.last().body.readUtf8()
                assertTrue(last.contains("1: def login()")); assertTrue(last.contains("2:     return False"))
                assertTrue(c.state.value.events.filter { it.tool == AiTool.RunCommand }.all { it.permissionSource == R.string.ai_permission_task_tool_rule })
                // Task grants expire, including for the exact same command in a later user turn.
                f.enqueue(calls = listOf(call("later", "run_command", "command" to "different verification command")))
                f.enqueue("已尊重拒绝。")
                main { c.send("再次验证") }
                await { c.state.value.approval != null }; assertEquals(2, commands)
                main { c.approve(false) }; await { !c.state.value.active }
                assertEquals(2, commands); assertEquals(AiToolStatus.Denied, c.state.value.events.last().status)
                // No automatic manifest/rule reads when reads require approval.
                val deniedReads = AiPolicy(overrides = mapOf(AiTool.ListFiles to AiPermission.Allow, AiTool.ReadFile to AiPermission.Ask))
                val overview = withContext(Dispatchers.IO) { AiProjectOverview(AiProjectFiles { project }).load("p", deniedReads, currentCoroutineContext()) }
                assertFalse(overview.contains("unittest discover"))
                assertEquals("not_inspected", JSONObject(overview).getString("repository_state"))
                val blocked = AiProjectOverview(AiProjectFiles { error("Denied listing must not resolve the workspace") })
                for (permission in listOf(AiPermission.Ask, AiPermission.Deny)) {
                    assertEquals("", withContext(Dispatchers.IO) { blocked.load("p", AiPolicy(permission, emptyMap()), currentCoroutineContext()) })
                }
                assertEquals("", withContext(Dispatchers.IO) { AiProjectOverview(AiProjectFiles { project }).load("p", AiPolicy(AiPermission.Deny, emptyMap()), currentCoroutineContext()) })
            } finally { main { ai?.stop() }; await { ai?.state?.value?.active != true } }
        }
    }

    @Test(timeout = 90000) fun nonRepositoryTwoPathDiffComparesFilesInsteadOfTheirBaselines() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            check(w.state.value.installed && !w.ai.state.value.active && !w.projectTasks.state.value.busy)
            AiDeviceTest.Fixture(context).use { f ->
                val project = withContext(Dispatchers.IO) { w.projects.create(f.token) }
                val root = w.projects.directory(project.id)
                try {
                    withContext(Dispatchers.IO) {
                        File(root, "a.py").writeText("A_ORIGINAL_BODY\n")
                        File(root, "b.py").writeText("B_DIFFERENT_FILE\n")
                    }
                    // Minimal child environment: no user Git config, credential helpers or diff/textconv drivers.
                    val script = """
                        python3 - <<'PY'
                        import os, subprocess
                        from pathlib import Path
                        env = {'PATH':'/usr/bin:/bin', 'LC_ALL':'C', 'GIT_CONFIG_NOSYSTEM':'1',
                               'GIT_CONFIG_GLOBAL':'/dev/null', 'GIT_CEILING_DIRECTORIES':os.getcwd()}
                        assert not Path('.git').exists()
                        print(subprocess.run(['git','--version'],env=env,capture_output=True,text=True,check=True).stdout.strip())
                        repository = subprocess.run(['git','rev-parse','--is-inside-work-tree'],env=env,capture_output=True,text=True)
                        assert repository.returncode != 0, repository.stdout
                        before = {name:Path(name).read_bytes() for name in ['a.py','b.py']}
                        compared = subprocess.run(['git','--no-pager','diff','--no-ext-diff','--no-textconv','--','a.py','b.py'],env=env,capture_output=True,text=True)
                        assert compared.returncode == 1, (compared.returncode, compared.stderr)
                        assert '-A_ORIGINAL_BODY' in compared.stdout
                        assert '+B_DIFFERENT_FILE' in compared.stdout
                        assert 'a/a.py' in compared.stdout and 'b/b.py' in compared.stdout
                        assert before == {name:Path(name).read_bytes() for name in before}
                        assert not Path('.git').exists()
                        print('NON_REPOSITORY_CONFIRMED; TWO_DIFFERENT_FILES_COMPARED; DIFF_EXIT=1; SOURCES_UNCHANGED')
                        PY
                    """.trimIndent()
                    val result = w.runtime.execute(script, project.id, w.state.value.settings, timeoutMillis = 30000)
                    assertEquals(result.output, 0, result.exitCode)
                    assertTrue(result.output.contains("NON_REPOSITORY_CONFIRMED; TWO_DIFFERENT_FILES_COMPARED; DIFF_EXIT=1; SOURCES_UNCHANGED"))
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("gitReviewProof", result.output.trim().take(500)) })
                } finally { withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) } }
            }
        }
    }

    @Test(timeout = 120000) fun largeFailedCommandRetainsFirstErrorAndAiCanSearchItsPersistentLog() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            AiDeviceTest.Fixture(context).use { f ->
                val project = withContext(Dispatchers.IO) { w.projects.create(f.token) }
                val wrapper = wrapper(f)
                val tasks = ProjectTasks(wrapper, w.projects::directory, { id, command, timeout, output -> w.runtime.execute(command, id, w.state.value.settings, timeoutMillis = timeout, output = output) }, { true }, {})
                try {
                    val command = "python3 -c 'import sys; print(\"FIRST_REAL_BUILD_ERROR_42\"); print(\"x\"*700000); print(\"LAST_REAL_BUILD_ERROR_73\"); sys.exit(73)'"
                    val result = tasks.observed(project.id, command, 30000, {}, source = "ai")
                    assertEquals(73, result.exitCode); assertNotNull(result.runId)
                    assertFalse(result.output.contains("FIRST_REAL_BUILD_ERROR_42"))
                    val restored = ProjectTasks(wrapper, w.projects::directory, { _, _, _, _ -> error("No replay") }, { true }, {})
                    val log = JSONObject(restored.logPage(project.id, result.runId!!, query = "FIRST_REAL_BUILD_ERROR_42"))
                    assertTrue(log.getBoolean("query_found")); assertTrue(log.getString("text").contains("FIRST_REAL_BUILD_ERROR_42"))
                    assertEquals(73, log.getInt("exit_code")); assertTrue(log.getBoolean("log_truncated"))
                    assertTrue(log.getLong("original_characters") > 700000)
                    val tail = JSONObject(restored.logPage(project.id, result.runId!!, query = "LAST_REAL_BUILD_ERROR_73"))
                    assertTrue(tail.getBoolean("query_found"))
                    val small = JSONObject(restored.logPage(project.id, result.runId!!, limit = 100, query = "LAST_REAL_BUILD_ERROR_73"))
                    assertTrue(small.getString("text").contains("LAST_REAL_BUILD_ERROR_73"))
                    assertTrue(small.getInt("next_offset") > small.getInt("match_offset"))
                    assertTrue(restored.log(project.id, result.runId!!).contains("middle omitted"))
                    try { restored.logPage("another-project", result.runId!!); fail("Log must belong to selected project") } catch (_: NoSuchElementException) { }
                    var controller: AiController? = null
                    try {
                        f.enqueue(calls = listOf(call("trace", "read_command_log", "run_id" to result.runId!!, "query" to "FIRST_REAL_BUILD_ERROR_42")))
                        f.enqueue("已定位最早错误。")
                        main {
                            controller = AiController(wrapper, w.projects::directory, { _, _ -> false }, { _, _ -> error("No new execution") }, {}, {},
                                f.transport, f.config, history = f.history, commandLog = restored::logPage)
                            controller!!.select(project.id)
                        }
                        val c = controller!!; ready(c)
                        main { c.send("检索上次失败记录 ${result.runId}，找出最早错误") }
                        await { c.state.value.approval != null }; main { c.approve(true) }
                        await { !c.state.value.active }
                        assertEquals("", c.state.value.error)
                        assertTrue(c.state.value.events.single().output.contains("FIRST_REAL_BUILD_ERROR_42"))
                        f.server.takeRequest()
                        assertTrue(f.server.takeRequest().body.readUtf8().contains("FIRST_REAL_BUILD_ERROR_42"))
                    } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true } }
                } finally { withContext(Dispatchers.IO) { tasks.deleteProject(project.id); PrivateFiles.deleteTree(w.projects.directory(project.id)) } }
            }
        }
    }
    @Test(timeout = 60000) fun timedOutCommandReturnsSearchableRunIdWithoutCancellingTask() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val root = File(f.root, "timeout-project").apply { mkdirs() }
            val owned = wrapper(f)
            val tasks = ProjectTasks(owned, { root }, { _, _, _, output ->
                output("UNIQUE_TIMEOUT_CAUSE_123\n")
                withTimeout(40) { delay(1000); CommandResult(0, "unreachable") }
            }, { true }, {})
            var controller: AiController? = null
            try {
                f.enqueue(calls = listOf(call("timeout", "run_command", "command" to "fixture command")))
                f.enqueue("命令超时，已有可查询记录；未声称验证通过。")
                main {
                    controller = AiController(owned, { root }, { _, _ -> false }, { _, _ -> error("Recorded execution only") }, {}, {},
                        f.transport, f.config, history = f.history,
                        longCommand = { id, script, timeout, output -> tasks.observed(id, script, timeout, output, source = "ai") }, commandLog = tasks::logPage)
                    controller!!.select("timeout-fixture")
                }
                val c = controller!!; ready(c)
                main { c.send("运行并报告真实结果") }
                await { c.state.value.approval != null }; main { c.approve(true) }
                await { !c.state.value.active }
                assertEquals(AiPhase.Completed, c.state.value.phase)
                val event = c.state.value.events.single()
                assertEquals(AiToolStatus.Failed, event.status)
                val runId = Regex("run_id=([a-f0-9-]{36})").find(event.output)!!.groupValues[1]
                val log = JSONObject(tasks.logPage("timeout-fixture", runId, query = "UNIQUE_TIMEOUT_CAUSE_123"))
                assertEquals("timeout", log.getString("status")); assertTrue(log.getString("text").contains("UNIQUE_TIMEOUT_CAUSE_123"))
                f.server.takeRequest()
                assertTrue(f.server.takeRequest().body.readUtf8().contains(runId))
            } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true } }
        }
    }

}

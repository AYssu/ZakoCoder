package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.CommandResult
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** TLS fixtures use generated projects/configuration only; never read the user's credentials. */
class AiDiagnosticsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(25000) { while (!condition()) delay(30) }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
    private fun controller(f: AiDeviceTest.Fixture, command: suspend (String, String) -> CommandResult = { _, _ -> error("Unexpected command") }): AiController {
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } }
        val project = File(f.root, "project").apply { mkdirs() }
        return AiController(isolated, { project }, { _, _ -> false }, command, {}, {}, f.transport, f.config, history = f.history)
    }
    private fun request(f: AiDeviceTest.Fixture) = JSONObject(f.server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    @Test(timeout = 120000) fun failedTaskRetainsExecutedToolsPartialReplyAndReasonAcrossReloadWithoutReplay() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                var executions = 0
                val execute: suspend (String, String) -> CommandResult = { _, _ ->
                    executions++; File(f.root, "project/result.py").writeText("print('changed')\n"); CommandResult(0, "WROTE_RESULT")
                }
                lateinit var ai: AiController
                main { ai = controller(f, execute); ai.select("recovery") }; ready(ai)
                f.enqueue(calls = listOf(AiCall("done-once", "run_command", "{\"command\":\"python3 edit.py\",\"reason\":\"修改代码\"}")))
                f.enqueue("已经改了文件，后面的验证", done = false)
                main { ai.send("修改 result.py 并验证结果") }; await { ai.state.value.approval != null }; main { ai.approve(true) }
                await { !ai.state.value.active }
                repeat(2) { request(f) }
                assertEquals(AiPhase.Failed, ai.state.value.phase)
                assertEquals(AiFailure.Protocol, ai.state.value.failureKind)
                assertEquals(1, executions)
                assertTrue(ai.state.value.recoveryNotes.single().contains("WROTE_RESULT"))
                assertTrue(ai.state.value.recoveryNotes.single().contains("已经改了文件"))
                assertTrue(ai.state.value.recoveryNotes.single().contains("status=Succeeded"))
                assertEquals("result.py", ai.state.value.commandChanges.single().path)
                lateinit var restored: AiController
                main { restored = controller(f, execute); restored.select("recovery") }; ready(restored)
                assertEquals(AiFailure.Protocol, restored.state.value.requests.last().failure)
                assertTrue(restored.state.value.error.isNotBlank())
                assertEquals(1, restored.state.value.commandChanges.size)
                f.enqueue("先检查修改后的文件，再继续验证。")
                main { restored.send("继续") }; await { !restored.state.value.active }
                val next = request(f).getJSONArray("messages").toString()
                assertTrue(next.contains("修改 result.py")); assertTrue(next.contains("WROTE_RESULT")); assertTrue(next.contains("Protocol"))
                assertEquals(1, executions); assertEquals(AiPhase.Completed, restored.state.value.phase)
                assertTrue(restored.state.value.recoveryNotes.isEmpty())
                assertEquals(1, f.history.load("recovery").state!!.contextTurns.size)
            }
        }
    }

    @Test(timeout = 120000) fun usageEventsLatencyAndRedactedRequestDetailsArePersisted() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("metrics") }; ready(ai)
                println("METRICS ready")
                val body = buildString {
                    repeat(5) { append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", if (it == 0) "test-only-placeholder" else "内容$it".repeat(200)))))}\n\n") }
                    append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                    append("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45,\"prompt_tokens_details\":{\"cached_tokens\":67}}}\n\n")
                    append("data: [DONE]\n\n")
                }
                f.server.enqueue(MockResponse().setBody(body).throttleBody(1024, 300, TimeUnit.MILLISECONDS))
                main { ai.send("只用于脱敏验证 test-only-placeholder Bearer test-only-placeholder") }
                try { await { ai.state.value.requests.lastOrNull()?.textChars?.let { count -> count > 0 } == true } }
                catch (error: Exception) { throw AssertionError("METRICS phase=${ai.state.value.phase}, active=${ai.state.value.active}, error=${ai.state.value.failureKind}, requests=${ai.state.value.requests.map { listOf(it.status, it.diagnostic, it.events, it.textChars) }}", error) }
                assertTrue(ai.state.value.active)
                await { !ai.state.value.active }
                val sent = request(f); assertTrue(sent.getJSONObject("stream_options").getBoolean("include_usage"))
                val log = ai.state.value.requests.single()
                assertEquals(123L, log.inputTokens); assertEquals(45L, log.outputTokens); assertEquals(67L, log.cachedTokens)
                assertEquals(7, log.events); assertEquals(200, log.status); assertTrue(log.bytes > 0)
                assertNotNull(log.firstText); assertTrue("Elapsed must include first text", log.elapsed >= log.firstText!!); assertTrue("Multiple time buckets: ${log.points}", log.points.size > 1)
                assertFalse(log.encode().toString().contains("test-only-placeholder"))
                assertTrue(log.request.contains("[REDACTED]")); assertTrue(log.response.contains("[REDACTED]"))
                assertEquals(log, f.history.load("metrics").state!!.requests.single())
            }
        }
    }

    @Test(timeout = 120000) fun httpErrorsOutputLimitAndCancellationHaveDistinctDurableCauses() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("errors") }; ready(ai)
                f.server.enqueue(MockResponse().setResponseCode(429).setBody("PRIVATE_ERROR_BODY test-only-placeholder"))
                main { ai.send("第一条") }; await { !ai.state.value.active }; request(f)
                assertEquals(AiFailure.Http, ai.state.value.failureKind); assertTrue(ai.state.value.error.contains("429"))
                assertFalse(ai.state.value.requests.single().encode().toString().contains("PRIVATE_ERROR_BODY"))
                f.enqueue("回复被截断", finish = "length")
                main { ai.send("第二条") }; await { !ai.state.value.active }; request(f)
                assertEquals(AiFailure.OutputLimit, ai.state.value.failureKind)
                f.enqueue("输出一部分然后停止".repeat(100), delayMillis = 100)
                main { ai.send("第三条") }; await { ai.state.value.requests.lastOrNull()?.turn == 3 }
                main { ai.stop() }; await { !ai.state.value.active }
                assertEquals(AiFailure.Cancelled, ai.state.value.failureKind)
                assertEquals(AiFailure.Cancelled, ai.state.value.requests.last().failure)
                val loaded = f.history.load("errors").state!!
                assertEquals(3, loaded.recoveryNotes.size); assertEquals(AiFailure.Cancelled, loaded.failureKind)
            }
        }
    }

    @Test(timeout = 120000) fun timeoutIsClassifiedWithoutProviderBodyAndDefaultHasNoTotalCallDeadline() = runBlocking {
        val field = ChatTransport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val defaults = field.get(ChatTransport()) as okhttp3.OkHttpClient
        assertEquals(0, defaults.callTimeoutMillis); assertEquals(120000, defaults.readTimeoutMillis)
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context, timeoutSeconds = 1).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("timeout") }; ready(ai)
                f.server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("never read"))
                main { ai.send("等待服务") }; await { !ai.state.value.active }
                assertEquals(AiFailure.IdleTimeout, ai.state.value.failureKind)
                assertEquals(AiFailure.IdleTimeout, f.history.load("timeout").state!!.requests.single().failure)
            }
        }
    }

    @Test(timeout = 120000) fun skillsAreExplicitBoundedPersistedAndUseExistingCommandTools() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                val directory = File(f.root, "project/.zakocoder/skills/python-care").apply { mkdirs() }
                File(directory, "SKILL.md").writeText("---\nname: Python care\ndescription: >\n  Precise edits\n  and checks\n---\nUse sed or Python and verify actual files. SKILL_MARKER_42")
                File(f.root, "project/.zakocoder/skills/broken").mkdirs()
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("skills") }; ready(ai)
                assertEquals(2, ai.state.value.skills.count { !it.builtIn }); assertEquals(AiBuiltinSkills.defaults, ai.state.value.enabledSkills)
                val skill = ai.state.value.skills.single { it.valid && !it.builtIn }
                assertTrue(skill.description.contains("and checks"))
                main { ai.enableSkill(skill.path, true) }; delay(200)
                f.enqueue("遵循已启用的技能。")
                main { ai.send("修改前先说明") }; await { !ai.state.value.active }
                val body = request(f)
                assertTrue(body.getJSONArray("messages").toString().contains("SKILL_MARKER_42"))
                val names = body.getJSONArray("tools").let { tools -> (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") } }
                assertTrue("run_command" in names); assertFalse("edit_file" in names)
                val loaded = f.history.load("skills").state!!; assertEquals(AiBuiltinSkills.defaults + skill.path, loaded.enabledSkills)
                assertFalse(loaded.contextTurns.single().contains("SKILL_MARKER_42"))
                File(directory, "SKILL.md").delete()
                main { ai.refreshSkills() }; await { !ai.state.value.skillsLoading }
                main { ai.enableSkill(skill.path, false) }; await { skill.path !in ai.state.value.enabledSkills }
                f.enqueue("继续使用当前任务规则。")
                main { ai.send("继续") }; await { !ai.state.value.active }
                assertFalse(request(f).getJSONArray("messages").toString().contains("SKILL_MARKER_42"))
            }
        }
    }

    @Test(timeout = 120000) fun nestedCommandTimeoutRemainsAnAuditedToolFailureAndReachesTheModel() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f) { _, _ -> withTimeout(30) { delay(1000); CommandResult(0, "unreachable") } }; ai.select("command-timeout") }; ready(ai)
                f.enqueue(calls = listOf(AiCall("timeout", "run_command", "{\"command\":\"long-running-command\",\"reason\":\"验证命令限时\"}")))
                f.enqueue("命令超时，不能声称验证通过。")
                main { ai.send("运行验证") }; await { ai.state.value.approval != null }; main { ai.approve(true) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
                assertTrue(ai.state.value.events.single().output.contains("超时"))
                request(f)
                assertTrue(request(f).getJSONArray("messages").toString().contains("工具执行超时"))
                assertNull(ai.state.value.failureKind)
            }
        }
    }

    @Test(timeout = 210000) fun continuouslyStreamingReplySurvivesTheOldTwoMinuteDeadline() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            AiDeviceTest.Fixture(context, timeoutSeconds = 0).use { f ->
                lateinit var ai: AiController
                main { ai = controller(f); ai.select("long-stream") }; ready(ai)
                val event = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
                val body = event.repeat(3000) + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                f.server.enqueue(MockResponse().setBody(body).throttleBody(1024, 1, TimeUnit.SECONDS))
                main { ai.send("验证持续流超过旧的 120 秒截止，不访问真实模型。") }
                withTimeout(180000) { while (ai.state.value.requests.lastOrNull()?.elapsed?.let { it >= 125000 } != true) { assertTrue(ai.state.value.active); delay(250) } }
                assertTrue("The response must still be active after 125 seconds", ai.state.value.active)
                withTimeout(40000) { while (ai.state.value.active) delay(100) }
                assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
                val log = ai.state.value.requests.single()
                assertTrue(log.elapsed > 125000); assertEquals(3000, log.textChars); assertEquals(3001, log.events)
                assertNull(log.failure); assertTrue(log.points.size > 100)
            }
        }
    }

    @Test(timeout = 120000) fun realUbuntuSedAndPythonChangesAreReviewedIncludingNewAndDeletedFiles() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val w = (context.applicationContext as ZakoApplication).workbench
            await { !w.state.value.loading && !w.state.value.busy }
            AiDeviceTest.Fixture(context).use { f ->
                val project = withContext(Dispatchers.IO) { w.projects.create(f.token) }
                val root = w.projects.directory(project.id)
                lateinit var ai: AiController
                try {
                    withContext(Dispatchers.IO) {
                        File(root, "original.py").writeText("print('before')\n")
                        File(root, "obsolete.txt").writeText("remove this")
                    }
                    val isolated = object : ContextWrapper(context) { override fun getFilesDir() = File(f.root, "control").apply { mkdirs() } }
                    main { ai = AiController(isolated, w.projects::directory, { _, _ -> false }, { _, _ -> error("streaming seam") }, {}, {}, f.transport, f.config,
                        streamingCommand = { id, script, output -> w.runtime.execute(script, id, w.state.value.settings, timeoutMillis = 120000, output = output) }, history = f.history); ai.select(project.id) }
                    ready(ai)
                    val command = "set -eu; sed -i 's/before/after/' original.py; python3 -c \"from pathlib import Path; Path('created.txt').write_text('python edit'); Path('obsolete.txt').unlink()\"; python3 original.py"
                    f.enqueue(calls = listOf(AiCall("command-edit", "run_command", JSONObject().put("command", command).put("reason", "精确编辑并验证实际输出").toString())))
                    f.enqueue("修改完成，验证输出 after。")
                    main { ai.send("用 sed 和 Python 修改并验证") }; await { ai.state.value.approval != null }; main { ai.approve(true) }
                    await { !ai.state.value.active }
                    val event = ai.state.value.events.single()
                    assertEquals(event.output, 0, event.exitCode); assertTrue(event.output.contains("after"))
                    val changes = ai.state.value.commandChanges.associateBy { it.path }
                    assertEquals(setOf("original.py", "created.txt", "obsolete.txt"), changes.keys)
                    assertTrue(changes.getValue("original.py").before!!.contains("before")); assertTrue(changes.getValue("original.py").after!!.contains("after"))
                    assertNull(changes.getValue("created.txt").before); assertNull(changes.getValue("obsolete.txt").after)
                    assertEquals(3, f.history.load(project.id).state!!.commandChanges.size)
                } finally { withContext(Dispatchers.IO) { root.deleteRecursively() } }
            }
        }
    }

    @Test fun interruptedCheckpointRecoveryBoundsAndSnapshotExclusionsAreTruthful() {
        AiDeviceTest.Fixture(context).use { f ->
            val state = AiState(active = true, turn = 1, phase = AiPhase.Executing,
                timeline = listOf(AiEntry("q", "执行并验证", role = AiRole.User, turn = 1), AiEntry("t", toolId = "t", turn = 1)),
                events = listOf(AiToolEvent("t", "c", AiTool.RunCommand, "run_command", "{}", "", "python3 task.py", status = AiToolStatus.Running)),
                requests = listOf(AiRequestLog(request = "{}")))
            f.history.save("checkpoint", state.copy(taskRecovery = recoveryNote(state, AiFailure.Interrupted)))
            val loaded = f.history.load("checkpoint").state!!
            assertEquals(AiFailure.Interrupted, loaded.requests.single().failure)
            assertTrue(loaded.recoveryNotes.single().contains("status=Running")); assertTrue(loaded.recoveryNotes.single().contains("may have side effects"))
            assertTrue(boundedRecovery(List(10) { "x".repeat(24000) }).sumOf(String::length) <= 48000)
            assertTrue(boundedRequests(List(50) { AiRequestLog(request = "x".repeat(240000)) }).sumOf { it.request.length } <= 768000)
            val project = File(f.root, "project").apply { mkdirs() }
            File(project, "normal.py").writeText("print(1)"); File(project, ".env").writeText("not-readable")
            java.nio.file.Files.createSymbolicLink(File(project, "linked").toPath(), File(project, "normal.py").toPath())
            val snapshot = AiProjectFiles { project }.snapshot("project")
            assertEquals(setOf("normal.py"), snapshot.files.keys); assertTrue(snapshot.limited)
            assertTrue("linked" in snapshot.existing); assertTrue(snapshot.listingComplete)
        }
    }
}

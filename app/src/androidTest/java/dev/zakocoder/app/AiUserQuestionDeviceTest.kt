package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Isolated local TLS conversations: no commercial requests, user projects or runtime installation. */
class AiUserQuestionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) {
        while (!condition()) delay(25)
    }
    private fun controller(fixture: AiDeviceTest.Fixture, beforeContext: (suspend (String) -> String)? = null): AiController {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = fixture.root
            override fun getNoBackupFilesDir(): File = File(fixture.root, "no-backup").apply { mkdirs() }
        }
        return AiController(isolated, { fixture.root }, { _, _ -> false },
            { _, _ -> error("This question fixture must never execute commands") }, {}, {},
            fixture.transport, fixture.config, history = fixture.history,
            permissionStore = AiPermissionStore(isolated), projectCommandContext = beforeContext)
    }
    private fun question(id: String = "question-call", options: List<String> = listOf("Kotlin", "Java")) = AiCall(
        id, "ask_user", JSONObject().put("question", "选择开发语言？")
            .put("options", JSONArray(options)).put("reason", "需要用户确认项目方向").toString())
    private suspend fun start(fixture: AiDeviceTest.Fixture): AiController {
        lateinit var ai: AiController
        main { ai = controller(fixture); ai.select("fixture") }
        await { ai.state.value.configured && !ai.state.value.permissionsLoading }
        return ai
    }
    private suspend fun waitForQuestion(ai: AiController) {
        await { !ai.state.value.active && ai.state.value.phase == AiPhase.AwaitingAnswer }
        assertNotNull(ai.state.value.questionContinuation)
        assertNull(ai.state.value.approval)
        assertEquals("", ai.state.value.error)
    }
    private suspend fun persisted(fixture: AiDeviceTest.Fixture, predicate: (AiState) -> Boolean): AiState = withTimeout(20000) {
        var loaded: AiState?
        do {
            loaded = withContext(Dispatchers.IO) { fixture.history.load("fixture").state }
            if (loaded == null || !predicate(loaded)) delay(25)
        } while (loaded == null || !predicate(loaded))
        loaded
    }
    private fun lastRequestMessages(fixture: AiDeviceTest.Fixture, count: Int): JSONArray {
        var body = ""
        repeat(count) {
            body = (fixture.server.takeRequest(5, TimeUnit.SECONDS) ?: error("Missing fixture request")).body.readUtf8()
        }
        return JSONObject(body).getJSONArray("messages")
    }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

    @Test(timeout = 60000) fun optionAnswerResumesOriginalTurnAndToolCallWithoutAnotherUserTurn() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue("先确认开发语言。", listOf(question()))
                fixture.enqueue("已按 Kotlin 继续原任务。")
                main { ai.send("创建原生项目") }
                waitForQuestion(ai)
                val pending = ai.state.value
                val question = pending.questions.single()
                assertEquals(listOf("Kotlin", "Java"), question.options)
                assertEquals("选择开发语言？", question.prompt)
                assertEquals(1, fixture.server.requestCount)
                assertNull(pending.turnTimings.single().finished)
                delay(250)
                assertEquals("Paused tasks must not poll the model", 1, fixture.server.requestCount)
                main { assertTrue(ai.answerQuestion("fixture", pending.conversationId, question.id, "Kotlin")) }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                val complete = ai.state.value
                assertEquals(pending.turn, complete.turn)
                assertEquals(pending.checkpointId, complete.checkpointId)
                assertEquals(pending.turnTimings.single().started, complete.turnTimings.single().started)
                assertNotNull(complete.turnTimings.single().finished)
                assertEquals(1, complete.timeline.count { it.role == AiRole.User })
                assertEquals("Kotlin", complete.questions.single().answer)
                assertNull(complete.questionContinuation)
                val messages = lastRequestMessages(fixture, 2).objects()
                val result = messages.single { it.optString("tool_call_id") == "question-call" }
                assertEquals("tool", result.getString("role"))
                assertTrue(result.getString("content").contains("Kotlin"))
                assertTrue(messages.any { message -> message.optJSONArray("tool_calls")?.objects()
                    ?.any { it.optString("id") == "question-call" } == true })
                persisted(fixture) { it.questions.singleOrNull()?.answer == "Kotlin" && it.questionContinuation == null }
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun customAnswerSurvivesReloadWithoutReplayingCompletedWrite() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val original = start(fixture)
            var restored: AiController? = null
            try {
                fixture.enqueue(calls = listOf(AiCall("write-before-question", "write_file",
                    JSONObject().put("path", "choice.txt").put("content", "initial write\n").toString())))
                fixture.enqueue(calls = listOf(question(options = emptyList())))
                fixture.enqueue("已采用用户自定义方案。")
                main { original.send("先记录计划再询问我") }
                await { original.state.value.approval != null }
                main { original.approve(true) }
                waitForQuestion(original)
                val pending = original.state.value
                val question = pending.questions.single()
                assertTrue(question.options.isEmpty())
                assertEquals("initial write\n", File(fixture.root, "choice.txt").readText())
                persisted(fixture) { it.phase == AiPhase.AwaitingAnswer && it.questionContinuation != null }
                File(fixture.root, "choice.txt").writeText("user edit after pause\n")
                // Fresh controller models process recreation; stop() would explicitly cancel the question.
                main { restored = controller(fixture); restored!!.select("fixture") }
                val ai = restored!!
                await { !ai.state.value.permissionsLoading && ai.state.value.configured }
                assertEquals(AiPhase.AwaitingAnswer, ai.state.value.phase)
                assertFalse(ai.state.value.active)
                assertEquals(question.id, ai.state.value.questions.single().id)
                assertEquals(pending.checkpointId, ai.state.value.checkpointId)
                assertEquals(2, fixture.server.requestCount)
                main { assertTrue(ai.answerQuestion("fixture", pending.conversationId, question.id, "使用 Rust 和自定义目录")) }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                assertEquals("user edit after pause\n", File(fixture.root, "choice.txt").readText())
                assertEquals(1, ai.state.value.events.count { it.callId == "write-before-question" })
                assertEquals(pending.turn, ai.state.value.turn)
                assertEquals(pending.turnTimings.single().started, ai.state.value.turnTimings.single().started)
                val messages = lastRequestMessages(fixture, 3).objects()
                assertEquals(1, messages.count { it.optString("tool_call_id") == "write-before-question" })
                assertTrue(messages.single { it.optString("tool_call_id") == "question-call" }
                    .getString("content").contains("使用 Rust 和自定义目录"))
            } finally {
                main { original.select(null); original.stop(); restored?.stop() }
            }
        }
    }

    @Test(timeout = 60000) fun wrongOwnershipBlankOversizedAndDuplicateAnswersNeverResume() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue(calls = listOf(question()))
                fixture.enqueue("继续完成。")
                main { ai.send("询问用户") }
                waitForQuestion(ai)
                val pending = ai.state.value
                val id = pending.questions.single().id
                main {
                    assertFalse(ai.answerQuestion("other-project", pending.conversationId, id, "Kotlin"))
                    assertFalse(ai.answerQuestion("fixture", "wrong-conversation", id, "Kotlin"))
                    assertFalse(ai.answerQuestion("fixture", pending.conversationId, "stale-id", "Kotlin"))
                    assertFalse(ai.answerQuestion("fixture", pending.conversationId, id, " \n\t"))
                    assertFalse(ai.answerQuestion("fixture", pending.conversationId, id, "x".repeat(4097)))
                }
                assertEquals(1, fixture.server.requestCount)
                assertEquals(AiPhase.AwaitingAnswer, ai.state.value.phase)
                assertNull(ai.state.value.questions.single().answer)
                main {
                    assertTrue(ai.answerQuestion("fixture", pending.conversationId, id, "Java"))
                    assertFalse(ai.answerQuestion("fixture", pending.conversationId, id, "Kotlin"))
                }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                assertEquals(2, fixture.server.requestCount)
                assertEquals("Java", ai.state.value.questions.single().answer)
                main { assertFalse(ai.resumeQuestion("fixture", pending.conversationId, id)) }
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun stopPausedQuestionPersistsCancellationAndReloadCannotResumeIt() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            var restored: AiController? = null
            try {
                fixture.enqueue(calls = listOf(question()))
                main { ai.send("等待我回答") }
                waitForQuestion(ai)
                val pending = ai.state.value
                val id = pending.questions.single().id
                main { ai.stop() }
                await { ai.state.value.phase == AiPhase.Cancelled && ai.state.value.questionContinuation == null }
                val saved = persisted(fixture) { it.questions.singleOrNull()?.cancelled == true && it.questionContinuation == null }
                assertFalse(saved.active)
                assertNotNull(saved.turnTimings.single().finished)
                main { restored = controller(fixture); restored!!.select("fixture") }
                val fresh = restored!!
                await { !fresh.state.value.permissionsLoading && fresh.state.value.configured }
                assertTrue(fresh.state.value.questions.single().cancelled)
                assertNull(fresh.state.value.questionContinuation)
                main {
                    assertFalse(fresh.answerQuestion("fixture", pending.conversationId, id, "Kotlin"))
                    assertFalse(fresh.resumeQuestion("fixture", pending.conversationId, id))
                }
                assertEquals(1, fixture.server.requestCount)
            } finally { main { ai.select(null); ai.stop(); restored?.stop() } }
        }
    }

    @Test(timeout = 60000) fun mixedQuestionBatchIsRejectedBeforeAnyWriteOrApproval() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue(calls = listOf(AiCall("mixed-write", "write_file",
                    JSONObject().put("path", "must-not-exist.txt").put("content", "unsafe mixed batch").toString()), question()))
                // If rejection is returned as tool errors, let the model finish without issuing another call.
                fixture.enqueue("本批调用无效，未修改文件。")
                main { ai.send("测试混合调用") }
                await { !ai.state.value.active }
                assertNull(ai.state.value.approval)
                assertFalse(File(fixture.root, "must-not-exist.txt").exists())
                assertTrue(ai.state.value.questions.isEmpty())
                assertNull(ai.state.value.questionContinuation)
                assertTrue(ai.state.value.events.none { it.callId == "mixed-write" && it.status == AiToolStatus.Succeeded })
                assertTrue(ai.state.value.events.any { it.status == AiToolStatus.Failed } || ai.state.value.error.isNotEmpty())
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun savedAnswerResumesAfterPreflightFailureAndReloadWithoutAnsweringAgain() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            var failContinuation = false
            lateinit var original: AiController
            var restored: AiController? = null
            main {
                original = controller(fixture) {
                    if (failContinuation) error("fixture continuation preflight failure")
                    ""
                }
                original.select("fixture")
            }
            await { original.state.value.configured && !original.state.value.permissionsLoading }
            try {
                fixture.enqueue(calls = listOf(question()))
                main { original.send("确认后继续构建") }
                waitForQuestion(original)
                val pending = original.state.value
                val id = pending.questions.single().id
                persisted(fixture) { it.phase == AiPhase.AwaitingAnswer && it.questionContinuation != null }
                main {
                    failContinuation = true
                    assertTrue(original.answerQuestion("fixture", pending.conversationId, id, "Kotlin"))
                }
                await { !original.state.value.active && original.state.value.error.isNotBlank() }
                val saved = persisted(fixture) { it.questions.singleOrNull()?.answer == "Kotlin" && it.questionContinuation != null }
                assertEquals(1, fixture.server.requestCount)
                assertNull(saved.turnTimings.single().finished)
                fixture.enqueue("已恢复已保存的回答并完成。")
                main { restored = controller(fixture); restored!!.select("fixture") }
                val fresh = restored!!
                await { fresh.state.value.configured && !fresh.state.value.permissionsLoading }
                assertEquals("Kotlin", fresh.state.value.questions.single().answer)
                assertEquals(AiPhase.AwaitingAnswer, fresh.state.value.phase)
                main {
                    assertFalse(fresh.answerQuestion("fixture", pending.conversationId, id, "Java"))
                    assertTrue(fresh.resumeQuestion("fixture", pending.conversationId, id))
                }
                await { !fresh.state.value.active && fresh.state.value.phase == AiPhase.Completed }
                assertEquals(pending.turn, fresh.state.value.turn)
                assertEquals(pending.checkpointId, fresh.state.value.checkpointId)
                val messages = lastRequestMessages(fixture, 2).objects()
                assertEquals(1, messages.count { it.optString("tool_call_id") == "question-call" })
                assertTrue(messages.single { it.optString("tool_call_id") == "question-call" }.getString("content").contains("Kotlin"))
            } finally { main { original.select(null); original.stop(); restored?.stop() } }
        }
    }

    @Test(timeout = 60000) fun explicitlyDeniedQuestionReturnsToolDenialWithoutPausing() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                main { ai.setPolicy(AiPolicy(overrides = mapOf(AiTool.AskUser to AiPermission.Deny))) }
                await { !ai.state.value.permissionsSaving }
                fixture.enqueue(calls = listOf(question()))
                fixture.enqueue("提问被禁用，已停止询问。")
                main { ai.send("询问开发语言") }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                assertTrue(ai.state.value.questions.isEmpty())
                assertNull(ai.state.value.questionContinuation)
                assertNull(ai.state.value.approval)
                assertEquals(AiToolStatus.Denied, ai.state.value.events.single { it.callId == "question-call" }.status)
                val messages = lastRequestMessages(fixture, 2).objects()
                assertTrue(messages.any { it.optString("tool_call_id") == "question-call" })
                persisted(fixture) { it.phase == AiPhase.Completed && it.questionContinuation == null }
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun omittedOptionsAllowsFreeTextAnswer() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue(calls = listOf(AiCall("free-text-question", "ask_user", JSONObject()
                    .put("question", "项目需要什么名称？").put("reason", "由用户命名项目").toString())))
                fixture.enqueue("已使用用户提供的项目名称。")
                main { ai.send("创建项目并让我命名") }
                waitForQuestion(ai)
                val pending = ai.state.value
                assertTrue(pending.questions.single().options.isEmpty())
                main { assertTrue(ai.answerQuestion("fixture", pending.conversationId, pending.questions.single().id, "便携工作台")) }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.Completed }
                assertEquals("便携工作台", ai.state.value.questions.single().answer)
                assertTrue(lastRequestMessages(fixture, 2).objects().single {
                    it.optString("tool_call_id") == "free-text-question"
                }.getString("content").contains("便携工作台"))
            } finally { main { ai.stop() } }
        }
    }


    @Test(timeout = 60000) fun repeatedStopWhileCancellingCannotInterruptDurableCancellation() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue(calls = listOf(question()))
                main { ai.send("等待答案后执行") }
                waitForQuestion(ai)
                val pending = ai.state.value
                persisted(fixture) { it.questionContinuation != null && it.phase == AiPhase.AwaitingAnswer }
                main {
                    ai.stop()
                    assertTrue(ai.state.value.restoring)
                    ai.stop()
                    ai.stop()
                }
                await { !ai.state.value.restoring && ai.state.value.phase == AiPhase.Cancelled }
                val saved = persisted(fixture) { it.questionContinuation == null && it.questions.singleOrNull()?.cancelled == true }
                assertEquals(AiPhase.Cancelled, saved.phase)
                assertEquals(pending.conversationId, saved.conversationId)
                assertNotNull(saved.turnTimings.single().finished)
                assertEquals(AiToolStatus.Cancelled, saved.events.single { it.callId == "question-call" }.status)
                assertEquals(1, fixture.server.requestCount)
                assertFalse(ai.state.value.historyError)
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 60000) fun missingCheckpointDoesNotPreventDurableQuestionCancellation() = runBlocking<Unit> {
        AiDeviceTest.Fixture(context).use { fixture ->
            val ai = start(fixture)
            try {
                fixture.enqueue(calls = listOf(question()))
                main { ai.send("等我确认后再写代码") }
                waitForQuestion(ai)
                val pending = ai.state.value
                persisted(fixture) { it.questionContinuation != null && it.phase == AiPhase.AwaitingAnswer }
                withContext(Dispatchers.IO) {
                    val checkpoint = File(fixture.root, "history-checkpoints").walkTopDown().single {
                        it.isDirectory && it.name == pending.checkpointId
                    }
                    assertTrue(checkpoint.deleteRecursively())
                }
                main { ai.stop() }
                await { !ai.state.value.restoring && ai.state.value.phase == AiPhase.Cancelled }
                val saved = persisted(fixture) { it.questionContinuation == null && it.questions.singleOrNull()?.cancelled == true }
                assertEquals(AiPhase.Cancelled, saved.phase)
                assertNotNull(saved.turnTimings.single().finished)
                assertTrue(saved.timeline.any { it.text == context.getString(R.string.ai_checkpoint_update_error) })
                assertFalse(ai.state.value.historyError)
                main { assertFalse(ai.resumeQuestion("fixture", pending.conversationId, pending.questions.single().id)) }
                assertEquals(1, fixture.server.requestCount)
            } finally { main { ai.stop() } }
        }
    }

}

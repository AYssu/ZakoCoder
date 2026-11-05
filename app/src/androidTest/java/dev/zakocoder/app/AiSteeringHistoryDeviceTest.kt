package dev.zakocoder.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises complete successful steering transcripts, rather than empty queue metadata alone. */
class AiSteeringHistoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(25000) { while (!predicate()) delay(30) }
    private fun controller(f: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = f.root
            override fun getNoBackupFilesDir() = File(f.root, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${f.token}-$name", mode)
        }
        return AiController(isolated, { f.root }, { _, _ -> false }, { _, _ -> error("No Shell expected") }, {}, {},
            f.transport, f.config, permissionStore = AiPermissionStore(isolated), history = f.history)
    }
    private suspend fun open(f: AiDeviceTest.Fixture): AiController {
        lateinit var ai: AiController
        main { ai = controller(f); ai.select("fixture") }
        await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading }
        assertFalse(ai.state.value.historyError)
        return ai
    }
    private suspend fun roundtripAndContinue(f: AiDeviceTest.Fixture, original: AiController, expectedUser: String) {
        val saved = withContext(Dispatchers.IO) { f.history.load("fixture").state!! }
        assertEquals(original.state.value.contextTurns, saved.contextTurns)
        val transcript = JSONArray(saved.contextTurns.last())
        val supplements = (0 until transcript.length()).map(transcript::getJSONObject).filter { it.opt("zako_steering") == true }
        assertEquals(listOf(expectedUser), supplements.map { it.getString("content") })
        val reopened = open(f)
        try {
            assertEquals(saved.contextTurns, reopened.state.value.contextTurns)
            assertFalse(reopened.state.value.active)
            f.enqueue("继续对话完成")
            main { assertTrue(reopened.send("接着检查，不要重复执行")) }
            await { !reopened.state.value.active }
            assertEquals(AiPhase.Completed, reopened.state.value.phase)
            assertFalse(reopened.state.value.historyError)
            val after = withContext(Dispatchers.IO) { f.history.load("fixture").state!! }
            assertEquals(2, after.contextTurns.size)
            assertEquals(saved.contextTurns.first(), after.contextTurns.first())
        } finally { main { reopened.stop() } }
    }

    @Test(timeout = 90000) fun supplementAfterToolBatchPersistsReloadsAndContinuesWithPairedResults() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = open(f)
            try {
                f.enqueue(calls = listOf(AiCall("write", "write_file", JSONObject().put("path", "a.txt").put("content", "never").toString()),
                    AiCall("second", "write_file", JSONObject().put("path", "b.txt").put("content", "never").toString())))
                f.enqueue("按补充要求停止修改，未运行构建。")
                main { assertTrue(ai.send("创建文件")) }
                await { ai.state.value.approval != null }
                main { assertTrue(ai.enqueue("不修改文件，先检查", AiQueuedKind.Steer)); ai.approve(AiDecision.Reject) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                assertFalse(File(f.root, "a.txt").exists()); assertFalse(File(f.root, "b.txt").exists())
                val transcript = JSONArray(ai.state.value.contextTurns.single())
                val results = (0 until transcript.length()).map(transcript::getJSONObject).filter { it.optString("role") == "tool" }
                assertEquals(setOf("write", "second"), results.map { it.getString("tool_call_id") }.toSet())
                roundtripAndContinue(f, ai, "不修改文件，先检查")
                assertEquals(3, f.server.requestCount)
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 90000) fun supplementArrivingDuringFinalAnswerPersistsIntermediateAnswerAndContinues() = runBlocking {
        AiDeviceTest.Fixture(context).use { f ->
            val ai = open(f)
            try {
                f.enqueue("第一份回答", delayMillis = 80)
                f.enqueue("收到补充，修订回答。")
                main { assertTrue(ai.send("给出检查结果")) }
                // Ensure the first request has left the next-loop steering boundary already.
                await { f.server.requestCount == 1 }
                main { assertTrue(ai.enqueue("再补充验证依据", AiQueuedKind.Steer)) }
                await { !ai.state.value.active }
                assertEquals(AiPhase.Completed, ai.state.value.phase)
                val transcript = JSONArray(ai.state.value.contextTurns.single())
                assertEquals(2, (0 until transcript.length()).map(transcript::getJSONObject).count { it.optString("role") == "assistant" })
                roundtripAndContinue(f, ai, "再补充验证依据")
                assertEquals(3, f.server.requestCount)
                repeat(2) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
                val request = requireNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                assertTrue(request.contains("再补充验证依据"))
            } finally { main { ai.stop() } }
        }
    }

    @Test fun rejectsMalformedSteeringAndMissingOrDuplicateToolResults() = runBlocking(Dispatchers.IO) {
        AiDeviceTest.Fixture(context).use { f ->
            fun user(text: Any = "补充") = JSONObject().put("role", "user").put("content", text)
            fun final() = JSONObject().put("role", "assistant").put("content", "answer")
            fun marked() = user().put("zako_steering", true)
            fun calls() = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject().put("id", "call")))
            fun result() = JSONObject().put("role", "tool").put("tool_call_id", "call").put("content", "done")
            val invalid = listOf(
                listOf(user(), final(), user(), final()),
                listOf(user(), final(), marked().put("zako_steering", "true"), final()),
                listOf(user(), final(), marked().put("zako_steering", false), final()),
                listOf(user(), final(), marked().put("content", JSONArray()), final()),
                listOf(user(), final(), marked().put("content", " "), final()),
                listOf(user(), final(), marked().put("extra", true), final()),
                listOf(user(), calls(), marked(), final()),
                listOf(user(), calls(), result(), result(), marked(), final()),
                listOf(marked(), final()))
            invalid.forEachIndexed { index, messages ->
                val project = "invalid-$index"
                f.history.save(project, AiState(turn = 1, contextTurns = listOf(JSONArray(messages).toString())))
                try { f.history.load(project); fail("Invalid transcript $index must be rejected") }
                catch (_: IllegalArgumentException) { /* fail closed without relaxing tool pairing */ }
            }
        }
    }
}

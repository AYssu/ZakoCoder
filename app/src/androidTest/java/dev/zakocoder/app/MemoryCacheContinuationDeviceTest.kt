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

/** Real controller and TLS wire requests; all memory, source, permissions and history are fixture-owned. */
class MemoryCacheContinuationDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(20)
    }
    private suspend fun start(fixture: AiDeviceTest.Fixture): AiController {
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
        }
        lateinit var ai: AiController
        main {
            ai = AiController(isolated, { File(fixture.root, "source").apply { mkdirs() } },
                { _, _ -> false }, { _, _ -> error("Memory fixture must not execute commands") }, {}, {},
                fixture.transport, fixture.config, history = fixture.history, permissionStore = AiPermissionStore(isolated))
            ai.select("memory-cache")
        }
        ready(ai)
        main { ai.setPolicy(AiPolicy(AiPermission.Allow)) }
        ready(ai)
        return ai
    }
    private suspend fun ready(ai: AiController) = await {
        val state = ai.state.value
        state.configured && !state.active && !state.restoring && !state.permissionsLoading &&
            !state.permissionsSaving && !state.skillsLoading && !ai.models.state.value.loading
    }
    private suspend fun completed(ai: AiController) {
        await { !ai.state.value.active }
        assertEquals(ai.state.value.error, AiPhase.Completed, ai.state.value.phase)
        assertTrue("These requests must not depend on compression", ai.state.value.requests.none { it.compaction })
    }
    private fun request(fixture: AiDeviceTest.Fixture): JSONArray {
        val request = requireNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS)) { "Missing local TLS request" }
        val body = request.body.readUtf8()
        assertFalse("Internal snapshot markers must never reach a provider", body.contains("zako_memory_snapshot"))
        return JSONObject(body).getJSONArray("messages")
    }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun snapshots(messages: JSONArray) = messages.objects().filter {
        it.optString("role") == "user" && it.optString("content").startsWith("Current local memory snapshot,")
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun assertPrefix(first: JSONArray, next: JSONArray) {
        assertTrue(next.length() > first.length())
        repeat(first.length()) { index ->
            assertEquals("Previously sent message $index changed", canonical(first.get(index)), canonical(next.get(index)))
        }
    }

    @Test(timeout = 120_000) fun changedAndDisabledMemoryOnlyAppendSnapshotsWithoutRewritingSentPrefix() = runBlocking<Unit> {
        AiDeviceTest.Fixture(base).use { fixture ->
            val ai = start(fixture)
            try {
                ai.memory.add("Language", "MEMORY_ORIGINAL_SENTINEL use Kotlin", core = true).join()
                assertNull(ai.memory.state.value.error)
                val memory = ai.memory.state.value.entries.single()
                fixture.enqueue("First fixture answer")
                main { assertTrue(ai.send("Explain the preferred language")) }
                completed(ai)
                val first = request(fixture)
                val firstSnapshot = snapshots(first).single()
                assertTrue(firstSnapshot.getString("content").contains("MEMORY_ORIGINAL_SENTINEL"))

                ai.memory.update(memory.id, "Language", "MEMORY_UPDATED_SENTINEL use Java", core = true).join()
                assertNull(ai.memory.state.value.error)
                fixture.enqueue("Second fixture answer")
                main { assertTrue(ai.send("Explain the updated preference")) }
                completed(ai)
                val second = request(fixture)
                assertPrefix(first, second)
                val secondSnapshots = snapshots(second)
                assertEquals(2, secondSnapshots.size)
                assertEquals(canonical(firstSnapshot), canonical(secondSnapshots.first()))
                assertTrue(secondSnapshots.last().getString("content").contains("MEMORY_UPDATED_SENTINEL"))
                assertFalse(secondSnapshots.last().getString("content").contains("MEMORY_ORIGINAL_SENTINEL"))
                assertTrue(secondSnapshots.last().getString("content").contains("supersedes all earlier memory snapshots"))

                ai.memory.setEnabled(memory.id, false).join()
                fixture.enqueue("Third fixture answer")
                main { assertTrue(ai.send("Continue without a saved preference")) }
                completed(ai)
                val third = request(fixture)
                assertPrefix(second, third)
                val thirdSnapshots = snapshots(third)
                assertEquals(3, thirdSnapshots.size)
                assertTrue(thirdSnapshots.last().getString("content").contains("No active memory is supplied"))
                assertFalse(thirdSnapshots.last().getString("content").contains("MEMORY_UPDATED_SENTINEL"))
                assertEquals(3, fixture.server.requestCount)
            } finally { main { ai.stop() } }
        }
    }

    @Test(timeout = 120_000) fun userAnswerKeepsOriginalTaskMemoryAndPairedToolResultWithoutCompression() = runBlocking<Unit> {
        AiDeviceTest.Fixture(base).use { fixture ->
            val ai = start(fixture)
            try {
                ai.memory.add("Style", "MEMORY_TASK_SNAPSHOT_SENTINEL concise Kotlin", core = true).join()
                assertNull(ai.memory.state.value.error)
                val memory = ai.memory.state.value.entries.single()
                val callId = "memory-question-call"
                fixture.enqueue(calls = listOf(AiCall(callId, "ask_user", JSONObject()
                    .put("question", "Choose the next step")
                    .put("options", JSONArray(listOf("Inspect", "Describe")))
                    .put("reason", "Fixture needs a user decision").toString())))
                fixture.enqueue("Continued the same task with the answer")
                main { assertTrue(ai.send("Inspect and ask before continuing")) }
                await { !ai.state.value.active && ai.state.value.phase == AiPhase.AwaitingAnswer }
                val pending = ai.state.value
                val question = pending.questions.single()
                assertNotNull(pending.questionContinuation)
                val before = request(fixture)
                val originalSnapshot = snapshots(before).single()
                assertTrue(originalSnapshot.getString("content").contains("MEMORY_TASK_SNAPSHOT_SENTINEL"))

                // A waiting task owns its existing snapshot even when a later task would see an update.
                ai.memory.update(memory.id, "Style", "MEMORY_LATER_TASK_SENTINEL detailed Java", core = true).join()
                assertNull(ai.memory.state.value.error)
                main { assertTrue(ai.answerQuestion("memory-cache", pending.conversationId, question.id, "Inspect")) }
                completed(ai)
                val after = request(fixture)
                val resumedSnapshot = snapshots(after).single()
                assertEquals(canonical(originalSnapshot), canonical(resumedSnapshot))
                assertFalse(after.toString().contains("MEMORY_LATER_TASK_SENTINEL"))
                val messages = after.objects()
                val callingIndex = messages.indexOfFirst { message -> message.optJSONArray("tool_calls")?.objects()
                    ?.any { it.optString("id") == callId } == true }
                val resultIndex = messages.indexOfFirst { it.optString("tool_call_id") == callId }
                assertTrue("The assistant call must precede its result", callingIndex >= 0 && resultIndex > callingIndex)
                assertEquals("tool", messages[resultIndex].getString("role"))
                assertEquals(1, messages.count { it.optString("tool_call_id") == callId })
                assertTrue(messages[resultIndex].getString("content").contains("Inspect"))
                assertEquals(pending.turn, ai.state.value.turn)
                assertEquals(2, fixture.server.requestCount)
            } finally { main { ai.stop() } }
        }
    }
}

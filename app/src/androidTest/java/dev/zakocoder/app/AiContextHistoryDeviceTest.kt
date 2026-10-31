package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Android JSON/storage, isolated private fixtures; no activity, model or user history. */
class AiContextHistoryDeviceTest {
    @Test fun appendedStateUpdatesRoundTripAfterCompleteToolBatches() = withHistory { store, root ->
        val context = AiContextCache()
        context.update("changes", "actual failed validation; exit=1")
        val firstUpdate = requireNotNull(context.nextMessage())
        context.update("rules", "current scoped rules")
        val secondUpdate = requireNotNull(context.nextMessage())
        val encoded = turn(user("original task"), assistant("call-1"), tool("call-1"), firstUpdate,
            assistant("call-2"), tool("call-2"), secondUpdate, assistant()).toString()
        val state = AiState(turn = 1, contextTurns = listOf(encoded), skillsInitialized = true)
        store.save("roundtrip", state)
        val restored = AiHistoryStore(root).load("roundtrip").state!!
        assertEquals(listOf(encoded), restored.contextTurns)
        assertTrue(restored.contextTurns.single().contains("actual failed validation; exit=1"))
        assertTrue(restored.contextTurns.single().contains("current scoped rules"))
    }

    @Test fun ordinaryLegacyUserPreambleRemainsReadable() = withHistory { store, root ->
        val encoded = turn(user("original task"), user("recovery evidence"), assistant()).toString()
        store.save("legacy", AiState(turn = 1, contextTurns = listOf(encoded)))
        assertEquals(listOf(encoded), AiHistoryStore(root).load("legacy").state!!.contextTurns)
    }

    @Test fun stateMarkerCannotBypassPendingCallsOrOrdinaryUserOrdering() = withHistory { store, root ->
        val state = AiContextCache().apply { update("rules", "reference only") }.snapshotMessage()
        val invalid = listOf(
            // One of two calls remains pending.
            turn(user("task"), assistant("a", "b"), tool("a"), state, tool("b"), assistant()),
            // Ordinary user content remains disallowed after a completed assistant/tool batch.
            turn(user("task"), assistant("a"), tool("a"), user("unmarked extra turn"), assistant()),
            // A final assistant answer cannot be followed by even a marked state message.
            turn(user("task"), assistant(), state, assistant()),
            // Synthetic envelopes carry no tool-call or hidden metadata fields.
            turn(user("task"), assistant("a"), tool("a"), JSONObject(state.toString()).put("tool_calls", JSONArray()), assistant()),
            // A completed user turn must still end in an assistant message.
            turn(user("task"), assistant("a"), tool("a"), state),
            // Only the immediate complete tool-batch boundary admits an appended update.
            turn(user("task"), assistant("a"), tool("a"), state, state, assistant())
        )
        invalid.forEachIndexed { index, messages ->
            val project = "invalid-$index"
            store.save(project, AiState(turn = 1, contextTurns = listOf(messages.toString())))
            try { AiHistoryStore(root).load(project); fail("Invalid history $index must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)
    private fun assistant(vararg calls: String) = JSONObject().put("role", "assistant").put("content", "answer").apply {
        if (calls.isNotEmpty()) put("tool_calls", JSONArray(calls.map { id -> JSONObject().put("id", id)
            .put("type", "function").put("function", JSONObject().put("name", "list_files").put("arguments", "{}")) }))
    }
    private fun tool(id: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "actual result")
    private fun turn(vararg messages: JSONObject) = JSONArray(messages.toList())
    private inline fun withHistory(block: (AiHistoryStore, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "context-history-${UUID.randomUUID()}")
        try { block(AiHistoryStore(root), root) }
        finally { root.deleteRecursively() }
    }
}

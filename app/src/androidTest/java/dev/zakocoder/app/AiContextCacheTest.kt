package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiContextCacheTest {
    @Test fun repeatedSamplingDoesNotRewriteOrAppendTheConversationPrefix() {
        val state = AiContextCache(mapOf("rules" to "rule A"))
        val messages = JSONArray().put(message("system", "fixed system"))
            .put(message("user", "fixed skills/rules/project overview"))
        state.update("commands", "[Recent recorded command evidence; sampled_at=100; scope]\n[]")
        state.update("changes", changes(100, 0))
        messages.put(requireNotNull(state.nextMessage()))
        val previous = messages.toString()
        state.update("commands", "[Recent recorded command evidence; sampled_at=200; scope]\n[]")
        state.update("changes", changes(200, 0))
        assertNull(state.nextMessage())
        assertEquals(previous, messages.toString())
        assertTrue(state.snapshotMessage().getString("content").contains("sampled_at=200"))
    }

    @Test fun changedEvidenceAppendsOnlyChangedSectionsAndPreservesOldBytes() {
        val state = AiContextCache(mapOf("rules" to "initial rules"))
        state.update("changes", changes(100, 0))
        state.update("commands", "recorded failed build exit=1")
        val previous = requireNotNull(state.nextMessage()).toString()
        state.update("changes", changes(200, 1))
        val update = requireNotNull(state.nextMessage()).getString("content")
        assertTrue(update.contains("SECTION changes"))
        assertTrue(update.contains("\"observed_changed_items\":1"))
        assertFalse(update.contains("SECTION commands"))
        assertFalse(update.contains("SECTION rules"))
        assertTrue(previous.contains("recorded failed build exit=1"))
        assertTrue(state.snapshotMessage().getString("content").contains("recorded failed build exit=1"))
    }

    @Test fun ruleRevocationAndMcpRemovalAreDeliveredRatherThanHidden() {
        val state = AiContextCache(mapOf("rules" to "old scoped rules", "mcp" to "server tools"))
        state.update("rules", "[Automatic project-rule loading disabled]")
        state.update("mcp", "")
        val update = requireNotNull(state.nextMessage()).getString("content")
        assertTrue(update.contains("Automatic project-rule loading disabled"))
        assertTrue(update.contains("SECTION mcp [0 chars]"))
        assertNull(state.nextMessage())
    }

    @Test fun timestampLookingRuleContentIsNotSilentlyIgnored() {
        val state = AiContextCache(mapOf("rules" to "sampled_at=100"))
        state.update("rules", "sampled_at=200")
        assertNotNull(state.nextMessage())
    }

    @Test fun completeToolPairsRemainAvailableToCompactionAfterStateAppend() {
        val assistant = message("assistant", "").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "call1").put("type", "function").put("function", JSONObject().put("name", "read_file").put("arguments", "{}"))))
        val result = message("tool", "actual file evidence").put("tool_call_id", "call1")
        val state = AiContextCache()
        state.update("rules", "latest scoped rules")
        val messages = JSONArray().put(message("system", "system")).put(assistant).put(result)
            .put(requireNotNull(state.nextMessage()))
        val tail = contextTail(contextBeforeStateUpdates(messages), 1)
        assertEquals(2, tail.size)
        assertSame(assistant, tail[0])
        assertSame(result, tail[1])
        assertEquals(4, messages.length())
        assertTrue(state.snapshotMessage().getString("content").contains("latest scoped rules"))
    }

    private fun changes(time: Long, count: Int) = JSONObject().put("current_task_changes", JSONObject()
        .put("sampled_at_ms", time).put("observed_changed_items", count)).toString()
    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content", text)
}

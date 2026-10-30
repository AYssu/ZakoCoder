package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AiTokenBudgetDeviceTest {
    @Test fun legacyCharacterConfigurationMigratesWithoutPretendingCharactersAreTokens() {
        val old = JSONObject().put("maxContextChars", 192000).put("autoCompress", false)
            .put("compressionThresholdPercent", 80).put("summaryMaxChars", 6000)
        val migrated = decodeContextOptions(old)
        assertEquals(64000, migrated.contextWindowTokens); assertFalse(migrated.autoCompress)
        assertEquals(80, migrated.compressionThresholdPercent)
        assertEquals(128000, decodeContextOptions(JSONObject().put("maxContextChars", 96000)).contextWindowTokens)
        assertEquals(1048576, decodeContextOptions(old.put("contextWindowTokens", 1048576)).contextWindowTokens)
    }
    @Test fun schemaAndOutputReserveCountTowardTheActualRequestBudget() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
        val config = AiConfig(parameters = AiParameters(maxTokens = 32768), context = AiContextOptions(contextWindowTokens = 8192))
        assertFalse(tokenBudget(config, messages, JSONArray()).fits)
        val normal = config.copy(parameters = AiParameters(), context = AiContextOptions(contextWindowTokens = 1048576))
        val tools = JSONArray().put(JSONObject().put("description", "tool schema ".repeat(10000)))
        assertTrue(tokenBudget(normal, messages, tools).inputTokens > tokenBudget(normal, messages, JSONArray()).inputTokens + 30000)
        assertEquals(8192, tokenBudget(normal, messages, tools).outputReserveTokens)
    }
    @Test fun historyKeepsMoreThanEightTurnsAndNinetySixMessagesAcrossRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "token-history-${UUID.randomUUID()}")
        try {
            val turns = (1..60).map { index -> JSONArray()
                .put(JSONObject().put("role", "user").put("content", "task $index"))
                .put(JSONObject().put("role", "assistant").put("content", "answer $index " + "a".repeat(10000))).toString() }
            val state = AiState(turn = 60, contextTurns = turns, estimatedContextTokens = 210000, contextWindowTokens = 1048576)
            AiHistoryStore(root).save("project", state)
            val restored = AiHistoryStore(root).load("project").state!!
            assertEquals(turns, restored.contextTurns); assertEquals(0, restored.contextCompactions)
            assertEquals(210000L, restored.estimatedContextTokens); assertEquals(1048576, restored.contextWindowTokens)
            val messages = JSONArray(); turns.forEach { encoded -> val turn = JSONArray(encoded); repeat(turn.length()) { messages.put(turn.getJSONObject(it)) } }
            val budget = tokenBudget(AiConfig(context = AiContextOptions(contextWindowTokens = 1048576)), messages, JSONArray())
            assertFalse(budget.shouldCompress); assertTrue(budget.fits)
        } finally { root.deleteRecursively() }
    }
}

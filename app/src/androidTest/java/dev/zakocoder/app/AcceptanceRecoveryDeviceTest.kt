package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiHistoryStore
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiTool
import dev.zakocoder.app.ai.AiToolStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Read-only, explicitly selected acceptance history; never loads provider configuration. */
class AcceptanceRecoveryDeviceTest {
    @Test fun completedPaidCacheTurnRetainsAppendedEvidenceAcrossStoreReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Existing cache acceptance evidence is opt-in", arguments.getString("verifyCacheRecovery") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "dev.zakocoder.app")
        val projectId = arguments.getString("acceptanceProjectId").orEmpty()
        val conversationId = arguments.getString("acceptanceConversationId").orEmpty()
        require(UUID.fromString(projectId).toString() == projectId)
        require(UUID.fromString(conversationId).toString() == conversationId)
        withContext(Dispatchers.IO) {
            val state = requireNotNull(AiHistoryStore(context).load(projectId, conversationId).state)
            assertEquals(conversationId, state.conversationId)
            assertEquals(AiPhase.Completed, state.phase)
            assertFalse(state.active)
            assertTrue(state.contextCompactions > 0)
            assertTrue(state.contextSummary.isNotBlank())
            val reads = state.events.filter { it.turn == state.turn && it.tool == AiTool.ReadFile }
            assertEquals(4, reads.size)
            assertTrue(reads.all { it.status == AiToolStatus.Succeeded })
            assertEquals(listOf("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml"),
                reads.map { JSONObject(it.input).getString("path") })
            assertTrue(state.contextTurns.any { it.contains(dev.zakocoder.app.ai.AI_CONTEXT_UPDATE) })
            val reopened = requireNotNull(AiHistoryStore(context).load(projectId, conversationId).state)
            assertEquals(state.contextTurns, reopened.contextTurns)
            assertEquals(state.contextSummary, reopened.contextSummary)
            assertEquals(state.requests.map { it.id }, reopened.requests.map { it.id })
        }
    }

    @Test fun failedPaidBuildRetainsSummaryToolsAndFailureAcrossStoreReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Existing acceptance evidence is opt-in", arguments.getString("verifyAcceptanceRecovery") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "dev.zakocoder.app")
        val projectId = arguments.getString("acceptanceProjectId").orEmpty()
        val conversationId = arguments.getString("acceptanceConversationId").orEmpty()
        require(UUID.fromString(projectId).toString() == projectId)
        require(UUID.fromString(conversationId).toString() == conversationId)
        withContext(Dispatchers.IO) {
            val state = requireNotNull(AiHistoryStore(context).load(projectId, conversationId).state)
            assertEquals(conversationId, state.conversationId)
            assertEquals(AiPhase.Failed, state.phase)
            assertFalse(state.active)
            assertTrue(state.contextCompactions > 0)
            assertTrue(state.contextSummary.contains("GameEngine"))
            assertTrue(state.contextSummary.contains("assembleDebug"))
            assertTrue(state.requests.any { it.status == 402 && it.complete })
            assertTrue(state.recoveryNotes.any { it.contains("HTTP=402") && it.contains("http_status_402") })
            assertTrue(state.events.any { event ->
                event.tool == AiTool.RunCommand && event.status == AiToolStatus.Succeeded && event.exitCode == 0 &&
                    JSONObject(event.input).optString("command").trim() == "gradle --no-daemon :app:assembleDebug"
            })
            assertTrue(state.events.any { it.output.contains("17 deterministic tests passed") })
            // Reopening does not rewrite the stored failure, summarize again, or send a model request.
            val reopened = requireNotNull(AiHistoryStore(context).load(projectId, conversationId).state)
            assertEquals(state.contextSummary, reopened.contextSummary)
            assertEquals(state.recoveryNotes, reopened.recoveryNotes)
            assertEquals(state.requests.map { it.id }, reopened.requests.map { it.id })
        }
    }
}

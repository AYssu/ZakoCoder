package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiConversationForkTest {
    private fun source() = AiState(projectId = "project", conversationId = "original", turn = 3,
        timeline = (1..3).map { AiEntry("entry-$it", "message-$it", role = AiRole.User, turn = it) },
        events = (1..3).map { AiToolEvent("event-$it", "call-$it", AiTool.ReadFile, "read_file", "{}", "", "file", status = AiToolStatus.Succeeded, turn = it) },
        requests = (1..3).map { AiRequestLog(id = "request-$it", turn = it) },
        turnTimings = (1..3).map { AiTurnTiming(it, it.toLong(), it.toLong() + 1) },
        contextTurns = listOf("unmapped future tool calls"), contextSummary = "future secret from turn 3",
        taskPrompt = "latest", taskRecovery = "do not inherit", recoveryNotes = listOf("do not inherit"),
        checkpointId = "source-checkpoint", references = emptyList(), plan = listOf(AiPlanStep("old plan", "pending")))

    @Test fun keepsOnlyHistoryBeforeBoundaryAndLeavesOriginalUntouched() {
        val source = source()
        val fork = source.forkBeforeTurn(2)
        assertNotEquals(source.conversationId, fork.conversationId)
        assertEquals("original", source.conversationId)
        assertEquals(3, source.timeline.size)
        assertEquals(listOf(1), fork.timeline.map { it.turn })
        assertEquals(listOf(1), fork.events.map { it.turn })
        assertEquals(listOf(1), fork.requests.map { it.turn })
        assertEquals(listOf(1), fork.turnTimings.map { it.turn })
        assertEquals(1, fork.turn)
        assertEquals(source.projectId, fork.projectId)
    }

    @Test fun discardsUnmappedContextAndExecutionState() {
        val fork = source().forkBeforeTurn(3)
        assertTrue(fork.contextTurns.isEmpty())
        assertFalse(fork.contextSummary.contains("future secret"))
        assertFalse(fork.contextSummary.contains("message-3"))
        assertTrue(fork.contextSummary.contains("message-1"))
        assertTrue(fork.contextSummary.contains("files have NOT been restored"))
        assertEquals("", fork.taskPrompt)
        assertEquals("", fork.taskRecovery)
        assertTrue(fork.recoveryNotes.isEmpty())
        assertTrue(fork.drafts.isEmpty())
        assertTrue(fork.changes.isEmpty())
        assertTrue(fork.commandChanges.isEmpty())
        assertTrue(fork.plan.isEmpty())
        assertNull(fork.checkpointId)
        assertNull(fork.approval)
        assertFalse(fork.active)
        assertEquals(AiPhase.Idle, fork.phase)
    }

    @Test fun firstTurnCreatesEmptyConversationAndSummariesAreBounded() {
        val empty = source().forkBeforeTurn(1)
        assertTrue(empty.timeline.isEmpty())
        assertEquals("", empty.contextSummary)
        assertEquals(0, empty.turn)
        val huge = source().copy(timeline = List(100) { AiEntry("$it", "x".repeat(8000), role = AiRole.User, turn = 1) })
        val fork = huge.forkBeforeTurn(2)
        assertTrue(fork.contextSummary.length <= 12000)
        assertTrue(fork.historyTrimmed)
    }

    @Test fun incompleteHistoricalToolsNeverBecomePendingActions() {
        val source = source().copy(events = source().events.map { it.copy(status = AiToolStatus.Running) })
        assertEquals(AiToolStatus.Incomplete, source.forkBeforeTurn(2).events.single().status)
    }

    @Test fun rejectsActiveTasksAndInvalidBoundary() {
        for (state in listOf(source().copy(active = true), source().copy(restoring = true))) {
            assertThrows(IllegalArgumentException::class.java) { state.forkBeforeTurn(2) }
        }
        for (turn in listOf(0, 4)) assertThrows(IllegalArgumentException::class.java) { source().forkBeforeTurn(turn) }
    }
}

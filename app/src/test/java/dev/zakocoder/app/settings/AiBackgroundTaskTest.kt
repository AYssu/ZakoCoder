package dev.zakocoder.app.settings

import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiState
import org.junit.Assert.*
import org.junit.Test

class AiBackgroundTaskTest {
    private fun task(active: Boolean = true, phase: AiPhase = AiPhase.Requesting, turn: Int = 1,
        conversation: String = "conversation", approval: Boolean = false, approvalId: String? = null,
        questionId: String? = null, historyError: Boolean = false) = AiBackgroundTask("project", conversation,
        turn, active, phase, 1, 0, approvalId, approval, questionId, historyError)

    @Test fun openingHistoricalCompletionDoesNotAnnounceIt() {
        val tracker = AiBackgroundTaskTracker()
        assertNull(tracker.update(task(false, AiPhase.Completed)))
        assertNull(tracker.update(task(false, AiPhase.Failed)))
        assertNull(tracker.update(task(false, AiPhase.AwaitingAnswer, questionId = "question")))
    }

    @Test fun completionWaitsUntilTaskCleanupAndHistoryFinish() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        assertNull(tracker.update(task(phase = AiPhase.Completed)))
        assertEquals(BackgroundTaskNotice.Completed, tracker.update(task(false, AiPhase.Completed)))
        assertNull(tracker.update(task(false, AiPhase.Completed)))
    }

    @Test fun persistenceFailureCannotAnnounceSuccessfulCompletion() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        assertEquals(BackgroundTaskNotice.Failed,
            tracker.update(task(false, AiPhase.Completed, historyError = true)))
    }

    @Test fun failedTaskNotifiesOnceButUserCancellationDoesNot() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        assertEquals(BackgroundTaskNotice.Failed, tracker.update(task(false, AiPhase.Failed)))
        assertNull(tracker.update(task(false, AiPhase.Failed)))
        tracker.update(task(turn = 2))
        assertNull(tracker.update(task(false, AiPhase.Cancelled, turn = 2)))
    }

    @Test fun switchingHistoricalConversationDoesNotFinishTheObservedTask() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        assertNull(tracker.update(task(false, AiPhase.Completed, conversation = "another")))
    }

    @Test fun eachApprovalNotifiesOnlyOnItsOwnTransition() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        val first = task(phase = AiPhase.Waiting, approval = true, approvalId = "one")
        assertEquals(BackgroundTaskNotice.Approval, tracker.update(first))
        assertNull(tracker.update(first.copy(round = 2)))
        assertEquals(BackgroundTaskNotice.Approval, tracker.update(first.copy(approvalId = "two")))
    }

    @Test fun questionPauseNotifiesOnceAndAnswerResumesTheOriginalTask() {
        val tracker = AiBackgroundTaskTracker()
        tracker.update(task())
        val waiting = task(phase = AiPhase.AwaitingAnswer, questionId = "question")
        assertEquals(BackgroundTaskNotice.Answer, tracker.update(waiting))
        assertNull(tracker.update(waiting.copy(active = false)))
        assertNull(tracker.update(task()))
        assertEquals(BackgroundTaskNotice.Completed, tracker.update(task(false, AiPhase.Completed)))
    }

    @Test fun waitingStatesDoNotRequireCpuButExecutionAndCleanupDo() {
        assertFalse(task(phase = AiPhase.Waiting, approval = true).needsCpu)
        assertFalse(task(phase = AiPhase.AwaitingAnswer).needsCpu)
        assertFalse(task(false, AiPhase.Completed).needsCpu)
        assertTrue(task(phase = AiPhase.Receiving).needsCpu)
        assertTrue(task(phase = AiPhase.Executing).needsCpu)
        assertTrue(task(phase = AiPhase.Stopping).needsCpu)
    }

    @Test fun taskProjectionDoesNotContainUserTextOrNetworkConfiguration() {
        val state = AiState(projectId = "project", active = true, messages = listOf("private source"),
            endpoint = "https://private.example", error = "private credential", taskPrompt = "private prompt")
        val projection = AiBackgroundTask.from(state).toString()
        assertFalse(projection.contains("private"))
        assertFalse(projection.contains("https"))
    }
}

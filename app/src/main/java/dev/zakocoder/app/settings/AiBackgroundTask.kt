package dev.zakocoder.app.settings

import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiState

/** Deliberately excludes messages, model inputs, paths, tool arguments and credentials. */
internal data class AiBackgroundTask(
    val projectId: String?, val conversationId: String, val turn: Int,
    val active: Boolean, val phase: AiPhase, val round: Int, val tools: Int,
    val approvalId: String?, val hasApproval: Boolean, val questionId: String?, val historyError: Boolean,
) {
    companion object {
        fun from(state: AiState) = AiBackgroundTask(state.projectId, state.conversationId, state.turn,
            state.active, state.phase, state.round, state.toolCount, state.approval?.eventId,
            state.approval != null, state.questionContinuation?.questionId, state.historyError)
    }
    val identity get() = Triple(projectId, conversationId, turn)
    val needsCpu get() = active && !hasApproval && phase !in setOf(AiPhase.AwaitingAnswer, AiPhase.Waiting)
}

internal enum class BackgroundTaskNotice { Completed, Failed, Approval, Answer }

/** Observe live transitions only: opening historical conversations must never announce old work. */
internal class AiBackgroundTaskTracker {
    private var observed: Triple<String?, String, Int>? = null
    private var last: AiBackgroundTask? = null

    fun update(task: AiBackgroundTask): BackgroundTaskNotice? {
        if (task.active && task.projectId != null) observed = task.identity
        val previous = last
        last = task
        if (observed != task.identity || task.projectId == null) return null
        val notice = when {
            task.hasApproval && (previous?.identity != task.identity || previous.hasApproval != true || previous.approvalId != task.approvalId) -> BackgroundTaskNotice.Approval
            task.phase == AiPhase.AwaitingAnswer && task.questionId != null &&
                (previous?.identity != task.identity || previous.phase != AiPhase.AwaitingAnswer || previous.questionId != task.questionId) -> BackgroundTaskNotice.Answer
            !task.active && previous?.identity == task.identity && previous.active &&
                task.phase == AiPhase.Completed -> if (task.historyError) BackgroundTaskNotice.Failed else BackgroundTaskNotice.Completed
            !task.active && previous?.identity == task.identity && previous.active &&
                task.phase == AiPhase.Failed -> BackgroundTaskNotice.Failed
            else -> null
        }
        if (!task.active && task.phase !in setOf(AiPhase.AwaitingAnswer)) observed = null
        return notice
    }
}

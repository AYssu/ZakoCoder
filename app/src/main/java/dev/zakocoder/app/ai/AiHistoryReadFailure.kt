package dev.zakocoder.app.ai

/** Classification only; no raw file contents, paths or exception messages reach the UI. */
data class AiHistoryProblem(val conversationId: String?, val reason: Reason) {
    enum class Reason { Corrupt, Unsupported }
}

internal class AiHistoryReadFailure(val problem: AiHistoryProblem) : IllegalArgumentException(problem.reason.name)

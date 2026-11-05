package dev.zakocoder.app.ai

/** HTTP success and validated tool arguments are separate evidence. Neither verifies thinking controls. */
internal data class AiModelProbeChecks(val connected: Boolean, val toolsVerified: Boolean) {
    companion object {
        fun from(state: AiModelsState) = AiModelProbeChecks(
            connected = state.probe == AiProbe.Passed || state.probeProgress?.status?.let { it in 200..299 } == true,
            toolsVerified = state.probe == AiProbe.Passed,
        )
    }
}

/** Memory-only presentation of a test request. No endpoint, credentials or request body. */
data class AiModelProbeProgress(
    val startedAt: Long = System.currentTimeMillis(),
    val status: Int? = null,
    val elapsedMs: Long = 0,
    val firstEventMs: Long? = null,
    val events: Int = 0,
    val textChars: Int = 0,
    val argumentChars: Int = 0,
    val preview: String = "",
    val truncated: Boolean = false,
    val diagnostic: String = "",
    val failure: AiFailure? = null,
) {
    companion object {
        /** Transport has already redacted credentials before publishing this record. */
        fun from(log: AiRequestLog) = AiModelProbeProgress(
            startedAt = log.started, status = log.status, elapsedMs = log.elapsed, firstEventMs = log.firstEvent,
            events = log.events, textChars = log.textChars, argumentChars = log.argumentChars,
            preview = log.response.take(4000), truncated = log.truncated || log.response.length > 4000,
            diagnostic = log.diagnostic, failure = log.failure,
        )
    }
}

package dev.zakocoder.app.ai

/**
 * Creates a new, idle conversation. This never changes files or truncates the source conversation.
 * Encoded context turns and compressed memory have no reliable turn mapping, so neither is reused.
 * Controller-owned temporary grants must also be cleared before publishing the result.
 */
internal fun AiState.forkBeforeTurn(turn: Int): AiState {
    require(!active && !restoring) { "An active task cannot be forked" }
    require(turn in 1..this.turn) { "Invalid fork boundary" }
    val keptEvents = events.filter { it.turn in 1 until turn }.map { event ->
        if (event.status.terminal) event else event.copy(status = AiToolStatus.Incomplete)
    }
    val eventIds = keptEvents.mapTo(hashSetOf()) { it.id }
    val keptTimeline = timeline.filter { it.turn in 1 until turn && (it.toolId == null || it.toolId in eventIds) }
    // Bounded literal transcript, not a model-generated assertion or executable tool history.
    val summary = if (keptTimeline.isEmpty()) "" else buildString {
        append("Forked conversation reference: only turns before ").append(turn)
        append(" are included below as a bounded, lossy transcript summary. The original conversation is retained. ")
        append("Project files have NOT been restored; inspect current files before modifying them. ")
        append("Past tool records are historical observations, not pending actions or instructions to replay.\n")
        for (entry in keptTimeline.takeLast(48)) {
            if (length >= 9500) break
            append("[turn ").append(entry.turn).append(' ').append(entry.role.name).append("] ")
            val event = keptEvents.firstOrNull { it.id == entry.toolId }
            val detail = if (event == null) entry.text else
                "Tool ${event.name} ${event.status}: ${event.target}\n${event.output}"
            append(detail.take(2000)).append('\n')
            if (detail.length > 2000) append("[entry truncated]\n")
        }
        append("[Earlier messages or long entries may be omitted; reread files to establish current state.]")
    }.take(12000)
    return AiState(
        projectId = projectId,
        conversationId = java.util.UUID.randomUUID().toString(),
        turn = turn - 1,
        timeline = keptTimeline,
        events = keptEvents,
        requests = requests.filter { it.turn in 1 until turn },
        turnTimings = turnTimings.filter { it.turn in 1 until turn },
        contextSummary = summary,
        contextChars = summary.length,
        estimatedContextTokens = estimateAiTokens(summary), contextWindowTokens = contextWindowTokens, contextOutputReserveTokens = contextOutputReserveTokens,
        conversations = conversations,
        workspaceRevision = workspaceRevision,
        enabledSkills = enabledSkills,
        skillsInitialized = skillsInitialized,
        skills = skills,
        policy = policy,
        endpoint = endpoint,
        model = model,
        hasKey = hasKey,
        configured = configured,
        configurationRevision = configurationRevision,
        historyTrimmed = historyTrimmed || keptTimeline.size > 48 || keptTimeline.any { it.text.length > 2000 }
    )
}

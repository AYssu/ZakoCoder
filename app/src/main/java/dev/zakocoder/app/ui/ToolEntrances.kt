package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.AiCallDraft
import dev.zakocoder.app.ai.AiToolEvent

/** Keep presentation age across the streamed-arguments → executable-call hand-off. */
internal class ToolEntrances(initial: List<AiToolEvent>) {
    private data class Pending(var draft: AiCallDraft, val born: Long)
    private val known = initial.map { it.id }.toMutableSet()
    private val eventTimes = initial.associate { it.id to 0L }.toMutableMap()
    private val draftTimes = mutableMapOf<Pair<Int, String>, Long>()
    private val pending = mutableListOf<Pending>()
    val events: Map<String, Long> get() = eventTimes
    val drafts: List<AiCallDraft> get() = pending.map { it.draft }
    fun draft(draft: AiCallDraft): Long = draftTimes[draft.index to draft.name]
        ?: pending.firstOrNull { it.draft.index == draft.index && it.draft.name == draft.name }?.born ?: 0L

    fun observe(drafts: List<AiCallDraft>, events: List<AiToolEvent>, now: Long = System.nanoTime()) {
        events.forEach { event -> if (known.add(event.id)) {
            val match = pending.indexOfFirst { it.draft.name == event.name }
            eventTimes[event.id] = if (match >= 0) pending.removeAt(match).born else now
        } }
        val current = drafts.map { it.index to it.name }.toSet()
        if (draftTimes.isEmpty() && current.isNotEmpty()) pending.clear()
        drafts.forEach { draft ->
            val key = draft.index to draft.name
            if (key !in draftTimes) {
                val born = pending.firstOrNull { it.draft.index == draft.index }?.born ?: now
                pending.removeAll { it.draft.index == draft.index }
                draftTimes[key] = born
                pending += Pending(draft, born)
            } else {
                pending.firstOrNull { it.draft.index == draft.index && it.draft.name == draft.name }?.draft = draft
            }
        }
        draftTimes.keys.retainAll(current)
        val retained = events.map { it.id }.toSet()
        known.retainAll(retained)
        eventTimes.keys.retainAll(retained)
        if (pending.size > 32) pending.subList(0, pending.size - 32).clear()
    }
}

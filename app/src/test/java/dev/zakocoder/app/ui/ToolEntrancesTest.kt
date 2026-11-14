package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class ToolEntrancesTest {
    private fun event(id: String, status: AiToolStatus = AiToolStatus.Running) =
        AiToolEvent(id, id, AiTool.ReadFile, "read_file", "{}", "", "$id.kt", status)
    @Test fun streamedArgumentsAndExecutionShareTheOriginalEntranceTime() {
        val registry = ToolEntrances(emptyList())
        val draft = AiCallDraft(0, "read_file", "{}")
        registry.observe(listOf(draft), emptyList(), 100)
        registry.observe(listOf(draft.copy(arguments = "{\"path\":\"a\"}")), emptyList(), 150)
        assertEquals(100L, registry.draft(draft))
        registry.observe(emptyList(), emptyList(), 180)
        assertEquals("Retain the preview until the executable call arrives", 1, registry.drafts.size)
        registry.observe(emptyList(), listOf(event("first")), 200)
        assertEquals(100L, registry.events["first"])
        assertTrue(registry.drafts.isEmpty())
        registry.observe(emptyList(), listOf(event("first", AiToolStatus.Succeeded)), 300)
        assertEquals(100L, registry.events["first"])
    }
    @Test fun repeatedCallsHaveSeparateAgesAndHistoryDoesNotAnimate() {
        val history = event("history", AiToolStatus.Succeeded)
        val registry = ToolEntrances(listOf(history))
        assertEquals(0L, registry.events[history.id])
        val draft = AiCallDraft(0, "read_file", "{}")
        registry.observe(listOf(draft), listOf(history), 100)
        registry.observe(emptyList(), listOf(history, event("one")), 200)
        registry.observe(listOf(draft), listOf(history, event("one")), 300)
        registry.observe(emptyList(), listOf(history, event("one"), event("two")), 400)
        assertEquals(100L, registry.events["one"])
        assertEquals(300L, registry.events["two"])
    }
    @Test fun partialToolNamesKeepOnePreviewAndTheSameEntrance() {
        val registry = ToolEntrances(emptyList())
        registry.observe(listOf(AiCallDraft(0, "", "")), emptyList(), 100)
        registry.observe(listOf(AiCallDraft(0, "read_file", "{}")), emptyList(), 150)
        assertEquals(listOf("read_file"), registry.drafts.map { it.name })
        assertEquals(100L, registry.draft(registry.drafts.single()))
        registry.observe(emptyList(), listOf(event("one")), 200)
        assertEquals(100L, registry.events["one"])
        assertTrue(registry.drafts.isEmpty())
    }

}

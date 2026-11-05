package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiConversationActivityTest {
    private fun event(id: String, turn: Int = 1, status: AiToolStatus = AiToolStatus.Succeeded, tool: AiTool = AiTool.ReadFile) =
        AiToolEvent(id, id, tool, tool.wire, "{}", "", "$id.kt", status = status, turn = turn)
    private fun entry(id: String, turn: Int = 1) = AiEntry(id, toolId = id, turn = turn)

    @Test fun onlyConsecutiveSuccessfulToolsFoldAndKeepOriginalEvents() {
        val reads = listOf(event("read"), event("search", tool = AiTool.SearchFiles))
        val failed = event("failure", status = AiToolStatus.Failed)
        val write = event("write", tool = AiTool.WriteFile, status = AiToolStatus.Waiting)
        val state = AiState(timeline = listOf(entry("read"), entry("search"), entry("failure"), entry("write")), events = reads + failed + write)
        val display = aiDisplayActivity(state, emptySet())
        assertEquals(listOf("read", "failure", "write"), display.entries.map { it.id })
        assertEquals(reads, display.tools.getValue("read"))
        assertFalse(display.tools.containsKey("failure"))
        assertFalse(display.tools.containsKey("write"))
    }

    @Test fun preparationOnlyFoldsKnownMessagesWithinTheSameTurn() {
        val entries = listOf(AiEntry("a", "checkpoint", turn = 1), AiEntry("b", "overview", turn = 1),
            AiEntry("c", "important failure", turn = 1), AiEntry("d", "checkpoint", turn = 2))
        val display = aiDisplayActivity(AiState(timeline = entries), setOf("checkpoint", "overview"))
        assertEquals(listOf("a", "c", "d"), display.entries.map { it.id })
        assertEquals(listOf(entries[0], entries[1]), display.preparation.getValue("a"))
        assertFalse(display.preparation.containsKey("c"))
        assertEquals(listOf(entries[3]), display.preparation.getValue("d"))
    }

    @Test fun readsNeverMergeAcrossTurnsOrAssistantMessages() {
        val events = listOf(event("one"), event("two", turn = 2), event("three", turn = 2))
        val answer = AiEntry("answer", "Answer", role = AiRole.Assistant, turn = 2)
        val display = aiDisplayActivity(AiState(events = events,
            timeline = listOf(entry("one"), entry("two", 2), answer, entry("three", 2))), emptySet())
        assertEquals(4, display.entries.size)
        assertTrue(display.tools.values.all { it.size == 1 })
    }
    @Test fun completedPreparationSharesReadDisclosureWithoutRemovingItsDetails() {
        val prep = AiEntry("prep", "checkpoint", turn = 1)
        val read = event("read")
        val display = aiDisplayActivity(AiState(timeline = listOf(prep, entry("read")), events = listOf(read)), setOf("checkpoint"))
        assertEquals(listOf(prep), display.entries)
        assertEquals(listOf(prep), display.preparation.getValue("prep"))
        assertEquals(listOf(read), display.tools.getValue("prep"))
    }

    @Test fun reasoningKeepsItsPlaceWhilePreparationMovesIntoReadDetails() {
        val prep = AiEntry("prep", "checkpoint", turn = 1)
        val reasoning = AiEntry("thinking", role = AiRole.Assistant, reasoning = "Inspect first", turn = 1)
        val read = event("read")
        val state = AiState(timeline = listOf(prep, reasoning, entry("read")), events = listOf(read))
        val display = aiDisplayActivity(state, setOf("checkpoint"))
        assertEquals(listOf(reasoning, entry("read")), display.entries)
        assertEquals(listOf(prep), display.preparation.getValue("read"))
        val warning = AiEntry("warning", "Important failure", turn = 1)
        val blocked = aiDisplayActivity(state.copy(timeline = listOf(prep, reasoning, warning, entry("read"))), setOf("checkpoint"))
        assertTrue(blocked.entries.contains(prep))
        assertEquals(listOf(prep), blocked.preparation.getValue("prep"))
        assertFalse(blocked.preparation.containsKey("read"))
    }

    @Test fun consecutivePlanReadSearchWriteAndCommandShareOneGroupInOriginalOrder() {
        val events = listOf(event("plan", tool = AiTool.UpdatePlan), event("read"),
            event("search", tool = AiTool.SearchFiles), event("write", tool = AiTool.WriteFile),
            event("command", tool = AiTool.RunCommand))
        val display = aiDisplayActivity(AiState(events = events, timeline = events.map { entry(it.id) }), emptySet())
        assertEquals(listOf(entry("plan")), display.entries)
        assertEquals(events, display.tools.getValue("plan"))
    }

    @Test fun successfulQuestionAndUnknownToolsRemainExplicitAndSeparateGroups() {
        val events = listOf(event("before"), event("question", tool = AiTool.AskUser),
            event("middle", tool = AiTool.WriteFile), event("unknown").copy(tool = null, name = "custom_tool"),
            event("after", tool = AiTool.UpdatePlan))
        val display = aiDisplayActivity(AiState(events = events, timeline = events.map { entry(it.id) }), emptySet())
        assertEquals(events.map { it.id }, display.entries.map { it.id })
        assertEquals(setOf("before", "middle", "after"), display.tools.keys)
        assertTrue(display.tools.values.all { it.size == 1 })
    }

    @Test fun everyNonSuccessfulStatusRemainsOutsideTheCollapsedGroup() {
        val statuses = AiToolStatus.entries.filter { it != AiToolStatus.Succeeded }
        val events = statuses.mapIndexed { index, status -> event("state-$index", status = status, tool = AiTool.RunCommand) }
        val display = aiDisplayActivity(AiState(events = events, timeline = events.map { entry(it.id) }), emptySet())
        assertEquals(events.map { it.id }, display.entries.map { it.id })
        assertTrue(display.tools.isEmpty())
    }

    @Test fun bodyTextAndMismatchedEventTurnNeverDisappearInsideAGroup() {
        val events = listOf(event("before", tool = AiTool.UpdatePlan), event("with-body", tool = AiTool.WriteFile),
            event("mismatch", turn = 2), event("after", tool = AiTool.RunCommand))
        val body = entry("with-body").copy(text = "Important explanation", role = AiRole.Assistant)
        val timeline = listOf(entry("before"), body, entry("mismatch"), entry("after"))
        val display = aiDisplayActivity(AiState(events = events, timeline = timeline), emptySet())
        assertEquals(timeline, display.entries)
        assertEquals(setOf("before", "after"), display.tools.keys)
    }

    @Test fun preparationAttachesToCompletedPlanAndWriteWithoutLosingDetails() {
        val prep = AiEntry("prep", "checkpoint", turn = 1)
        val events = listOf(event("plan", tool = AiTool.UpdatePlan), event("write", tool = AiTool.WriteFile))
        val display = aiDisplayActivity(AiState(events = events, timeline = listOf(prep) + events.map { entry(it.id) }), setOf("checkpoint"))
        assertEquals(listOf(prep), display.entries)
        assertEquals(listOf(prep), display.preparation.getValue("prep"))
        assertEquals(events, display.tools.getValue("prep"))
    }

    @Test fun emptyToolResponseMessagesDoNotSplitActualConsecutiveOperations() {
        val first = event("plan", tool = AiTool.UpdatePlan)
        val second = event("read")
        val empty = AiEntry("transport", role = AiRole.Assistant, turn = 1)
        val state = AiState(timeline = listOf(entry("plan"), empty, entry("read")), events = listOf(first, second))
        val display = aiDisplayActivity(state, emptySet())
        assertEquals(listOf(entry("plan")), display.entries)
        assertEquals(listOf(first, second), display.tools.getValue("plan"))
        assertEquals(3, state.timeline.size)
        val visible = aiDisplayActivity(state.copy(timeline = listOf(entry("plan"), empty.copy(text = "Explanation"), entry("read"))), emptySet())
        assertEquals(3, visible.entries.size)
    }

}

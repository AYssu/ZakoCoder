package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real JSON/AtomicFile history fixtures; no user's conversations or provider settings are read. */
class AiConversationSearchDeviceTest {
    private fun fixture(block: (AiHistoryStore, File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "history-search-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try { block(AiHistoryStore(root), root) } finally { root.deleteRecursively() }
    }

    private fun record(id: String = UUID.randomUUID().toString(), body: String = "目标内容 Gradle failure") = AiState(
        conversationId = id, turn = 1,
        timeline = listOf(AiEntry("question", "如何修复编译？", role = AiRole.User, turn = 1),
            AiEntry("answer", body, role = AiRole.Assistant, turn = 1, reasoning = "公开思考：检查配置"),
            AiEntry("tool-entry", "", toolId = "event", role = AiRole.Notice, turn = 1)),
        events = listOf(AiToolEvent("event", "call", AiTool.RunCommand, "run_command", "{}", "", "project",
            status = AiToolStatus.Failed, output = "Compiler says missingDependency", turn = 1)),
        error = "模型连接中断 retry-later")

    @Test fun findsBodyReasoningToolAndFailureAcrossWorkspacesWithoutSelectingAnything() = fixture { store, root ->
        val first = record()
        val second = record(body = "另一个工作区的目标内容")
        store.save("project-a", first)
        store.save("project-b", second)
        val before = root.walkTopDown().filter(File::isFile).associate { it.relativeTo(root).path to it.readBytes().toList() }
        val hits = store.searchConversations(listOf("project-a", "project-b"), "目标内容")
        assertEquals(setOf("project-a", "project-b"), hits.hits.map { it.projectId }.toSet())
        assertTrue(hits.hits.all { it.entryId == "answer" && it.kind == AiConversationSearchKind.Assistant })
        assertEquals(2, hits.scannedConversations)
        assertFalse(hits.partial)
        assertEquals(AiConversationSearchKind.User, store.searchConversations(listOf("project-a"), "修复编译").hits.single().kind)
        assertEquals(AiConversationSearchKind.Reasoning, store.searchConversations(listOf("project-a"), "公开思考").hits.single().kind)
        val tool = store.searchConversations(listOf("project-a"), "missingDependency").hits.single()
        assertEquals("tool-entry", tool.entryId)
        assertEquals(AiConversationSearchKind.Tool, tool.kind)
        assertEquals(AiConversationSearchKind.Error, store.searchConversations(listOf("project-a"), "retry-later").hits.single().kind)
        val after = root.walkTopDown().filter(File::isFile).associate { it.relativeTo(root).path to it.readBytes().toList() }
        assertEquals("Search must not write history or selected pointers", before, after)
    }

    @Test fun searchesLegacyTranscriptWithoutRequiringNewFieldsOrACompatibleProtocolContext() = fixture { store, root ->
        val state = record()
        store.save("legacy", state)
        val file = root.walkTopDown().single { it.name == "${state.conversationId}.json" }
        val json = JSONObject(file.readText())
        json.remove("plan"); json.remove("questions"); json.remove("queuedMessages")
        repeat(json.getJSONArray("timeline").length()) { index -> json.getJSONArray("timeline").getJSONObject(index).remove("reasoning") }
        // Search reads only displayed transcript; legacy model-wire data need not be decoded.
        json.put("context", "old incompatible model protocol")
        json.put("requests", "secret-model-request-not-searchable")
        json.put("contextSummary", "secret-summary-not-searchable")
        file.writeText(json.toString())
        val found = store.searchConversations(listOf("legacy"), "目标内容")
        assertEquals("answer", found.hits.single().entryId)
        assertFalse(found.partial)
        assertTrue(store.searchConversations(listOf("legacy"), "secret-model-request").hits.isEmpty())
        assertTrue(store.searchConversations(listOf("legacy"), "secret-summary").hits.isEmpty())
    }

    @Test fun deletedMessagesAndRemovedWorkspacesNeverRemainInResults() = fixture { store, _ ->
        val state = record()
        store.save("deleted-conversation", state)
        store.save("deleted-project", record())
        assertEquals(2, store.searchConversations(listOf("deleted-conversation", "deleted-project"), "目标内容").hits.size)
        store.delete("deleted-conversation", state.conversationId)
        store.deleteProject("deleted-project")
        val found = store.searchConversations(listOf("deleted-conversation", "deleted-project"), "目标内容")
        assertTrue(found.hits.isEmpty())
        assertFalse(found.partial)
    }

    @Test fun corruptRecordDoesNotHideHealthyNeighborsAndPartialResultsAreExplicit() = fixture { store, root ->
        val good = record()
        val corrupt = record()
        store.save("partial", good)
        store.save("partial", corrupt)
        root.walkTopDown().single { it.name == "${corrupt.conversationId}.json" }.writeText("not JSON")
        val found = store.searchConversations(listOf("partial"), "目标内容")
        assertEquals(good.conversationId, found.hits.single().conversationId)
        assertEquals(1, found.unreadableConversations)
        assertTrue(found.partial)
        val noMatch = store.searchConversations(listOf("partial"), "nothing matches")
        assertTrue(noMatch.hits.isEmpty())
        assertTrue("An incomplete scan cannot be presented as definitive no results", noMatch.partial)
    }

    @Test fun limitBoundsResultsAndBlankQueriesDoNotReadAnyRecord() = fixture { store, root ->
        repeat(4) { store.save("limit", record()) }
        val limited = store.searchConversations(listOf("limit"), "目标内容", 2)
        assertEquals(2, limited.hits.size)
        assertEquals(4, limited.scannedConversations)
        assertTrue(limited.partial)
        root.walkTopDown().filter { it.name.endsWith(".json") }.forEach { it.writeText("broken") }
        assertEquals(AiConversationSearchResult(), store.searchConversations(listOf("limit"), "  "))
    }

    @Test fun cancellationIsCheckedWithinLargeFileReadsAndNotReportedAsCorruption() = fixture { store, _ ->
        val state = AiState(turn = 1, timeline = (0..7).map {
            AiEntry("entry-$it", "x".repeat(50000), role = AiRole.Assistant, turn = 1)
        })
        store.save("large", state)
        var checkpoints = 0
        try {
            store.searchConversations(listOf("large"), "needle", checkCancelled = {
                if (++checkpoints == 8) throw CancellationException("search superseded")
            })
            fail("Cancelled scan must throw")
        } catch (_: CancellationException) { assertEquals(8, checkpoints) }
    }

    @Test fun largeReplayFieldsAreSkippedWhileTheirLegacyTranscriptRemainsSearchable() = fixture { store, root ->
        val state = record()
        store.save("large-replay", state)
        val file = root.walkTopDown().single { it.name == "${state.conversationId}.json" }
        // A valid v1 record may retain a large model context. Search must project the visible
        // transcript instead of loading that replay into the result or treating it as a hit.
        val json = JSONObject(file.readText()).apply { put("context", "opaque-replay-marker-".repeat(500000)) }
        file.writeText(json.toString())
        val found = store.searchConversations(listOf("large-replay"), "目标内容")
        assertEquals("answer", found.hits.single().entryId)
        assertFalse(found.partial)
        assertTrue(store.searchConversations(listOf("large-replay"), "opaque-replay-marker").hits.isEmpty())
    }
}

package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiConversationSearchTest {
    private fun hit(text: String, query: String) = AiConversationSearch.hit("project", "conversation", "entry", "title", 1,
        AiConversationSearchKind.Assistant, text, AiConversationSearch.query(query))

    @Test fun searchesChineseAndCodeCaseInsensitively() {
        assertNotNull(hit("构建失败：Gradle Error", "gradle error"))
        assertNotNull(hit("更新了 src/main/App.kt", "App.kt"))
        assertNull(hit("This is visible reply text", "hidden system prompt"))
        assertNull(hit("anything", "   "))
    }

    @Test fun snippetRetainsLocationAndEmphasizesTheActualMatch() {
        val result = requireNotNull(hit("before ".repeat(50) + "目标词" + " after".repeat(100), "目标词"))
        assertEquals("entry", result.entryId)
        assertTrue(result.snippet.startsWith("…"))
        assertTrue(result.snippet.endsWith("…"))
        assertEquals("目标词", result.snippet.substring(result.matchStart, result.matchEnd))
        assertTrue(result.snippet.length <= 182)
    }

    @Test fun longQueryIsNotCutAndLineBreaksAreSearchableAsSpaces() {
        val query = "a".repeat(190)
        val result = requireNotNull(hit("prefix " + query + " suffix", query))
        assertEquals(query, result.snippet.substring(result.matchStart, result.matchEnd))
        assertNotNull(hit("build\n\t failed", " build   failed "))
        assertThrows(IllegalArgumentException::class.java) { AiConversationSearch.query("q".repeat(201)) }
    }

    @Test fun snippetNeverSplitsAnEmojiAtItsBoundaries() {
        val result = requireNotNull(hit("😀".repeat(80) + "needle" + "😀".repeat(160), "needle"))
        val content = result.snippet.removePrefix("…").removeSuffix("…")
        assertFalse(content.first().isLowSurrogate())
        assertFalse(content.last().isHighSurrogate())
        assertEquals("needle", result.snippet.substring(result.matchStart, result.matchEnd))
    }
}

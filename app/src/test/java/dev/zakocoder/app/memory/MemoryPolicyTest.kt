package dev.zakocoder.app.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    private fun entry(id: String, title: String = "Kotlin", content: String = "Prefer immutable state", core: Boolean = false,
        enabled: Boolean = true, pending: Boolean = false) = AgentMemory(id, title, content, "user", 100, 100, enabled, core, pending)

    @Test fun unconfirmedAndDisabledMemoriesNeverEnterContext() {
        val pending = entry("pending", pending = true, enabled = false)
        val disabled = entry("disabled", enabled = false, core = true)
        val confirmed = entry("confirmed")
        assertEquals(listOf(confirmed), MemoryPolicy.context(listOf(pending, disabled, confirmed), "Kotlin").entries)
    }

    @Test fun coreIsStableAndUnrelatedLongTermEntriesStayOut() {
        val core = entry("core", title = "回复语言", content = "使用中文回答", core = true)
        val web = entry("web", title = "Vue", content = "Use pnpm for Vue projects")
        assertEquals(listOf(core), MemoryPolicy.context(listOf(web, core), "Help build a Java app").entries)
        assertEquals(listOf(web), MemoryPolicy.context(listOf(web, core), "Vue component").relevant)
    }

    @Test fun chineseRecallWorksWithoutWhitespace() {
        val item = entry("cn", title = "构建习惯", content = "小修改只做定向测试，提交前集中构建")
        assertEquals(listOf(item), MemoryPolicy.search(listOf(item), "帮我构建一下项目"))
    }

    @Test fun contextHasStrictTotalAndRelevantCountBounds() {
        val entries = (0..35).map { entry("memory-$it", content = "Kotlin " + "x".repeat(1800), core = it < 3) }
        val snapshot = MemoryPolicy.context(entries, "Kotlin")
        assertTrue(snapshot.asContext().length <= MemoryPolicy.MAX_CONTEXT_CHARS)
        assertTrue(snapshot.relevant.size <= MemoryPolicy.MAX_RELEVANT)
        assertTrue(snapshot.core.size < 3)
        assertEquals(snapshot.entries.map { it.id }.distinct().size, snapshot.entries.size)
    }

    @Test fun emptyQueryOnlyLoadsCore() {
        val core = entry("core", core = true)
        assertEquals(listOf(core), MemoryPolicy.context(listOf(core, entry("other")), "  ").entries)
    }

    @Test fun titleMatchOutranksIncidentalBodyMatch() {
        val incidental = entry("body", title = "Notes", content = "Kotlin")
        val title = entry("title", title = "Kotlin", content = "Coding style")
        assertEquals(listOf(title, incidental), MemoryPolicy.search(listOf(incidental, title), "Kotlin"))
    }

    @Test fun invalidFieldsAndUnconfirmedEnabledEntryAreRejected() {
        val invalid = listOf(entry("../escape"), entry("ok", content = "x".repeat(2001)),
            entry("ok", title = " "), entry("ok", pending = true), entry("ok", content = "null\u0000text"))
        invalid.forEach { memory -> assertThrows(IllegalArgumentException::class.java) { MemoryPolicy.validate(memory) } }
    }

    @Test fun longRequestsBoundWorkAndRetainBothOpeningAndFinalIntent() {
        val query = "Kotlin " + (0..300).joinToString(" ") { "term$it" } + " Rust"
        val terms = MemoryPolicy.queryTerms(query.lowercase())
        assertTrue(terms.size <= MemoryPolicy.MAX_SEARCH_TERMS)
        assertTrue("kotlin" in terms)
        assertTrue("rust" in terms)
        val kotlin = entry("kotlin", content = "Prefer Kotlin for Android")
        val rust = entry("rust", title = "Rust", content = "Use cargo test")
        assertEquals(setOf("kotlin", "rust"), MemoryPolicy.search(listOf(kotlin, rust), query).map { it.id }.toSet())
    }

    @Test fun maximumUnspacedChineseQueryDoesNotCreateThousandsOfScoringTerms() {
        val query = (0 until MemoryPolicy.MAX_QUERY_CHARS).map { (0x4e00 + it).toChar() }.joinToString("")
        val terms = MemoryPolicy.queryTerms(query)
        assertTrue(terms.size <= MemoryPolicy.MAX_SEARCH_TERMS)
        assertTrue(query.take(2) in terms)
        assertTrue(query.takeLast(2) in terms)
        val memory = entry("tail", title = query.takeLast(2), content = "A relevant remembered preference")
        val irrelevant = (0..126).map { entry("unused-$it", title = "Unrelated $it", content = "x".repeat(2000)) }
        assertEquals(listOf(memory), MemoryPolicy.search(irrelevant + memory, query))
    }

}

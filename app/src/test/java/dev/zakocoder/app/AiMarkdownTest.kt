package dev.zakocoder.app

import dev.zakocoder.app.ui.AiMarkdownDocument
import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.junit.Assert.*
import org.junit.Test

class AiMarkdownTest {
    @Test fun parsesCodingReplyAndIncompleteFence() {
        val root = AiMarkdownDocument.parse("# 标题\n\n**加粗** `inline`\n\n> 引用\n\n1. 步骤\n\n```kotlin\nprintln(1)\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |")
        val blocks = AiMarkdownDocument.children(root)
        assertTrue(blocks.any { it is Heading })
        assertTrue(blocks.any { it is BlockQuote })
        assertTrue(blocks.any { it is OrderedList })
        assertTrue(blocks.any { it is TableBlock })
        assertEquals("kotlin", (blocks.first { it is FencedCodeBlock } as FencedCodeBlock).info)
        val pending = AiMarkdownDocument.parse("```python\nprint('incremental')")
        assertTrue(pending.firstChild is FencedCodeBlock)
    }
    @Test fun onlyExplicitExternalWebLinksAreInteractive() {
        listOf("https://example.org/path?q=1", "http://example.org").forEach { assertTrue(AiMarkdownDocument.externalLink(it)) }
        listOf("javascript:alert(1)", "data:text/html,hi", "file:///data/data", "content://provider", "/relative", "https://name:secret@example.org", "https://", "//example.org").forEach { assertFalse(it, AiMarkdownDocument.externalLink(it)) }
        val document = AiMarkdownDocument.parse("<script>alert(1)</script>\n\n![alt](https://example.org/pixel.png)")
        assertTrue(document.firstChild is HtmlBlock)
        assertTrue(document.lastChild.firstChild is Image)
    }
    @Test fun complexMarkupFallsBackToSelectableText() {
        val source = "*x* ".repeat(3000)
        val parsed = AiMarkdownDocument.parse(source)
        assertTrue(parsed.firstChild.firstChild is Text)
        assertEquals(source, (parsed.firstChild.firstChild as Text).literal)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnboundedInput() { AiMarkdownDocument.parse("a".repeat(64001)) }

    @Test fun streamingFencesKeepReceivedLiteralAndClosingOnlyChangesStructure() {
        val parts = listOf("```kotlin\nval emoji = \"😀\"", "```kotlin\nval emoji = \"😀\"\nval x = 2\n", "```kotlin\nval emoji = \"😀\"\nval x = 2\n```\n\n完成")
        parts.forEachIndexed { index, source ->
            val first = AiMarkdownDocument.parse(source).firstChild as FencedCodeBlock
            assertEquals("kotlin", first.info)
            assertTrue(first.literal.startsWith("val emoji = \"😀\""))
            assertFalse(first.literal.contains("```"))
            if (index > 0) assertTrue(first.literal.contains("val x = 2"))
        }
    }

    @Test fun incompleteInlineLinkIsPlainReceivedTextUntilUrlIsComplete() {
        val source = "查看 [接口说明](https://example.org/api"
        val pending = AiMarkdownDocument.parse(source).firstChild
        assertFalse(AiMarkdownDocument.children(pending).any { it is Link })
        assertTrue(AiMarkdownDocument.children(AiMarkdownDocument.parse("$source)").firstChild).any { it is Link })
    }
    @Test fun renderCacheReusesUnchangedTextButDoesNotReuseChangedReplies() {
        val first = "# Cache reentry fixture\n\nOriginal **reply**."
        val parsed = dev.zakocoder.app.ui.AiMarkdownCache.parse(first)
        assertSame(parsed, dev.zakocoder.app.ui.AiMarkdownCache.get(first))
        assertSame(parsed, dev.zakocoder.app.ui.AiMarkdownCache.parse(first))
        val changed = dev.zakocoder.app.ui.AiMarkdownCache.parse(first + " Updated.")
        assertNotSame(parsed, changed)
        assertEquals(" Updated.", (changed.lastChild.lastChild as Text).literal.takeLast(9))
    }
    @Test fun renderCacheEvictsOldDocumentsAndBoundsRetainedText() {
        val prefix = "bounded cache fixture "
        val first = prefix + "old"
        dev.zakocoder.app.ui.AiMarkdownCache.parse(first)
        repeat(13) { dev.zakocoder.app.ui.AiMarkdownCache.parse(prefix + it) }
        assertNull(dev.zakocoder.app.ui.AiMarkdownCache.get(first))
        val long = "a".repeat(63000)
        repeat(4) { dev.zakocoder.app.ui.AiMarkdownCache.parse("$it$long") }
        assertNull(dev.zakocoder.app.ui.AiMarkdownCache.get("0$long"))
        assertNotNull(dev.zakocoder.app.ui.AiMarkdownCache.get("3$long"))
    }
}

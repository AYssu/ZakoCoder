package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AiDiffTest {
    @Test fun distantChangesProduceTwoSmallHunks() {
        val before = (1..2000).map { "source line $it" }
        val after = before.toMutableList().apply { set(9, "changed ten"); set(1899, "changed nineteen hundred") }
        val diff = fileDiff(before.joinToString("\n"), after.joinToString("\n"))
        assertEquals(2, diff.lineSequence().count { it.startsWith("@@") })
        assertTrue(diff.contains("-source line 10\n+changed ten"))
        assertTrue(diff.contains("-source line 1900\n+changed nineteen hundred"))
        assertFalse(diff.contains("source line 1000")); assertTrue(diff.length < 1000)
    }
    @Test fun editsReconstructBothSidesAcrossInsertionsDeletionsRepeatedLinesAndLargeRewrite() {
        val random = Random(42)
        repeat(300) {
            val before = List(random.nextInt(60)) { "line ${random.nextInt(6)}" }
            val after = List(random.nextInt(60)) { "line ${random.nextInt(6)}" }
            val edits = lineEdits(before, after)
            assertEquals(before, edits.filter { it.kind != '+' }.map { it.text })
            assertEquals(after, edits.filter { it.kind != '-' }.map { it.text })
        }
        val old = List(2000) { "old$it" }; val new = List(2000) { "new$it" }
        val edits = lineEdits(old, new)
        assertEquals(old, edits.filter { it.kind != '+' }.map { it.text })
        assertEquals(new, edits.filter { it.kind != '-' }.map { it.text })
    }
    @Test fun emptyFilesIdenticalFilesAndEndOfFileChangesHaveAccurateRanges() {
        assertEquals("", fileDiff("same", "same"))
        assertEquals("@@ -0,0 +1,1 @@\n+new\n", fileDiff("", "new"))
        assertEquals("@@ -1,1 +0,0 @@\n-old\n", fileDiff("old", ""))
        assertTrue(fileDiff("one\ntwo\n", "one\ntwo").contains("-\n"))
    }
}

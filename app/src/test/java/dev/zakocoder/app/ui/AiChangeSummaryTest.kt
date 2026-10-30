package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.AiReview
import org.junit.Assert.assertEquals
import org.junit.Test

class AiChangeSummaryTest {
    @Test fun countsEditedLinesWithoutCountingDiffHeadersOrUnchangedContext() {
        assertEquals(AiLineSummary(2, 1), aiLineSummary(AiReview("file", "same\nold\nend", "same\nnew\nextra\nend")))
    }
    @Test fun countsNewAndDeletedFilesAndEmptyContent() {
        assertEquals(AiLineSummary(2, 0), aiLineSummary(AiReview("file", null, "first\nsecond")))
        assertEquals(AiLineSummary(0, 2), aiLineSummary(AiReview("file", "first\nsecond", null)))
        assertEquals(AiLineSummary(0, 0), aiLineSummary(AiReview("file", null, "")))
    }
    @Test fun permissionAndDirectoryChangesDoNotInventTextChanges() {
        assertEquals(AiLineSummary(0, 0), aiLineSummary(AiReview("file", "same", "same", beforeMode = "644", afterMode = "755")))
        assertEquals(AiLineSummary(0, 0), aiLineSummary(AiReview("dir", "before", "after", directory = true)))
    }
    @Test fun manyEditsMatchBoundedReviewDiff() {
        val before = (1..400).joinToString("\n") { "old-$it" }
        val after = (1..400).joinToString("\n") { "new-$it" }
        assertEquals(AiLineSummary(400, 400), aiLineSummary(AiReview("file", before, after)))
    }
}

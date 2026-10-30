package dev.zakocoder.app.ui

import dev.zakocoder.app.ai.AiReview
import dev.zakocoder.app.ai.lineEdits

/** Counts the same bounded edit script displayed by the review editor, never generated diff headers. */
internal data class AiLineSummary(val added: Int, val removed: Int)

internal fun aiLineSummary(review: AiReview): AiLineSummary {
    if (review.directory || review.before == review.after) return AiLineSummary(0, 0)
    fun lines(value: String?): List<String> = if (value.isNullOrEmpty()) emptyList() else value.split('\n')
    val edits = lineEdits(lines(review.before), lines(review.after))
    return AiLineSummary(edits.count { it.kind == '+' }, edits.count { it.kind == '-' })
}

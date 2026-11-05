package dev.zakocoder.app.ai

import org.json.JSONObject

internal data class AiTextEdit(val oldText: String, val newText: String)

/** All matches refer to the same read baseline; no replacement can match newly inserted text. */
internal object AiTextEdits {
    const val MAX_EDITS = 64

    fun parse(args: JSONObject): List<AiTextEdit> {
        if (args.keys().asSequence().any { it !in setOf("path", "edits", "reason") } || args.opt("path") !is String)
            throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
        val edits = args.optJSONArray("edits") ?: throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
        if (edits.length() !in 1..MAX_EDITS) throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
        return (0 until edits.length()).map { index ->
            val edit = edits.optJSONObject(index) ?: throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
            if (edit.keys().asSequence().toSet() != setOf("old_text", "new_text") ||
                edit.opt("old_text") !is String || edit.opt("new_text") !is String)
                throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
            AiTextEdit(edit.getString("old_text"), edit.getString("new_text"))
        }
    }

    fun apply(original: String, edits: List<AiTextEdit>): String {
        if (edits.size !in 1..MAX_EDITS || edits.any { it.oldText.isEmpty() || it.oldText == it.newText })
            throw AiFileFailure(AiFileFailure.Reason.INVALID_EDITS)
        val matches = edits.map { edit ->
            val start = original.indexOf(edit.oldText)
            if (start < 0) throw AiFileFailure(AiFileFailure.Reason.EDIT_NOT_FOUND)
            // Include overlapping occurrences ("aa" in "aaa") in ambiguity detection.
            if (original.indexOf(edit.oldText, start + 1) >= 0) throw AiFileFailure(AiFileFailure.Reason.EDIT_AMBIGUOUS)
            Triple(start, start + edit.oldText.length, edit.newText)
        }.sortedBy { it.first }
        if (matches.zipWithNext().any { (a, b) -> a.second > b.first })
            throw AiFileFailure(AiFileFailure.Reason.EDIT_OVERLAP)
        val length = original.length.toLong() + matches.sumOf { it.third.length.toLong() - (it.second - it.first) }
        if (length > dev.zakocoder.app.editor.EditorFileStore.MAX_BYTES) throw AiFileFailure(AiFileFailure.Reason.FILE_LIMIT)
        return buildString(length.toInt()) {
            var cursor = 0
            matches.forEach { (start, end, replacement) -> append(original, cursor, start); append(replacement); cursor = end }
            append(original, cursor, original.length)
        }
    }
}

package dev.zakocoder.app.ai

internal data class LineEdit(val kind: Char, val text: String)

/** Myers shortest edit script with bounded edit distance/memory; a large rewrite remains a coarse exact diff. */
internal fun lineEdits(old: List<String>, new: List<String>): List<LineEdit> {
    val trace = mutableListOf<IntArray>()
    for (distance in 0..minOf(old.size + new.size, 256)) {
        val current = IntArray(2 * distance + 1)
        fun previous(k: Int) = trace[distance - 1][k + distance - 1]
        for (k in -distance..distance step 2) {
            var x = when {
                distance == 0 -> 0
                k == -distance || (k != distance && previous(k - 1) < previous(k + 1)) -> previous(k + 1)
                else -> previous(k - 1) + 1
            }
            var y = x - k
            while (x < old.size && y < new.size && old[x] == new[y]) { x++; y++ }
            current[k + distance] = x
            if (x >= old.size && y >= new.size) {
                trace += current
                val edits = mutableListOf<LineEdit>()
                for (d in distance downTo 1) {
                    val diagonal = x - y
                    val previous = trace[d - 1]
                    val previousK = if (diagonal == -d || (diagonal != d && previous[diagonal - 1 + d - 1] < previous[diagonal + 1 + d - 1])) diagonal + 1 else diagonal - 1
                    val previousX = previous[previousK + d - 1]
                    val previousY = previousX - previousK
                    while (x > previousX && y > previousY) { edits += LineEdit(' ', old[--x]); y-- }
                    if (x == previousX) edits += LineEdit('+', new[--y]) else edits += LineEdit('-', old[--x])
                }
                while (x > 0 && y > 0) { edits += LineEdit(' ', old[--x]); y-- }
                while (x > 0) edits += LineEdit('-', old[--x])
                while (y > 0) edits += LineEdit('+', new[--y])
                return edits.asReversed()
            }
        }
        trace += current
    }
    var prefix = 0
    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(old.size, new.size) - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
    return old.take(prefix).map { LineEdit(' ', it) } + old.subList(prefix, old.size - suffix).map { LineEdit('-', it) } +
        new.subList(prefix, new.size - suffix).map { LineEdit('+', it) } + old.takeLast(suffix).map { LineEdit(' ', it) }
}

fun fileDiff(before: String, after: String): String {
    if (before == after) return ""
    val edits = lineEdits(if (before.isEmpty()) emptyList() else before.split('\n'), if (after.isEmpty()) emptyList() else after.split('\n'))
    val ranges = mutableListOf<IntRange>()
    edits.forEachIndexed { index, edit ->
        if (edit.kind != ' ') {
            val start = maxOf(0, index - 3); val end = minOf(edits.lastIndex, index + 3)
            if (ranges.isNotEmpty() && start <= ranges.last().last + 1) ranges[ranges.lastIndex] = ranges.last().first..end
            else ranges += start..end
        }
    }
    val oldOffsets = IntArray(edits.size + 1); val newOffsets = IntArray(edits.size + 1)
    edits.forEachIndexed { index, edit ->
        oldOffsets[index + 1] = oldOffsets[index] + if (edit.kind == '+') 0 else 1
        newOffsets[index + 1] = newOffsets[index] + if (edit.kind == '-') 0 else 1
    }
    return buildString {
        for (range in ranges) {
            val oldCount = oldOffsets[range.last + 1] - oldOffsets[range.first]
            val newCount = newOffsets[range.last + 1] - newOffsets[range.first]
            val oldStart = oldOffsets[range.first] + if (oldCount == 0) 0 else 1
            val newStart = newOffsets[range.first] + if (newCount == 0) 0 else 1
            append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            for (index in range) { append(edits[index].kind); append(edits[index].text); append('\n') }
        }
    }
}

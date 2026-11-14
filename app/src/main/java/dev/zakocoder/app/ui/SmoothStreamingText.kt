package dev.zakocoder.app.ui

/** Presentation-only cursor; source/history, tool approvals and usage never wait for it. */
internal object StreamingTextPacer {
    /** Avoid splitting surrogate pairs, combining marks, emoji modifiers, ZWJ and flags. */
    fun boundary(text: String, requested: Int): Int {
        var end = requested.coerceIn(0, text.length)
        if (end in 1 until text.length && Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) end++
        while (end < text.length) {
            val next = text.codePointAt(end)
            val previous = if (end > 0) text.codePointBefore(end) else -1
            val category = Character.getType(next)
            val attached = category == Character.NON_SPACING_MARK.toInt() || category == Character.COMBINING_SPACING_MARK.toInt() ||
                category == Character.ENCLOSING_MARK.toInt() || next in 0xFE00..0xFE0F || next in 0x1F3FB..0x1F3FF ||
                next == 0x200D || previous == 0x200D || next == 0x0A && previous == 0x0D
            if (attached) { end += Character.charCount(next); continue }
            if (next in 0x1F1E6..0x1F1FF && previous in 0x1F1E6..0x1F1FF) {
                var count = 0
                var cursor = end
                while (cursor > 0 && text.codePointBefore(cursor) in 0x1F1E6..0x1F1FF) { cursor -= Character.charCount(text.codePointBefore(cursor)); count++ }
                if (count % 2 == 1) { end += Character.charCount(next); continue }
            }
            break
        }
        return end
    }
}

/** Page/conversation-owned one-shot markers survive lazy item disposal without retaining text. */
internal class LiveEntryAnimations(initialIds: Collection<String>) {
    private val seen = initialIds.toMutableSet()
    private val pending = mutableSetOf<String>()

    fun observe(ids: Collection<String>, active: Boolean) {
        val current = ids.toSet()
        if (active) pending.addAll(current - seen)
        pending.retainAll(current)
        seen.retainAll(current)
        seen.addAll(current)
    }

    fun consume(id: String): Boolean = pending.remove(id)
}

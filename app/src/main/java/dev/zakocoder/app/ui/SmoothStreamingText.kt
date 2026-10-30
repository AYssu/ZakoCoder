package dev.zakocoder.app.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.max

/** Presentation-only cursor; source/history, tool approvals and usage never wait for it. */
internal object StreamingTextPacer {
    const val TICK_MS = 32L
    const val CATCH_UP_MS = 480L

    fun advance(source: String, visible: Int, elapsedMs: Long): Int {
        if (visible >= source.length) return source.length
        val remaining = source.length - visible
        val step = max(1, ceil(remaining * elapsedMs.coerceIn(1, 64).toDouble() / CATCH_UP_MS).toInt())
        return boundary(source, (visible + step).coerceAtMost(source.length))
    }

    fun atTime(source: String, initial: Int, visible: Int, elapsedMs: Long): Int {
        val deadlineStep = initial + ceil((source.length - initial) * elapsedMs.coerceIn(0, CATCH_UP_MS).toDouble() / CATCH_UP_MS).toInt()
        return boundary(source, max(advance(source, visible, TICK_MS), deadlineStep))
    }

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

@Composable
internal fun smoothStreamingText(identity: String, source: String, streaming: Boolean, animateOnMount: Boolean = false): String {
    // Existing/history text is immediately readable, even after scrolling away or returning.
    var shown by remember(identity) { mutableStateOf(if (animateOnMount && streaming) "" else source) }
    var wasStreaming by remember(identity) { mutableStateOf(streaming) }
    val latestSource by rememberUpdatedState(source)
    val latestStreaming by rememberUpdatedState(streaming)
    LaunchedEffect(identity) {
        snapshotFlow { latestSource to latestStreaming }.collectLatest { (target, active) ->
            if (!target.startsWith(shown) || (!active && !wasStreaming)) shown = target
            wasStreaming = active
            val started = System.nanoTime()
            val initial = shown.length
            while (shown.length < target.length) {
                delay(StreamingTextPacer.TICK_MS)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                shown = target.take(StreamingTextPacer.atTime(target, initial, shown.length, elapsed))
            }
        }
    }
    return shown
}

package dev.zakocoder.app.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs

internal const val INLINE_CODE_ANNOTATION = "inline-code"

/** Keep real selectable text; paint code backgrounds behind each visual run after wrapping. */
internal fun inlineCodeBackgrounds(layout: TextLayoutResult): List<Rect> = buildList {
    val text = layout.layoutInput.text
    text.getStringAnnotations(INLINE_CODE_ANNOTATION, 0, text.length).forEach { range ->
        val boxes = (range.start until range.end).mapNotNull { offset ->
            if (text[offset] == '\n' || text[offset] == '\r') null
            else layout.getBoundingBox(offset).takeIf { it.width > 0f && it.height > 0f }
        }.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
        var run: Rect? = null
        boxes.forEach { box ->
            val previous = run
            if (previous != null && abs(previous.top - box.top) < .5f &&
                abs(previous.bottom - box.bottom) < .5f && box.left <= previous.right + .5f) {
                run = Rect(previous.left, previous.top, maxOf(previous.right, box.right), previous.bottom)
            } else {
                previous?.let(::add)
                run = box
            }
        }
        run?.let(::add)
    }
}

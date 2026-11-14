package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

internal val LocalTextArrival = compositionLocalOf { false }
internal const val TEXT_REVEAL_MS = 200

/** A continuous drawing cursor. Layout, links and selection keep the complete native text. */
@Composable
internal fun ArrivalText(text: AnnotatedString, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null, fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    style: TextStyle = LocalTextStyle.current, animated: Boolean = LocalTextArrival.current) {
    val cursor = remember { Animatable(if (animated) 0f else text.length.toFloat()) }
    var previous by remember { mutableStateOf(text.text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val boundaries = remember(text.text, animated) {
        if (!animated) intArrayOf() else buildList {
            add(0)
            var offset = 0
            while (offset < text.length) {
                offset = StreamingTextPacer.boundary(text.text, offset + 1)
                add(offset)
            }
        }.toIntArray()
    }
    LaunchedEffect(text.text, animated) {
        val append = text.text.startsWith(previous)
        previous = text.text
        if (!animated || !append || cursor.value > text.length) cursor.snapTo(text.length.toFloat())
        else cursor.animateTo(text.length.toFloat(), tween(TEXT_REVEAL_MS, easing = LinearEasing))
    }
    Text(text, modifier.drawWithContent {
        val result = layout
        val position = if (animated) cursor.value.coerceIn(0f, text.length.toFloat()) else text.length.toFloat()
        if (result == null || result.layoutInput.text != text || position >= text.length || !animated) {
            drawContent()
        } else if (position > 0f && boundaries.size > 1) {
            val found = boundaries.binarySearch(position.toInt())
            val at = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
            val base = boundaries[at]
            val next = boundaries.getOrElse(at + 1) { text.length }
            if (base > 0) clipPath(result.getPathForRange(0, base)) { this@drawWithContent.drawContent() }
            if (next > base) {
                val fraction = ((position - base) / (next - base)).coerceIn(0f, 1f)
                val glyph = result.getBoundingBox(base)
                val rtl = result.getBidiRunDirection(base) == ResolvedTextDirection.Rtl
                val left = if (rtl) glyph.right - glyph.width * fraction else glyph.left
                val right = if (rtl) glyph.right else glyph.left + glyph.width * fraction
                // Only the partially revealed cluster needs an alpha layer, never the full paragraph.
                if (right > left && fraction > 0f) {
                    val canvas = drawContext.canvas
                    canvas.saveLayer(Rect(left, glyph.top, right, glyph.bottom), Paint().apply { alpha = fraction })
                    clipRect(left, glyph.top, right, glyph.bottom) { this@drawWithContent.drawContent() }
                    canvas.restore()
                }
            }
        }
    }, color = color, fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight,
        lineHeight = lineHeight, maxLines = maxLines, overflow = overflow, softWrap = softWrap,
        style = style, onTextLayout = { layout = it })
}

@Composable
internal fun ArrivalText(text: String, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null, fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    style: TextStyle = LocalTextStyle.current, animated: Boolean = LocalTextArrival.current) =
    ArrivalText(AnnotatedString(text), modifier, color, fontSize, fontFamily, fontWeight,
        lineHeight, maxLines, overflow, softWrap, style, animated)

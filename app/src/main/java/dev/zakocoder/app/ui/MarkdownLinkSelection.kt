package dev.zakocoder.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

private const val LINK_RANGE = "markdown_link"

/** Keep native links tappable while allowing a long press to select their text. */
@Composable
internal fun SelectableMarkdownText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    inlineCodeBackground: Color = Color.Unspecified,
) {
    val links = remember(text) { text.getLinkAnnotations(0, text.length).filter { it.start < it.end } }
    val fallbackLinkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline)
    val marked = remember(text, fallbackLinkStyle) {
        if (links.isEmpty()) text else AnnotatedString.Builder(text.flatMapAnnotations { range ->
            if (range.item is LinkAnnotation) emptyList() else listOf(range)
        }).apply {
            links.forEachIndexed { index, range ->
                addStyle(range.item.styles?.style ?: fallbackLinkStyle, range.start, range.end)
                addStringAnnotation(LINK_RANGE, index.toString(), range.start, range.end)
            }
        }.toAnnotatedString()
    }
    val uriHandler = LocalUriHandler.current
    val activate: (LinkAnnotation) -> Unit = { link ->
        val listener = link.linkInteractionListener
        if (listener != null) listener.onClick(link)
        else if (link is LinkAnnotation.Url) uriHandler.openUri(link.url)
    }
    val latestActivate by rememberUpdatedState(activate)
    val latestLinks by rememberUpdatedState(links)
    val latestText by rememberUpdatedState(marked)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val latestLayout by rememberUpdatedState(layout)
    val linkSelection = if (links.isEmpty()) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            // Observe after the native selection gestures. Never consume a pointer:
            // selection, scrolling, handles and long presses remain entirely native.
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
            val result = latestLayout ?: return@awaitEachGesture
            if (result.layoutInput.text != latestText) return@awaitEachGesture
            val caret = result.getOffsetForPosition(down.position)
            val offset = listOf(caret, caret - 1).firstOrNull { index ->
                index in 0 until result.layoutInput.text.length && result.getBoundingBox(index).contains(down.position)
            } ?: return@awaitEachGesture
            val link = latestLinks.firstOrNull { offset >= it.start && offset < it.end } ?: return@awaitEachGesture
            var candidate = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.any { it.isConsumed } || event.changes.count { it.pressed } > 1 ||
                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) candidate = false
                if (!pointer.pressed) {
                    if (candidate && pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                        latestLayout?.layoutInput?.text == result.layoutInput.text) latestActivate(link.item)
                    break
                }
            }
        }
    }.semantics {
        // Each link stays reachable through TalkBack actions without adding an
        // interactive overlay over the selectable text.
        customActions = links.map { range ->
            CustomAccessibilityAction(text.text.substring(range.start, range.end)) {
                latestActivate(range.item); true
            }
        }
    }
    val density = LocalDensity.current
    val focusColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f)
    Box(modifier, propagateMinConstraints = true) {
        ArrivalText(marked, linkSelection, fontSize = fontSize, fontWeight = fontWeight,
            lineHeight = lineHeight, inlineCodeBackground = inlineCodeBackground, onTextLayout = { layout = it })
        layout?.takeIf { it.layoutInput.text == marked && links.isNotEmpty() }?.let { result ->
            // These nodes provide only focus/semantics: no clickable or pointer input
            // modifier may be added, because that would block native text selection.
            Box(Modifier.matchParentSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                links.forEachIndexed { index, range -> key(index, range.start) {
                    var focused by remember { mutableStateOf(false) }
                    val path = remember(result, range.start, range.end) { result.getPathForRange(range.start, range.end) }
                    val bounds = path.getBounds()
                    if (bounds.width > 0f && bounds.height > 0f) {
                        val localPath = remember(path, bounds) { Path().apply { addPath(path, Offset(-bounds.left, -bounds.top)) } }
                        val label = marked.text.substring(range.start, range.end)
                        Box(Modifier.absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                            .drawBehind { if (focused) drawPath(localPath, focusColor) }
                            .onFocusChanged { focused = it.isFocused }
                            .onKeyEvent { event ->
                                if (event.key in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)) {
                                    if (event.type == KeyEventType.KeyUp) latestActivate(range.item)
                                    true
                                } else false
                            }
                            .semantics {
                                contentDescription = label
                                onClick { latestActivate(range.item); true }
                            }
                            .focusable())
                    }
                } }
            }
        }
    }
}

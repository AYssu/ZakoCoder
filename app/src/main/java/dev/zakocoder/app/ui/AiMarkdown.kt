package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Parses text only. No HTML engine, image loader, network, or execution path. */
internal object AiMarkdownDocument {
    fun parse(text: String): Node {
        require(text.length <= AiTextPages.MARKDOWN_LIMIT)
        val parsed = Parser.builder().extensions(listOf(TablesExtension.create())).build().parse(text)
        var count = 0
        val pending = java.util.ArrayDeque<Node>().apply { add(parsed) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (++count > 8000) return Document().apply { appendChild(org.commonmark.node.Paragraph().apply { appendChild(org.commonmark.node.Text(text)) }) }
            children(node).forEach(pending::add)
        }
        return parsed
    }
    fun externalLink(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    } catch (_: Exception) { false }
    fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun AiMarkdown(text: String, onLink: (String) -> Unit) {
    var document by remember { mutableStateOf<Node?>(null) }
    var fullTextLength by rememberSaveable { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(text)
    LaunchedEffect(Unit) {
        snapshotFlow { current }.sample(80).collectLatest { source ->
            document = withContext(Dispatchers.Default) { AiMarkdownDocument.parse(source.take(AiTextPages.MARKDOWN_LIMIT)) }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (text.length > AiTextPages.MARKDOWN_LIMIT) {
            Text(stringResource(R.string.ai_markdown_partial, AiTextPages.MARKDOWN_LIMIT, text.length),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { fullTextLength = text.length }) { Text(stringResource(R.string.ai_full_text_open)) }
        }
        SelectionContainer {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                document?.let { root -> AiMarkdownDocument.children(root).forEach { MarkdownBlock(it, onLink, 0) } }
            }
        }
        if (text.length > AiTextPages.MARKDOWN_LIMIT) {
            TextButton(onClick = { fullTextLength = text.length }) { Text(stringResource(R.string.ai_full_text_open)) }
        }
    }
    fullTextLength?.let { length ->
        val snapshot = remember(length) { text.take(length) }
        AiFullText(snapshot) { fullTextLength = null }
    }
}

@Composable
private fun MarkdownBlock(node: Node, onLink: (String) -> Unit, depth: Int) {
    if (depth > 16) return
    val children = AiMarkdownDocument.children(node)
    when (node) {
        is Heading -> Text(inline(node, onLink), fontWeight = FontWeight.SemiBold,
            fontSize = when (node.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 15.sp }, lineHeight = 27.sp)
        is org.commonmark.node.Paragraph -> Text(inline(node, onLink), fontSize = 13.sp, lineHeight = 22.sp)
        is FencedCodeBlock -> CodeBlock(node.literal, node.info.orEmpty().substringBefore(' ').take(40))
        is IndentedCodeBlock -> CodeBlock(node.literal, "")
        is BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Surface(Modifier.width(3.dp).heightIn(min = 30.dp), color = MaterialTheme.colorScheme.primary) { }
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                children.forEach { MarkdownBlock(it, onLink, depth + 1) }
            }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            children.forEachIndexed { index, item -> Row {
                Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", Modifier.width(26.dp), fontSize = 13.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AiMarkdownDocument.children(item).forEach { MarkdownBlock(it, onLink, depth + 1) }
                }
            } }
        }
        is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is TableBlock -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            children.flatMap { AiMarkdownDocument.children(it) }.forEach { row -> Row {
                AiMarkdownDocument.children(row).forEach { cell -> Surface(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = if (cell is TableCell && cell.isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                    Text(inline(cell, onLink), Modifier.width(160.dp).padding(9.dp), fontSize = 12.sp, lineHeight = 19.sp,
                        fontWeight = if (cell is TableCell && cell.isHeader) FontWeight.SemiBold else null)
                } }
            } }
        }
        is HtmlBlock -> Text(node.literal, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        else -> children.forEach { MarkdownBlock(it, onLink, depth + 1) }
    }
}

@Composable
private fun inline(node: Node, onLink: (String) -> Unit): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        fun appendNode(current: Node, depth: Int) {
            if (depth > 32) return
            when (current) {
                is org.commonmark.node.Text -> append(current.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh, color = colors.primary)) { append(current.literal) }
                is SoftLineBreak -> append("\n")
                is HardLineBreak -> append("\n")
                is HtmlInline -> append(current.literal)
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) } }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) } }
                is Link -> {
                    if (AiMarkdownDocument.externalLink(current.destination)) withLink(LinkAnnotation.Clickable(current.destination,
                        TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) { onLink(current.destination) }) {
                        AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) }
                    } else AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) }
                }
                is Image -> { append("["); AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) }; append("]") }
                else -> AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1) }
            }
        }
        AiMarkdownDocument.children(node).forEach { appendNode(it, 0) }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(language.ifBlank { stringResource(R.string.ai_code) }, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(R.string.ai_copy_code), fontSize = 11.sp) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(code.trimEnd('\n'), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, softWrap = false)
        }
    }
}

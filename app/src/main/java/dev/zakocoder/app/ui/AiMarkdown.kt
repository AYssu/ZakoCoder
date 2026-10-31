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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
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
internal fun AiMarkdown(
    text: String,
    projectId: String? = null,
    onOpenProjectPath: ((String) -> Unit)? = null,
    streaming: Boolean = false,
    onReadingInteraction: () -> Unit = {},
    onLink: (String) -> Unit,
) {
    var document by remember { mutableStateOf<Node?>(null) }
    var fullTextLength by rememberSaveable { mutableStateOf<Int?>(null) }
    val current by rememberUpdatedState(text)
    LaunchedEffect(Unit) {
        // Parsing is decoupled from the 32 ms reveal clock and never runs on the UI thread.
        snapshotFlow { current.take(AiTextPages.MARKDOWN_LIMIT) }.sample(64).conflate().collect { source ->
            document = withContext(Dispatchers.Default) { AiMarkdownDocument.parse(source.take(AiTextPages.MARKDOWN_LIMIT)) }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (text.length > AiTextPages.MARKDOWN_LIMIT) {
            Text(stringResource(R.string.ai_markdown_partial, AiTextPages.MARKDOWN_LIMIT, text.length),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onReadingInteraction(); fullTextLength = text.length }) { Text(stringResource(R.string.ai_full_text_open)) }
        }
        SelectionContainer {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                document?.let { root -> AiMarkdownDocument.children(root).forEach { MarkdownBlock(it, onLink, 0, projectId, onOpenProjectPath, streaming, text.length > AiTextPages.MARKDOWN_LIMIT, onReadingInteraction) } }
            }
        }
        if (text.length > AiTextPages.MARKDOWN_LIMIT) {
            TextButton(onClick = { onReadingInteraction(); fullTextLength = text.length }) { Text(stringResource(R.string.ai_full_text_open)) }
        }
    }
    fullTextLength?.let { length ->
        val snapshot = remember(length) { text.take(length) }
        AiFullText(snapshot) { fullTextLength = null }
    }
}

@Composable
private fun MarkdownBlock(node: Node, onLink: (String) -> Unit, depth: Int, projectId: String?, onOpenProjectPath: ((String) -> Unit)?, streaming: Boolean,
    sourceLimited: Boolean, onReadingInteraction: () -> Unit) {
    if (depth > 16) return
    val children = AiMarkdownDocument.children(node)
    when (node) {
        is Heading -> Text(inline(node, onLink, projectId, onOpenProjectPath), fontWeight = FontWeight.SemiBold,
            fontSize = when (node.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 15.sp }, lineHeight = 27.sp)
        is org.commonmark.node.Paragraph -> Text(inline(node, onLink, projectId, onOpenProjectPath), fontSize = 13.sp, lineHeight = 22.sp)
        is FencedCodeBlock -> CodeBlock(node.literal, node.info.orEmpty().substringBefore(' ').take(40), streaming, sourceLimited, onReadingInteraction)
        is IndentedCodeBlock -> CodeBlock(node.literal, "", streaming, sourceLimited, onReadingInteraction)
        is BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Surface(Modifier.width(3.dp).heightIn(min = 30.dp), color = MaterialTheme.colorScheme.primary) { }
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                children.forEach { MarkdownBlock(it, onLink, depth + 1, projectId, onOpenProjectPath, streaming, sourceLimited, onReadingInteraction) }
            }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            children.forEachIndexed { index, item -> Row {
                Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", Modifier.widthIn(min = 26.dp).padding(end = 8.dp), fontSize = 13.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AiMarkdownDocument.children(item).forEach { MarkdownBlock(it, onLink, depth + 1, projectId, onOpenProjectPath, streaming, sourceLimited, onReadingInteraction) }
                }
            } }
        }
        is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is TableBlock -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            children.flatMap { AiMarkdownDocument.children(it) }.forEach { row -> Row(Modifier.height(IntrinsicSize.Min)) {
                AiMarkdownDocument.children(row).forEach { cell -> Surface(modifier = Modifier.width(160.dp).fillMaxHeight(), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = if (cell is TableCell && cell.isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                    Text(inline(cell, onLink, projectId, onOpenProjectPath), Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp, lineHeight = 19.sp,
                        fontWeight = if (cell is TableCell && cell.isHeader) FontWeight.SemiBold else null)
                } }
            } }
        }
        is HtmlBlock -> Text(node.literal, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        else -> children.forEach { MarkdownBlock(it, onLink, depth + 1, projectId, onOpenProjectPath, streaming, sourceLimited, onReadingInteraction) }
    }
}

@Composable
private fun inline(node: Node, onLink: (String) -> Unit, projectId: String?, onOpenProjectPath: ((String) -> Unit)?): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        fun projectLink(label: String, relative: String) {
            withLink(LinkAnnotation.Clickable("project:$relative",
                TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) {
                onOpenProjectPath?.invoke(relative)
            }) { append(label) }
        }
        fun appendReferences(value: String, whole: Boolean = false) {
            val resolved = if (whole && onOpenProjectPath != null) AiProjectPaths.resolve(value, projectId) else null
            if (resolved != null) { projectLink(value, resolved); return }
            val references = if (onOpenProjectPath != null) AiProjectPaths.references(value, projectId) else emptyList()
            var offset = 0
            references.forEach { reference ->
                append(value.substring(offset, reference.start))
                projectLink(value.substring(reference.start, reference.end), reference.relative)
                offset = reference.end
            }
            append(value.substring(offset))
        }
        fun appendNode(current: Node, depth: Int, allowPaths: Boolean = true) {
            if (depth > 32) return
            when (current) {
                is org.commonmark.node.Text -> if (allowPaths) appendReferences(current.literal) else append(current.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh, color = colors.primary)) { if (allowPaths) appendReferences(current.literal, whole = true) else append(current.literal) }
                is SoftLineBreak -> append(" ")
                is HardLineBreak -> append("\n")
                is HtmlInline -> append(current.literal)
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, allowPaths) } }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, allowPaths) } }
                is Link -> {
                    val relative = if (onOpenProjectPath != null) AiProjectPaths.resolve(current.destination, projectId) else null
                    val target = if (AiMarkdownDocument.externalLink(current.destination)) current.destination else null
                    if (relative != null || target != null) withLink(LinkAnnotation.Clickable(relative ?: target!!,
                        TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))) {
                        if (relative != null) onOpenProjectPath?.invoke(relative) else onLink(target!!)
                    }) {
                        AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, false) }
                    } else AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, false) }
                }
                is Image -> { append("["); AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, allowPaths) }; append("]") }
                else -> AiMarkdownDocument.children(current).forEach { appendNode(it, depth + 1, allowPaths) }
            }
        }
        AiMarkdownDocument.children(node).forEach { appendNode(it, 0) }
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeBlock(code: String, language: String, streaming: Boolean, sourceLimited: Boolean, onReadingInteraction: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fullText by remember { mutableStateOf<String?>(null) }
    val preview = remember(code, expanded) { AiCodePreview.of(code, expanded) }
    val collapsed = remember(code) { AiCodePreview.of(code, false) }
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(language.ifBlank { stringResource(R.string.ai_code) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (collapsed.limited) Text(stringResource(R.string.ai_reading_code_lines, preview.totalLines),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(stringResource(if (sourceLimited) R.string.ai_reading_code_copy_preview else R.string.ai_copy_code), fontSize = 11.sp) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(preview.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, softWrap = false)
            if (sourceLimited) Text(stringResource(R.string.ai_reading_code_source_limited, stringResource(R.string.ai_full_text_open)), Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (collapsed.limited) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (preview.limited) Text(stringResource(if (sourceLimited) R.string.ai_reading_code_partial_lines else R.string.ai_reading_code_preview, preview.lines, preview.totalLines),
                        Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onReadingInteraction(); expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (expanded) R.string.ai_reading_code_collapse else R.string.ai_reading_code_expand))
                        }
                        if (expanded && preview.limited) TextButton(onClick = { onReadingInteraction(); fullText = code }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (sourceLimited) R.string.ai_reading_code_open_preview else R.string.ai_reading_code_full))
                        }
                    }
                    if (streaming) Text(stringResource(R.string.ai_reading_code_receiving), Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    fullText?.let { snapshot -> AiFullText(snapshot) { fullText = null } }
}

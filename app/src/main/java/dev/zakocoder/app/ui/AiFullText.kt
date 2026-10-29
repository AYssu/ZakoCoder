package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

/** A snapshot of already received text, never a request to fetch or execute anything. */
@Composable
internal fun AiFullText(text: String, close: () -> Unit) {
    val pages = remember(text) { AiTextPages.ranges(text) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val range = pages.getOrNull(page)
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    AiFullScreen(stringResource(R.string.ai_full_text_title), close) {
        Text(stringResource(R.string.ai_full_text_snapshot), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_full_text_range, range?.first?.plus(1) ?: 0, range?.last?.plus(1) ?: 0, text.length),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        val pageLabel = stringResource(R.string.ai_full_text_body)
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
                Text(range?.let { text.substring(it) }.orEmpty(), Modifier.fillMaxWidth().semantics { contentDescription = pageLabel },
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = 0 }, enabled = page > 0) { Text(stringResource(R.string.ai_full_text_first)) }
            IconButton(onClick = { selected = page - 1 }, enabled = page > 0) {
                WorkbenchIcon(Glyph.Left, description = stringResource(R.string.ai_full_text_previous))
            }
            Text(stringResource(R.string.ai_full_text_page, page + 1, pages.size.coerceAtLeast(1)), Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { selected = page + 1 }, enabled = page < pages.lastIndex) {
                WorkbenchIcon(Glyph.Right, description = stringResource(R.string.ai_full_text_next))
            }
            TextButton(onClick = { selected = pages.lastIndex.coerceAtLeast(0) }, enabled = page < pages.lastIndex) {
                Text(stringResource(R.string.ai_full_text_last))
            }
        }
    }
}

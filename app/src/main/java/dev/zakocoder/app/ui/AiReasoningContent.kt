package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiEntry

/** Only publicly returned provider reasoning, never inferred or reconstructed hidden thoughts. */
@Composable
internal fun AiReasoningContent(entry: AiEntry, modifier: Modifier = Modifier, streaming: Boolean = false, displayedReasoning: String = entry.reasoning) {
    if (entry.reasoning.isEmpty() && !entry.reasoningTruncated) return
    var expanded by remember(entry.id) { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { expanded = streaming }
    val scroll = rememberScrollState()
    LaunchedEffect(streaming, expanded, entry.reasoning.length, scroll.maxValue) {
        if (streaming && expanded) scroll.scrollTo(scroll.maxValue)
    }
    val clipboard = LocalClipboardManager.current
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                Text(stringResource(R.string.ai_reasoning_title), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                WorkbenchIcon(if (expanded) Glyph.Down else Glyph.Next, Modifier.size(18.dp), description = stringResource(if (expanded) R.string.ai_reasoning_collapse else R.string.ai_reasoning_expand))
            }
            if (expanded) {
                Text(stringResource(R.string.ai_reasoning_provider_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.reasoning.isNotEmpty()) SelectionContainer {
                    Text(if (streaming) displayedReasoning.takeLast(12000) else displayedReasoning.take(12000), Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scroll).padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.reasoning.length > 12000) Text(stringResource(if (streaming) R.string.ai_reasoning_live_preview_limited else R.string.ai_reasoning_preview_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.reasoningTruncated) Text(stringResource(R.string.ai_reasoning_retention_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(entry.reasoning)) }, enabled = entry.reasoning.isNotEmpty()) {
                        WorkbenchIcon(Glyph.Copy, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ai_reasoning_copy))
                    }
                }
            }
        }
    }
}

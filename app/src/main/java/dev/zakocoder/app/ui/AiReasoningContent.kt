package dev.zakocoder.app.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiEntry
import kotlinx.coroutines.launch

/** Only publicly returned provider reasoning, never inferred or reconstructed hidden thoughts. */
@Composable
internal fun AiReasoningContent(entry: AiEntry, modifier: Modifier = Modifier, streaming: Boolean = false, displayedReasoning: String = entry.reasoning,
    onReadingInteraction: () -> Unit = {}, trailingActions: (@Composable () -> Unit)? = null) {
    if (entry.reasoning.isEmpty() && !entry.reasoningTruncated) return
    var expanded by remember(entry.id) { mutableStateOf(streaming) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var follow by remember(entry.id) { mutableStateOf(true) }
    var pausedPreview by remember(entry.id) { mutableStateOf<String?>(null) }
    val preview = remember(displayedReasoning, streaming) { AiReasoningPreview.of(displayedReasoning, streaming) }
    val currentPreview by rememberUpdatedState(preview)
    val readingInteraction by rememberUpdatedState(onReadingInteraction)
    var automaticScroll by remember { mutableStateOf(false) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val awayFromEnd by remember { derivedStateOf { scroll.value < scroll.maxValue } }
    fun pauseReading() {
        if (follow || pausedPreview == null) {
            // Notify the conversation before adding the resume control changes this card's height.
            readingInteraction()
            pausedPreview = currentPreview
            follow = false
        }
    }
    LaunchedEffect(streaming) { expanded = streaming; pausedPreview = null; follow = true }
    LaunchedEffect(dragged) { if (dragged) pauseReading() }
    LaunchedEffect(scroll, entry.id) {
        var userScrolled = false
        // Observe the origin in the same snapshot as the scroll event. Looking up automaticScroll
        // later can misclassify a delivered programmatic event after its finally block has run.
        snapshotFlow { scroll.isScrollInProgress to automaticScroll }.collect { (scrolling, automatic) ->
            if (scrolling && !automatic) { userScrolled = true; pauseReading() }
            if (!scrolling && userScrolled) {
                if (scroll.value >= scroll.maxValue) { pausedPreview = null; follow = true }
                userScrolled = false
            }
        }
    }
    LaunchedEffect(streaming, expanded, displayedReasoning.length, scroll.maxValue) {
        if (streaming && expanded && follow && !dragged && !scroll.isScrollInProgress) {
            automaticScroll = true
            try { scroll.scrollTo(scroll.maxValue) } finally { automaticScroll = false }
        }
    }
    val clipboard = LocalClipboardManager.current
    Column(modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onReadingInteraction(); expanded = !expanded }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (streaming) R.string.ai_reasoning_in_progress else R.string.ai_reasoning_title),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!expanded && displayedReasoning.isNotBlank()) Text(
                        displayedReasoning.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkbenchIcon(if (expanded) Glyph.Down else Glyph.Next, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant, description = stringResource(if (expanded) R.string.ai_reasoning_collapse else R.string.ai_reasoning_expand))
            }
            trailingActions?.invoke()
            }
            if (expanded) {
                Text(stringResource(R.string.ai_reasoning_provider_hint),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.reasoning.isNotEmpty()) SelectionContainer {
                    Text(pausedPreview ?: preview, Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scroll).padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.reasoning.length > AiReasoningPreview.MAX_CHARS) Text(stringResource(
                    if (streaming && pausedPreview != null) R.string.ai_reading_reasoning_paused_preview
                    else if (streaming) R.string.ai_reasoning_live_preview_limited else R.string.ai_reasoning_preview_limited),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.reasoningTruncated) Text(stringResource(R.string.ai_reasoning_retention_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (streaming && !follow && (awayFromEnd || pausedPreview != null)) TextButton(onClick = {
                    readingInteraction()
                    scope.launch {
                        automaticScroll = true
                        pausedPreview = null
                        try {
                            // Allow the latest bounded preview to be measured before targeting its end.
                            withFrameNanos { }
                            scroll.animateScrollTo(scroll.maxValue)
                            follow = true
                        } finally { automaticScroll = false }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_reading_reasoning_latest)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(entry.reasoning)) }, enabled = entry.reasoning.isNotEmpty()) {
                        Text(stringResource(R.string.ai_reasoning_copy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

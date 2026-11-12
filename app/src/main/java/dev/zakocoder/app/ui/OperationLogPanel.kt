package dev.zakocoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import kotlinx.coroutines.flow.collectLatest

/** Displays supplied operation output only. It never creates a terminal process or runs input. */
@Composable
internal fun OperationLogPanel(
    log: String,
    running: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    error: String? = null
) {
    var expanded by remember { mutableStateOf(running) }
    var following by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    // A new operation reopens its output; appended chunks must not override a user's pause.
    LaunchedEffect(running) {
        if (running) { expanded = true; following = true }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { Triple(scroll.maxValue, following, expanded) }.collectLatest { (bottom, follow, open) ->
            if (follow && open) scroll.scrollTo(bottom)
        }
    }
    val userScroll = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y < 0f && !scroll.canScrollForward) following = true
                return Offset.Zero
            }
        }
    }
    val output = remember(log) { log.takeLast(24000) }
    val expansion = stringResource(if (expanded) R.string.mobile_expanded else R.string.mobile_collapsed)
    WorkbenchCard(modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = expansion }
                .clickable(role = Role.Button) { expanded = !expanded; if (expanded) following = true }
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIcon(Glyph.Terminal, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title.ifBlank { stringResource(R.string.execution_log) }, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                WorkbenchIcon(if (expanded) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp))
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (following) R.string.operation_log_following else R.string.operation_log_paused),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { following = true }, enabled = !following,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.operation_log_latest), style = MaterialTheme.typography.labelSmall)
                    }
                }
                val description = stringResource(R.string.operation_log_output)
                SelectionContainer {
                    Box(Modifier.fillMaxWidth().height(200.dp).background(TerminalBackground)
                        .nestedScroll(userScroll).verticalScroll(scroll).padding(12.dp)
                        .semantics { contentDescription = description }) {
                        Text(output.ifEmpty { stringResource(if (running) R.string.operation_log_waiting else R.string.operation_log_empty) },
                            color = TerminalForeground, fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
                if (log.length > output.length) Text(stringResource(R.string.operation_log_tail),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

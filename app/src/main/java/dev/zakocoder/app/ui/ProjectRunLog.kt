package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.AiCommandLogReference
import dev.zakocoder.app.workflow.ProjectRun
import kotlinx.coroutines.CancellationException

/** Shared direct log view for project history and AI delivery. Bounded pages keep mobile text layout small. */
@Composable
internal fun ProjectRunLog(workbench: Workbench, projectId: String, run: ProjectRun, onAi: ((String) -> Unit)?, close: () -> Unit) {
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    var text by remember(projectId, run.id) { mutableStateOf("") }
    var offset by remember(projectId, run.id) { mutableIntStateOf(0) }
    var query by remember(projectId, run.id) { mutableStateOf("") }
    var matchAt by remember(projectId, run.id) { mutableIntStateOf(-1) }
    var failed by remember(projectId, run.id) { mutableStateOf(false) }
    var missing by remember(projectId, run.id) { mutableStateOf(false) }
    var loading by remember(projectId, run.id) { mutableStateOf(true) }
    var tailReference by remember(projectId, run.id) { mutableStateOf(false) }
    var tailJump by remember(projectId, run.id) { mutableIntStateOf(0) }
    var pendingTailJump by remember(projectId, run.id) { mutableIntStateOf(0) }
    var laidOutTailJump by remember(projectId, run.id) { mutableIntStateOf(0) }
    LaunchedEffect(projectId, run.id, run.status) {
        loading = true; failed = false
        do {
            try { text = workbench.projectTasks.log(projectId, run.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
            if (run.status != "running" || failed) break
            kotlinx.coroutines.delay(750)
        } while (true)
    }
    val start = minOf(offset, text.length)
    val end = minOf(text.length, start + 16000)
    val page = text.substring(start, end)
    val referenceName = commandKind(run.kind) + " · " + run.id.take(8)
    val scroll = rememberScrollState()
    LaunchedEffect(start, matchAt) { scroll.scrollTo(0) }
    LaunchedEffect(pendingTailJump, laidOutTailJump) {
        if (pendingTailJump != 0 && pendingTailJump == laidOutTailJump) {
            scroll.scrollTo(scroll.maxValue)
            pendingTailJump = 0
        }
    }
    val emptyLabel = stringResource(R.string.project_log_empty)
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    val rendered = remember(page, start, matchAt, query, highlight, emptyLabel) {
        val local = matchAt - start
        if (query.isNotEmpty() && local >= 0 && local + query.length <= page.length) buildAnnotatedString {
            append(page.substring(0, local)); withStyle(highlight) { append(page.substring(local, local + query.length)) }; append(page.substring(local + query.length))
        } else AnnotatedString(page.ifBlank { emptyLabel })
    }
    AiFullScreen(stringResource(R.string.project_run_log), close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(runStatus(run.status) + " · " + (run.exit?.let { stringResource(R.string.project_exit_value, it) } ?: stringResource(R.string.project_exit_unknown)), style = MaterialTheme.typography.titleSmall)
            if (run.truncated) Text(stringResource(R.string.project_log_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(query, { if (it.length <= 200) { query = it; matchAt = -1; tailReference = false; pendingTailJump = 0 }; missing = false }, label = { Text(stringResource(R.string.project_log_search)) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    tailReference = false; pendingTailJump = 0
                    var found = text.indexOf(query, startIndex = maxOf(0, matchAt + 1), ignoreCase = true)
                    if (found < 0 && matchAt >= 0) found = text.indexOf(query, ignoreCase = true)
                    missing = found < 0; matchAt = found
                    if (found >= 0) offset = maxOf(text.lastIndexOf('\n', found - 1) + 1, found - 120)
                }, enabled = query.isNotBlank() && !loading) { Text(stringResource(if (matchAt >= 0) R.string.project_log_find_next else R.string.editor_find)) }
            }
            if (missing) Text(stringResource(R.string.project_log_no_match), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { offset = maxOf(0, start - 16000); tailReference = false; pendingTailJump = 0 }, enabled = start > 0) { Text(stringResource(R.string.project_log_previous)) }
                Text(stringResource(R.string.project_log_range, start, end, text.length), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { offset = end; tailReference = false; pendingTailJump = 0 }, enabled = end < text.length) { Text(stringResource(R.string.project_log_next)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    offset = maxOf(0, text.length - 16000)
                    matchAt = -1; missing = false; tailReference = true
                    tailJump++; pendingTailJump = tailJump
                }, enabled = text.isNotEmpty() && !loading, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.project_log_latest)) }
                if (onAi != null) TextButton(onClick = {
                    if (workbench.referenceAi(projectId, AiCommandLogReference.create(run.id, text, start, end, run.truncated, referenceName, preferEnd = tailReference))) { close(); onAi(projectId) }
                }, enabled = page.isNotBlank() && !ai.active && !ai.restoring, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (tailReference) R.string.project_log_reference_tail else R.string.project_log_reference_page))
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.project_log_load_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        key(tailJump) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(rendered, Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).onGloballyPositioned {
                    if (pendingTailJump != 0) laidOutTailJump = tailJump
                }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


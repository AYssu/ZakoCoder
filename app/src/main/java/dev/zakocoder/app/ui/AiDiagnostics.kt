package dev.zakocoder.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

@Suppress("DEPRECATION")
@Composable
internal fun AiRequestsPanel(state: AiState, modifier: Modifier = Modifier) {
    var selected by remember(state.conversationId) { mutableStateOf<String?>(null) }
    var limits by remember { mutableStateOf(false) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_request_retained, state.requests.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { limits = true }, modifier = Modifier.size(32.dp)) { WorkbenchIcon(Glyph.Info, Modifier.size(16.dp), description = stringResource(R.string.ai_data_details)) }
        } }
        if (state.taskDiagnostic.isNotBlank()) item {
            Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            DiagnosticCode(state.taskDiagnostic)
        }
        if (state.requests.isEmpty()) item { Text(stringResource(R.string.ai_requests_empty)) }
        items(state.requests.asReversed(), key = { it.id }) { request ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().clickable { selected = request.id }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai_request_row, request.turn, request.round), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        WorkbenchIcon(Glyph.Next, Modifier.size(16.dp), description = stringResource(R.string.ai_request_details))
                    }
                    if (request.compaction) Text(stringResource(R.string.ai_phase_compacting), style = MaterialTheme.typography.labelSmall)
                    if (request.attempt > 1) Text(stringResource(R.string.ai_request_retry_badge, request.attempt, request.maxAttempts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    val elapsed = requestElapsed(request)
                    val status = stringResource(request.failure?.label ?: if (request.complete) R.string.ai_phase_completed else R.string.ai_phase_receiving)
                    Text(stringResource(R.string.ai_request_state, status, request.status?.toString() ?: "—", elapsed),
                        color = if (request.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth()) {
                        listOf(R.string.ai_token_input to request.inputTokens, R.string.ai_token_output to request.outputTokens, R.string.ai_token_cached to request.cachedTokens).forEach { (label, value) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(value?.toString() ?: "—", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(stringResource(R.string.ai_request_brief, request.events, request.textChars + request.argumentChars, request.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StreamChart(request)
                }
            }
        }
    }
    if (limits) AlertDialog(onDismissRequest = { limits = false }, title = { Text(stringResource(R.string.ai_data_details)) }, text = { Text(stringResource(R.string.ai_log_limits)) }, confirmButton = { TextButton(onClick = { limits = false }) { Text(stringResource(R.string.close)) } })
    state.requests.find { it.id == selected }?.let { request ->
        AiFullScreen(stringResource(R.string.ai_request_details), { selected = null }) {
            val clipboard = LocalClipboardManager.current
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SelectionContainer { Text(stringResource(R.string.ai_request_identity, request.id, request.model, request.origin,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(request.started)), request.finish.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.ai_request_protocol, stringResource(protocolLabel(dev.zakocoder.app.ai.AiApiProtocol.entries.firstOrNull { it.name == request.apiProtocol } ?: dev.zakocoder.app.ai.AiApiProtocol.ChatCompletions))),
                    style = MaterialTheme.typography.bodySmall)
                val status = stringResource(request.failure?.label ?: if (request.complete) R.string.ai_phase_completed else R.string.ai_phase_receiving)
                Text(stringResource(R.string.ai_request_state, status, request.status?.toString() ?: "—", requestElapsed(request)), style = MaterialTheme.typography.bodySmall)
                request.failure?.let { Text(stringResource(it.label), color = MaterialTheme.colorScheme.error) }
                if (request.maxAttempts > 1) {
                    Text(stringResource(R.string.ai_request_retry_attempt, request.attempt, request.maxAttempts, request.retryDelayMs), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ai_request_retry_group, request.retryGroup, request.retryReason.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall)
                }
                if (request.diagnostic.isNotEmpty()) Text(stringResource(R.string.ai_request_diagnostic, request.diagnostic), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (request.estimatedContextTokens > 0) Text(stringResource(R.string.ai_context_estimated_request, tokenWindowLabel(request.estimatedContextTokens), tokenWindowLabel(request.contextWindowTokens.toLong()), tokenWindowLabel(request.contextOutputReserveTokens.toLong())), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_request_metrics, request.contextChars, request.requestBytes, request.textChars, request.argumentChars, request.events, request.bytes), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_request_latency, request.firstEvent?.toString() ?: "—", request.firstText?.toString() ?: "—"), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_request_tokens, request.inputTokens?.toString() ?: stringResource(R.string.ai_usage_missing), request.outputTokens?.toString() ?: stringResource(R.string.ai_usage_missing), request.cachedTokens?.toString() ?: stringResource(R.string.ai_usage_missing)))
                StreamChart(request)
                Text(stringResource(R.string.ai_request_timeline), style = MaterialTheme.typography.labelLarge)
                DiagnosticCode(request.points.joinToString("\n") { "${it.ms / 1000.0} / ${it.events} / ${it.chars} / ${it.bytes}" })
                Text(stringResource(R.string.ai_request_body), style = MaterialTheme.typography.labelLarge)
                DiagnosticCode(request.request)
                Text(stringResource(R.string.ai_response_body), style = MaterialTheme.typography.labelLarge)
                DiagnosticCode(request.response)
                if (request.truncated) Text(stringResource(R.string.ai_log_truncated), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.ai_log_limits), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(request.encode().toString(2))) }) { Text(stringResource(R.string.ai_log_copy)) }
            }
        }
    }
}

@Composable
private fun StreamChart(request: AiRequestLog) {
    val color = MaterialTheme.colorScheme.primary
    val points = request.points
    if (points.size < 2) return
    Canvas(Modifier.fillMaxWidth().height(42.dp)) {
        if (points.size < 2) return@Canvas
        val maxTime = points.last().ms.coerceAtLeast(1)
        val maxChars = points.last().chars.coerceAtLeast(1)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.ms.toFloat() / maxTime * size.width
            val y = size.height - point.chars.toFloat() / maxChars * (size.height - 4f) - 2f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
@Composable
private fun DiagnosticCode(text: String) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            items(text.chunked(2048)) { chunk -> Text(chunk, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp) }
        }
    }
}
@Composable
internal fun AiSkillsPanel(state: AiState, controller: AiController, modifier: Modifier = Modifier) {
    var query by remember(state.conversationId) { mutableStateOf("") }
    var viewing by remember(state.conversationId) { mutableStateOf<AiSkill?>(null) }
    val editable = !state.active && !state.permissionsLoading && !state.skillsLoading && !state.historyError
    val filtered = state.skills.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_skills_selected, state.enabledSkills.size, AiSkills.MAX_ENABLED), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = controller::recommendedSkills, enabled = editable) { Text(stringResource(R.string.ai_skills_recommended)) }
                }
                Text(stringResource(R.string.ai_skills_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AiCustomSkillActions(state, controller)
                val searchLabel = stringResource(R.string.ai_skills_search)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(searchLabel) }, singleLine = true,
                        modifier = Modifier.weight(1f).semantics { contentDescription = searchLabel })
                    IconButton(onClick = controller::refreshSkills, enabled = editable) { WorkbenchIcon(Glyph.Refresh, description = stringResource(R.string.ai_skills_refresh)) }
                }
                if (state.skillsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.skillError) Text(stringResource(R.string.ai_skills_error), color = MaterialTheme.colorScheme.error)
                if (!state.skillsLoading && filtered.isEmpty()) Text(stringResource(R.string.ai_skills_empty))
                state.enabledSkills.filter { path -> state.skills.none { it.path == path && it.valid } }.forEach { path ->
                    Text(stringResource(R.string.ai_skill_missing, path), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    TextButton(onClick = { controller.enableSkill(path, false) }, enabled = editable) { Text(stringResource(R.string.remove)) }
                }
            }
        }
        AiSkillCategory.entries.forEach { category ->
            val skills = filtered.filter { it.category == category }
            if (skills.isNotEmpty()) {
                item(key = category.name) {
                    Text(stringResource(when (category) {
                        AiSkillCategory.Coding -> R.string.ai_skills_coding
                        AiSkillCategory.Expression -> R.string.ai_skills_expression
                        AiSkillCategory.Project -> R.string.ai_skills_project
                    }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (category == AiSkillCategory.Project) Text(stringResource(R.string.ai_skills_project_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(skills, key = { it.path }) { skill ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        val maySelect = skill.path in state.enabledSkills || state.enabledSkills.size < AiSkills.MAX_ENABLED ||
                            (skill.path in AiBuiltinSkills.expressionStyles && state.enabledSkills.any { it in AiBuiltinSkills.expressionStyles })
                        val toggleLabel = stringResource(R.string.ai_skill_toggle, skill.name)
                        Checkbox(modifier = Modifier.semantics { contentDescription = toggleLabel }, checked = skill.path in state.enabledSkills, onCheckedChange = { controller.enableSkill(skill.path, it) }, enabled = skill.valid && editable && maySelect)
                        Column(Modifier.weight(1f).padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(skill.name, style = MaterialTheme.typography.titleSmall)
                            if (skill.recommended) Text(stringResource(R.string.ai_skill_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(if (skill.valid) skill.description else stringResource(R.string.ai_skill_invalid), style = MaterialTheme.typography.bodySmall, color = if (skill.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            if (!skill.builtIn) Text(skill.path, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                        IconButton(onClick = { viewing = skill }, enabled = skill.valid) {
                            WorkbenchIcon(Glyph.Info, Modifier.size(18.dp), description = stringResource(R.string.ai_skill_view, skill.name))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    viewing?.let { skill ->
        var text by remember(skill.path) { mutableStateOf<String?>(null) }
        var failed by remember(skill.path) { mutableStateOf(false) }
        LaunchedEffect(skill.path) {
            try { text = controller.skillText(skill.path) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        AiFullScreen(skill.name, { viewing = null }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (skill.builtIn) R.string.ai_skill_builtin_source else R.string.ai_skill_project_source), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(skill.description, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                if (failed) Text(stringResource(R.string.ai_skill_invalid), color = MaterialTheme.colorScheme.error)
                else if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else AiMarkdown(text.orEmpty()) {}
            }
        }
    }
}
@Composable
internal fun AiFullScreen(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        val dark = LocalWorkbenchDark.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.close)) }
                    Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
private fun requestElapsed(request: AiRequestLog): Long {
    val now by produceState(System.currentTimeMillis(), request.id, request.complete) {
        while (!request.complete) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    return if (request.complete) request.elapsed else maxOf(request.elapsed, now - request.started)
}

@Composable
internal fun AiSectionTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.textButtonColors(contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Max)) {
            Text(label, maxLines = 1, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Surface(Modifier.fillMaxWidth().height(2.dp), color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent) { }
        }
    }
}

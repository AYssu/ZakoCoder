package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uses the durable usage ledger, never the bounded request-detail list. */
@Composable
internal fun AiUsageDashboard(
    state: AiUsageState,
    projectNames: Map<String, String>,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    var period by rememberSaveable { mutableStateOf("month") }
    var day by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val date = LocalDate.ofEpochDay(day)
    val first = if (period == "day") date else date.withDayOfMonth(1)
    val until = if (period == "day") first.plusDays(1) else first.plusMonths(1)
    val zone = ZoneId.systemDefault()
    val filtered = remember(state, first, until, zone, project, model) {
        AiUsageStatistics.filter(state, first.atStartOfDay(zone).toInstant().toEpochMilli(),
            until.atStartOfDay(zone).toInstant().toEpochMilli(), projectId = project, model = model)
    }
    val summary = remember(filtered) { AiUsageStatistics.summarize(filtered.requests, filtered.tools) }
    val unknownProject = stringResource(R.string.ai_usage_removed_project)
    val allProjects = stringResource(R.string.ai_usage_all_projects)
    val allModels = stringResource(R.string.ai_usage_all_models)
    val projects = remember(state.requests, state.tools, projectNames, unknownProject) {
        (state.requests.map { it.projectId } + state.tools.map { it.projectId } + projectNames.keys)
            .distinct().sortedBy { projectNames[it] ?: it }
            .map { it to (projectNames[it] ?: "$unknownProject · ${it.take(8)}") }
    }
    val missingModel = stringResource(R.string.ai_usage_missing_model)
    val models = remember(state.requests, state.tools, missingModel) {
        (state.requests.map { it.model } + state.tools.map { it.model }).distinct().sorted().map { it to it.ifBlank { missingModel } }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("day" to R.string.ai_usage_day, "month" to R.string.ai_usage_month).forEach { (value, label) ->
                        FilterChip(selected = period == value, onClick = { period = value },
                            label = { Text(stringResource(label)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { day = (if (period == "day") date.minusDays(1) else first.minusMonths(1)).toEpochDay() }) {
                        WorkbenchIcon(Glyph.Back, Modifier.size(20.dp), description = stringResource(R.string.ai_usage_previous_period))
                    }
                    Text(first.format(DateTimeFormatter.ofPattern(if (period == "day") "yyyy-MM-dd" else "yyyy-MM", Locale.ROOT)),
                        Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { day = until.toEpochDay() }, enabled = until <= today) {
                        WorkbenchIcon(Glyph.Next, Modifier.size(20.dp), description = stringResource(R.string.ai_usage_next_period))
                    }
                }
                UsageFilterRow(stringResource(R.string.ai_usage_project), project?.let { id -> projects.firstOrNull { it.first == id }?.second } ?: allProjects) { choosing = "project" }
                UsageFilterRow(stringResource(R.string.ai_usage_model), model?.ifBlank { missingModel } ?: allModels) { choosing = "model" }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (state.truncated || state.incomplete) R.string.ai_usage_partial_title else R.string.ai_usage_scope_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.ai_usage_refresh)) }
            }
            UsageLoadState(state, onRefresh)
        }
        if (!state.loading && !state.failed) {
            if (filtered.requests.isEmpty() && filtered.tools.isEmpty()) item {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = cardBorder()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ai_usage_empty), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.ai_usage_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                if (state.incomplete) Text(stringResource(R.string.ai_usage_incomplete), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.truncated) UsageRetainedNote(state)
                AiUsageSummaryCard(summary)
            }
            item { UsageScopeNote(state) }
        }
    }
    choosing?.let { kind ->
        val entries = if (kind == "project") projects else models
        val selected = if (kind == "project") project else model
        UsageChoiceSheet(stringResource(if (kind == "project") R.string.ai_usage_project else R.string.ai_usage_model),
            if (kind == "project") allProjects else allModels, entries, selected,
            { value -> if (kind == "project") project = value else model = value; choosing = null }, { choosing = null })
    }
}

@Composable
private fun UsageFilterRow(label: String, value: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = cardBorder()) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun UsageChoiceSheet(title: String, all: String, entries: List<Pair<String, String>>, selected: String?,
    choose: (String?) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = remember(entries, query) { entries.filter { it.second.contains(query, ignoreCase = true) }.take(100) }
    WorkbenchSheet(title, dismiss) {
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.ai_usage_search)) })
        UsageChoiceRow(all, selected == null) { choose(null) }
        visible.forEach { (id, label) -> UsageChoiceRow(label, selected == id) { choose(id) } }
        if (visible.isEmpty()) Text(stringResource(R.string.ai_usage_no_matches), style = MaterialTheme.typography.bodySmall)
        if (entries.count { it.second.contains(query, ignoreCase = true) } > visible.size)
            Text(stringResource(R.string.ai_usage_refine_search), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun AiSessionUsageSummary(state: AiUsageState?, projectId: String?, conversationId: String, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_usage_session_summary), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        if (state == null || projectId == null) {
            Text(stringResource(R.string.ai_usage_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            UsageLoadState(state, onRefresh)
            if (!state.loading && !state.failed) {
                val filtered = remember(state, projectId, conversationId) { AiUsageStatistics.filter(state, Long.MIN_VALUE, Long.MAX_VALUE, projectId = projectId, conversationId = conversationId) }
                val summary = remember(filtered) { AiUsageStatistics.summarize(filtered.requests, filtered.tools) }
                if (state.incomplete) Text(stringResource(R.string.ai_usage_incomplete), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.truncated) UsageRetainedNote(state)
                AiUsageSummaryCard(summary)
                UsageScopeNote(state)
            }
        }
    }
}

@Composable
private fun UsageLoadState(state: AiUsageState, onRefresh: () -> Unit) {
    if (state.loading) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_usage_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    } else if (state.failed) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ai_usage_load_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.ai_usage_retry)) }
            }
        }
    }
}

@Composable
internal fun AiUsageSummaryCard(summary: AiUsageSummary) {
    val unknown = stringResource(R.string.ai_usage_unknown)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UsageMetricRow(R.string.ai_usage_requests, summary.requestCount.toString(), R.string.ai_usage_tools, summary.toolCount.toString())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            UsageMetricRow(R.string.ai_usage_dashboard_input, summary.inputTokens?.let(::tokenWindowLabel) ?: unknown,
                R.string.ai_usage_dashboard_output, summary.outputTokens?.let(::tokenWindowLabel) ?: unknown)
            UsageMetricRow(R.string.ai_usage_dashboard_total, summary.totalTokens?.let(::tokenWindowLabel) ?: unknown,
                R.string.ai_usage_cache_hit, summary.cachedTokens?.let(::tokenWindowLabel) ?: unknown)
            Text(stringResource(R.string.ai_usage_request_status, summary.failedRequestCount, summary.pendingRequestCount, summary.executedToolCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.requestCount > 0) Text(stringResource(R.string.ai_usage_coverage, summary.inputReported,
                summary.outputReported, summary.cachedReported, summary.requestCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ai_usage_cache_ratio, summary.cacheRatio?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: unknown),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.ai_usage_context_estimate, summary.latestEstimatedContextTokens?.let(::tokenWindowLabel) ?: unknown,
                summary.maxContextWindowTokens?.toLong()?.let(::tokenWindowLabel) ?: unknown),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ai_usage_values_hint), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageMetricRow(firstLabel: Int, first: String, secondLabel: Int, second: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsageMetric(firstLabel, first, Modifier.weight(1f))
        UsageMetric(secondLabel, second, Modifier.weight(1f))
    }
}

@Composable
private fun UsageMetric(label: Int, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UsageScopeNote(state: AiUsageState) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneId.systemDefault()) }
    val start = if (state.startedAt > 0) formatter.format(Instant.ofEpochMilli(state.startedAt)) else stringResource(R.string.ai_usage_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_usage_ledger_scope, start), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_usage_retention_policy), fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UsageRetainedNote(state: AiUsageState) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneId.systemDefault()) }
    Text(stringResource(R.string.ai_usage_retained_scope,
        state.retainedFrom?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.ai_usage_unknown)),
        modifier = Modifier.padding(bottom = 8.dp), fontSize = 12.sp, lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

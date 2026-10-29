package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

@Composable
internal fun AiTaskPlanPanel(state: AiState, panel: Int, visible: Boolean) {
    key(state.projectId, state.conversationId, state.turn) {
        // Stay composed while IME hides the header. Only this task's five tab choices are retained.
        val holder = rememberSaveableStateHolder()
        if (visible && state.plan.isNotEmpty()) holder.SaveableStateProvider("plan") {
            AiPlanPanel(state.plan, state.active, panel)
        }
    }
}

@Composable
internal fun AiPlanPanel(plan: List<AiPlanStep>, active: Boolean, panel: Int) {
    // The caller keys this panel by project/conversation/turn. Keep only five tab choices.
    // -1 follows the live default until the user explicitly chooses; plan updates never reset it.
    var choices by rememberSaveable { mutableStateOf(IntArray(5) { -1 }) }
    val expanded = when (choices[panel]) { 1 -> true; 0 -> false; else -> active && panel == 0 }
    val action = stringResource(if (expanded) R.string.ai_plan_collapse else R.string.ai_plan_expand)
    val reported = stringResource(R.string.ai_plan_reported)
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = reported }
                .clickable(role = Role.Button, onClickLabel = action) {
                    choices = choices.copyOf().also { it[panel] = if (expanded) 0 else 1 }
                }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkbenchIcon(if (expanded) Glyph.Down else Glyph.Next, Modifier.size(16.dp))
                Text(stringResource(R.string.ai_current_plan), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.ai_plan_progress, plan.count { it.status == "completed" }, plan.size), style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) Column(Modifier.heightIn(max = 145.dp).verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.ai_plan_reported), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan.forEachIndexed { index, step -> Row {
                    Text("${index + 1}.", Modifier.width(22.dp), fontSize = 12.sp)
                    Text(step.title, Modifier.weight(1f), fontSize = 12.sp)
                    Text(stringResource(when (step.status) { "completed" -> R.string.ai_plan_completed; "in_progress" -> R.string.ai_plan_active; else -> R.string.ai_plan_pending }), fontSize = 10.sp,
                        color = if (step.status == "in_progress") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}

@Composable
internal fun AiToolCard(event: AiToolEvent, terminalNumber: Int? = null, onTerminal: () -> Unit = {}) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val expandAction = stringResource(if (expanded) R.string.ai_tool_collapse else R.string.ai_tool_details)
    val expandState = stringResource(if (expanded) R.string.mobile_expanded else R.string.mobile_collapsed)
    val color = when (event.status) {
        AiToolStatus.Failed -> MaterialTheme.colorScheme.error
        AiToolStatus.Waiting, AiToolStatus.Running -> MaterialTheme.colorScheme.primary
        AiToolStatus.Succeeded -> androidx.compose.ui.graphics.Color(if (LocalWorkbenchDark.current) 0xFF6EE7B7 else 0xFF087F5B)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = expandState }
                    .clickable(role = Role.Button, onClickLabel = expandAction) { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    WorkbenchIcon(when (event.tool) { AiTool.RunCommand, AiTool.ReadCommandLog, AiTool.ListTerminals, AiTool.ReadTerminal, AiTool.StartTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal -> Glyph.Terminal; AiTool.SearchFiles -> Glyph.Search; AiTool.UpdatePlan -> Glyph.Check; else -> Glyph.File }, Modifier.size(16.dp), tint = color)
                    Text(event.tool?.let { stringResource(it.label) } ?: event.name, Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(event.status.label), color = color, style = MaterialTheme.typography.labelSmall)
                    WorkbenchIcon(if (expanded) Glyph.Up else Glyph.Down, Modifier.padding(start = 8.dp).size(14.dp))
                }
                if (event.target.isNotEmpty()) Text(event.target, maxLines = if (expanded) 8 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text(event.reason.ifBlank { stringResource(R.string.ai_reason_missing) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.status == AiToolStatus.Incomplete && event.preview == stringResource(R.string.ai_rules_deferred))
                    Text(stringResource(R.string.ai_rules_deferred_visible), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_tool_authority, stringResource(event.permissionSource), stringResource(event.permission.label)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    event.exitCode?.let { Text(stringResource(R.string.ai_tool_exit_code, it), style = MaterialTheme.typography.labelSmall, color = color) }
                    if (event.finished != null && event.started != null) Text(stringResource(R.string.ai_tool_elapsed, maxOf(0, event.finished - event.started)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (terminalNumber != null) TextButton(onClick = onTerminal) {
                    WorkbenchIcon(Glyph.Terminal, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_open_terminal, terminalNumber))
                }
                if (expanded) {
                    HorizontalDivider()
                    Text(stringResource(R.string.ai_tool_parameters), style = MaterialTheme.typography.labelSmall)
                    CodeDetail(event.input)
                    if (event.preview.isNotEmpty()) CodeDetail(event.preview)
                    if (event.output.isNotEmpty()) { Text(stringResource(R.string.ai_tool_output), style = MaterialTheme.typography.labelSmall); CodeDetail(event.output) }
                    if (event.truncated) Text(stringResource(R.string.ai_output_truncated), style = MaterialTheme.typography.labelSmall)
                } else if (event.status == AiToolStatus.Failed && event.output.isNotEmpty()) {
                    Text(event.output.take(800), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else if (event.tool == AiTool.RunCommand && event.output.isNotEmpty()) {
                    Text(event.output.takeLast(400), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 4)
                }
            }
        }
    }
}

@Composable
private fun CodeDetail(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, Modifier.fillMaxWidth().heightIn(max = 240.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

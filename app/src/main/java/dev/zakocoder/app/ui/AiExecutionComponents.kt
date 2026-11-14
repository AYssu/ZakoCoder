package dev.zakocoder.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

internal fun aiToolGlyph(tool: AiTool?): Glyph = when (tool) {
    AiTool.RunCommand, AiTool.ReadCommandLog, AiTool.ListTerminals, AiTool.ReadTerminal,
    AiTool.StartTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal, AiTool.SshRemote -> Glyph.Terminal
    AiTool.ReadFile, AiTool.WriteFile, AiTool.EditFile -> Glyph.File
    AiTool.ListFiles, AiTool.MakeDirectory -> Glyph.Projects
    AiTool.SearchFiles, AiTool.SearchMemory -> Glyph.Search
    AiTool.UpdatePlan, AiTool.SetTaskMode -> Glyph.History
    AiTool.Browser -> Glyph.Globe
    AiTool.AndroidTest -> Glyph.ReadOnly
    AiTool.AskUser, AiTool.DelegateReadOnly -> Glyph.Ai
    else -> Glyph.Extensions
}

@Composable
internal fun AiTaskPlanPanel(state: AiState, panel: Int, visible: Boolean) {
    key(state.projectId, state.conversationId, state.turn) {
        // Stay composed while IME hides the header. Only this task's five tab choices are retained.
        val holder = rememberSaveableStateHolder()
        AnimatedVisibility(visible && state.plan.isNotEmpty(),
            enter = fadeIn(tween(140)) + expandVertically(tween(200)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(180))) {
            holder.SaveableStateProvider("plan") { AiPlanPanel(state.plan, state.active, panel) }
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
    WorkbenchCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = reported }
                .clickable(role = Role.Button, onClickLabel = action) {
                    choices = choices.copyOf().also { it[panel] = if (expanded) 0 else 1 }
                }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkbenchIcon(Glyph.History, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_current_plan), Modifier.weight(1f).padding(start = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_plan_progress, plan.count { it.status == "completed" }, plan.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkbenchIcon(if (expanded) Glyph.Down else Glyph.Next, Modifier.padding(start = 6.dp).size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(tween(140)) + expandVertically(tween(200)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(180))) { Column(Modifier.heightIn(max = 145.dp).verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                plan.forEachIndexed { index, step -> Row {
                    Text("${index + 1}.", Modifier.width(20.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ArrivalText(step.title, Modifier.weight(1f).padding(end = 8.dp), fontSize = 11.sp, lineHeight = 17.sp, animated = active, color = if (step.status == "in_progress") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(when (step.status) { "completed" -> R.string.ai_plan_completed; "in_progress" -> R.string.ai_plan_active; else -> R.string.ai_plan_pending }), fontSize = 10.sp,
                        color = if (step.status == "in_progress") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            } }
        }
    }
}

@Composable
internal fun AiToolCard(event: AiToolEvent, terminalNumber: Int? = null, onTerminal: () -> Unit = {},
    revealRequest: String? = null, highlighted: Boolean = false) {
    var expanded by remember(event.id) { mutableStateOf(revealRequest != null) }
    val resultLocation = remember(event.id) { BringIntoViewRequester() }
    LaunchedEffect(revealRequest) {
        if (revealRequest != null) {
            expanded = true
            kotlinx.coroutines.delay(240)
            if (expanded) resultLocation.bringIntoView()
        }
    }
    val expandAction = stringResource(if (expanded) R.string.ai_tool_collapse else R.string.ai_tool_details)
    val expandState = stringResource(if (expanded) R.string.mobile_expanded else R.string.mobile_collapsed)
    val color = if (event.status == AiToolStatus.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().then(if (event.output.isEmpty()) Modifier.bringIntoViewRequester(resultLocation) else Modifier).background(if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .25f)
        else androidx.compose.ui.graphics.Color.Transparent), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = expandState }
            .clickable(role = Role.Button, onClickLabel = expandAction) { expanded = !expanded }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIcon(aiToolGlyph(event.tool), Modifier.size(14.dp), tint = color)
            Text(event.tool?.let { stringResource(it.label) } ?: event.name,
                fontSize = 11.sp, color = color, maxLines = 1)
            ArrivalText(if (event.status == AiToolStatus.Running) event.target.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().takeLast(120) else event.target, Modifier.weight(1f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, animated = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crossfade(event.status, animationSpec = tween(140), label = "tool-state") { status ->
                Text(stringResource(status.label), color = color, fontSize = 10.sp)
            }
            WorkbenchIcon(if (expanded) Glyph.Up else Glyph.Down, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Pending approval and failures stay visible without requiring the detail disclosure.
        if (event.status == AiToolStatus.Waiting) {
            Text(event.reason.ifBlank { stringResource(R.string.ai_reason_missing) },
                style = MaterialTheme.typography.bodySmall, color = color)
            Text(stringResource(R.string.ai_tool_authority, stringResource(event.permissionSource),
                stringResource(event.permission.label)), style = MaterialTheme.typography.labelSmall, color = color)
        }
        if (event.status == AiToolStatus.Incomplete && event.preview == stringResource(R.string.ai_rules_deferred))
            Text(stringResource(R.string.ai_rules_deferred_visible), style = MaterialTheme.typography.bodySmall, color = color)
        AnimatedVisibility(expanded, enter = fadeIn(tween(140)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160))) {
            Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (event.target.isNotEmpty()) Text(event.target, fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(event.reason.ifBlank { stringResource(R.string.ai_reason_missing) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.ai_tool_authority, stringResource(event.permissionSource),
                    stringResource(event.permission.label)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.finished != null && event.started != null) Text(
                    stringResource(R.string.ai_tool_elapsed, maxOf(0, event.finished - event.started)),
                    style = MaterialTheme.typography.labelSmall, color = color)
                event.exitCode?.let { Text(stringResource(R.string.ai_tool_exit_code, it),
                    style = MaterialTheme.typography.labelSmall, color = color) }
                Text(stringResource(R.string.ai_tool_parameters), style = MaterialTheme.typography.labelSmall)
                CodeDetail(event.input)
                if (event.preview.isNotEmpty()) CodeDetail(event.preview)
                if (event.output.isNotEmpty()) {
                    Text(stringResource(R.string.ai_tool_output), style = MaterialTheme.typography.labelSmall)
                    CodeDetail(event.output, Modifier.bringIntoViewRequester(resultLocation), animated = event.status == AiToolStatus.Running)
                }
                if (event.truncated) Text(stringResource(R.string.ai_output_truncated),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!expanded && event.status == AiToolStatus.Failed && event.output.isNotEmpty()) {
            Text(event.output.take(800), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (terminalNumber != null) TextButton(onClick = onTerminal, modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.ai_open_terminal, terminalNumber), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CodeDetail(text: String, modifier: Modifier = Modifier, animated: Boolean = false) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        ArrivalText(text, modifier.fillMaxWidth().heightIn(max = 240.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, animated = animated)
    }
}

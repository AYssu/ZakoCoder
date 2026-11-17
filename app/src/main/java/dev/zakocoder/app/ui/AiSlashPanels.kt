package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.RadioButton

/** Local slash actions reuse the app sheet; no command or process is launched by these views. */
@Composable
internal fun AiSlashPanels(
    workbench: Workbench,
    state: AiState,
    action: AiSlashCommandId,
    argument: String,
    onDismiss: () -> Unit,
    onSkillChosen: (AiSkill) -> Unit,
    onSkills: () -> Unit,
) {
    key(action, state.projectId, state.conversationId) {
        when (action) {
            AiSlashCommandId.Help -> WorkbenchSheet(stringResource(R.string.ai_slash_help_title), onDismiss) {
                SlashPanelText(stringResource(R.string.ai_slash_help_hint), secondary = true)
                AiSlashCommands.catalog.forEach { command ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("/${command.token}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                        SlashPanelText(stringResource(slashCommandLabel(command.id)), secondary = true)
                    }
                }
            }
            AiSlashCommandId.Export, AiSlashCommandId.Import, AiSlashCommandId.Recap ->
                AiConversationTransferSheet(workbench.ai, state, action,
                    workbench.state.collectAsStateWithLifecycle().value.projects.firstOrNull { it.id == state.projectId }?.name.orEmpty(), onDismiss)
            AiSlashCommandId.Rename -> SlashRenamePanel(workbench, state, argument, onDismiss)
            AiSlashCommandId.Compact -> SlashCompactPanel(workbench, state, argument, onDismiss)
            AiSlashCommandId.Context -> SlashContextPanel(workbench, state, onDismiss)
            AiSlashCommandId.Thinking -> SlashThinkingPanel(workbench, state, onDismiss)
            AiSlashCommandId.Skill -> SlashSkillPanel(state, argument, onDismiss, onSkillChosen, onSkills)
            else -> Unit
        }
    }
}

@Composable
private fun SlashRenamePanel(workbench: Workbench, state: AiState, argument: String, onDismiss: () -> Unit) {
    var name by rememberSaveable {
        mutableStateOf(argument.ifBlank { state.conversations.firstOrNull { it.id == state.conversationId }?.title.orEmpty() })
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = name.trim().isNotEmpty() && name.length <= 80 && name.none(Char::isISOControl)
    WorkbenchSheet(stringResource(R.string.ai_slash_rename_title), onDismiss) {
        WorkbenchTextField(name, { name = it; failed = false }, Modifier.fillMaxWidth(),
            enabled = !saving, singleLine = true, label = stringResource(R.string.ai_slash_rename_hint),
            textStyle = MaterialTheme.typography.bodySmall, isError = !valid && name.isNotEmpty(),
            supportingText = { SlashPanelText(stringResource(R.string.ai_slash_panel_rename_limit), secondary = true) })
        if (failed) SlashPanelText(stringResource(R.string.ai_slash_panel_rename_failed), error = true)
        WorkbenchPrimaryButton(onClick = {
            saving = true
            scope.launch {
                try { if (workbench.ai.renameConversation(name)) onDismiss() else failed = true }
                finally { saving = false }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = valid && !saving && !state.active && !state.restoring &&
            !state.permissionsLoading && !state.permissionsSaving && !state.historyError) {
            SlashPanelText(stringResource(R.string.editor_save))
        }
    }
}

@Composable
private fun SlashCompactPanel(workbench: Workbench, state: AiState, argument: String, onDismiss: () -> Unit) {
    var instructions by rememberSaveable { mutableStateOf(argument) }
    var failed by remember { mutableStateOf(false) }
    val hasContext = state.contextSummary.isNotBlank() || state.contextTurns.isNotEmpty()
    WorkbenchSheet(stringResource(R.string.ai_slash_compact_title), onDismiss) {
        SlashPanelText(stringResource(R.string.ai_slash_compact_message), secondary = true)
        WorkbenchTextField(instructions, { instructions = it; failed = false }, Modifier.fillMaxWidth(),
            label = stringResource(R.string.ai_slash_compact_keep_hint), minLines = 2, maxLines = 5,
            textStyle = MaterialTheme.typography.bodySmall, isError = instructions.length > 2000,
            supportingText = { SlashPanelText(stringResource(R.string.ai_slash_panel_compact_limit), secondary = true) })
        if (!hasContext) SlashPanelText(stringResource(R.string.ai_slash_panel_compact_empty), secondary = true)
        if (failed) SlashPanelText(stringResource(R.string.ai_slash_panel_compact_failed), error = true)
        WorkbenchPrimaryButton(onClick = {
            if (workbench.ai.compactConversation(instructions)) onDismiss() else failed = true
        }, modifier = Modifier.fillMaxWidth(), enabled = hasContext && instructions.length <= 2000 && state.configured &&
            !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving && !state.historyError) {
            SlashPanelText(stringResource(R.string.ai_slash_panel_compact_action))
        }
    }
}

@Composable
private fun SlashContextPanel(workbench: Workbench, state: AiState, onDismiss: () -> Unit) {
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val window = state.contextWindowTokens.takeIf { it > 0 }
        ?: models.catalog.active?.context?.contextWindowTokens?.takeIf { it > 0 }
    fun number(value: Long) = String.format(java.util.Locale.ROOT, "%,d", value)
    WorkbenchSheet(stringResource(R.string.ai_slash_panel_context_title), onDismiss,
        subtitle = stringResource(R.string.ai_slash_context_summary)) {
        if (window != null) SlashPanelText(stringResource(R.string.ai_slash_panel_context_usage,
            number(state.estimatedContextTokens), number(window.toLong())))
        else SlashPanelText(stringResource(R.string.ai_slash_panel_context_unknown,
            number(state.estimatedContextTokens)), secondary = true)
        SlashPanelText(stringResource(R.string.ai_slash_panel_context_reserve, number(state.contextOutputReserveTokens.toLong())), secondary = true)
        SlashPanelText(stringResource(R.string.ai_slash_panel_context_compactions, state.contextCompactions), secondary = true)
        SlashPanelText(stringResource(R.string.ai_slash_panel_context_hint), secondary = true)
        SlashPanelText(stringResource(R.string.ai_slash_panel_summary_title))
        SelectionContainer { SlashPanelText(state.contextSummary.ifBlank { stringResource(R.string.ai_slash_panel_summary_empty) }, secondary = true) }
    }
}

@Composable
private fun SlashThinkingPanel(workbench: Workbench, state: AiState, onDismiss: () -> Unit) {
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val profile = models.catalog.active
    if (profile == null) {
        WorkbenchSheet(stringResource(R.string.ai_slash_panel_thinking_title), onDismiss) {
            SlashPanelText(stringResource(R.string.ai_slash_panel_thinking_missing), secondary = true)
        }
        return
    }
    key(profile.id) {
        var thinking by rememberSaveable { mutableStateOf(profile.parameters.thinking) }
        var effort by rememberSaveable { mutableStateOf(profile.parameters.reasoningEffort) }
        var savingRevision by remember { mutableStateOf<Int?>(null) }
        val parameters = profile.parameters.copy(thinking = thinking, reasoningEffort = effort)
        val editable = !state.active && !state.restoring && !models.loading && !models.saving
        val valid = protocolParametersSupported(profile.protocol, parameters)
        LaunchedEffect(models.revision, models.saving, models.error) {
            if (savingRevision != null && models.revision != savingRevision && !models.saving && models.error.isBlank()) onDismiss()
        }
        WorkbenchSheet(stringResource(R.string.ai_slash_panel_thinking_title), onDismiss) {
            SlashPanelText(profile.name)
            SlashPanelText(stringResource(R.string.ai_slash_panel_thinking_hint), secondary = true)
            SlashPanelText(stringResource(R.string.ai_options_thinking), secondary = true)
            AiThinking.entries.forEach { option ->
                SlashChoice(stringResource(when (option) {
                    AiThinking.Default -> R.string.ai_options_provider_default
                    AiThinking.Enabled -> R.string.ai_options_enabled
                    AiThinking.Disabled -> R.string.ai_options_disabled
                }), selected = thinking == option, enabled = editable) {
                    thinking = option
                    if (option == AiThinking.Disabled) effort = null
                }
            }
            SlashPanelText(stringResource(R.string.ai_options_effort), secondary = true)
            (listOf<AiReasoningEffort?>(null) + AiReasoningEffort.entries).forEach { option ->
                SlashChoice(if (option == null) stringResource(R.string.ai_options_provider_default) else stringResource(option.labelResource()),
                    selected = effort == option, enabled = editable && (option == null || thinking != AiThinking.Disabled) &&
                        (profile.protocol != AiApiProtocol.Gemini || option != AiReasoningEffort.Max)) { effort = option }
            }
            if (!valid) SlashPanelText(stringResource(protocolParametersHint(profile.protocol)), error = true)
            if (models.error.isNotEmpty()) {
                SlashPanelText(models.error, error = true)
                WorkbenchSecondaryButton(onClick = { workbench.ai.models.clearError() }, enabled = editable) {
                    SlashPanelText(stringResource(R.string.ai_slash_panel_model_error_retry))
                }
            }
            WorkbenchPrimaryButton(onClick = {
                savingRevision = models.revision
                workbench.ai.models.save(AiModelDraft(profile.id, profile.name, profile.endpoint, profile.model,
                    replacementKey = null, parameters = parameters, context = profile.context,
                    replacementHeaders = null, protocol = profile.protocol, systemPrompt = profile.systemPrompt))
            }, modifier = Modifier.fillMaxWidth(), enabled = editable && valid && models.error.isBlank()) {
                SlashPanelText(stringResource(R.string.editor_save))
            }
        }
    }
}

@Composable
private fun SlashSkillPanel(state: AiState, argument: String, onDismiss: () -> Unit,
    onSkillChosen: (AiSkill) -> Unit, onSkills: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(argument) }
    val skills = remember(query, state.skills) {
        state.skills.filter { it.valid && (it.name.contains(query, true) || it.description.contains(query, true)) }
    }
    WorkbenchSheet(stringResource(R.string.ai_slash_panel_skill_title), onDismiss) {
        SlashPanelText(stringResource(R.string.ai_slash_panel_skill_hint), secondary = true)
        WorkbenchTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall, label = stringResource(R.string.ai_skills_search))
        when {
            state.skillsLoading -> SlashPanelText(stringResource(R.string.ai_skills_loading_local), secondary = true)
            state.skillError -> SlashPanelText(stringResource(R.string.ai_slash_panel_skill_error), error = true)
            skills.isEmpty() -> SlashPanelText(stringResource(R.string.ai_skills_empty), secondary = true)
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(skills, key = { it.path }) { skill ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onSkillChosen(skill) }
                        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlashPanelText(skill.name)
                        SlashPanelText(skill.description, secondary = true)
                        Text(skill.path, fontSize = 11.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        WorkbenchSecondaryButton(onClick = { onDismiss(); onSkills() }, modifier = Modifier.fillMaxWidth()) {
            SlashPanelText(stringResource(R.string.ai_slash_panel_skill_manage))
        }
    }
}

@Composable
private fun SlashChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SlashPanelText(label, modifier = Modifier.weight(1f), secondary = !enabled)
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

@Composable
private fun SlashPanelText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false, error: Boolean = false) {
    Text(text, modifier, fontSize = 12.sp, lineHeight = 18.sp,
        color = when { error -> MaterialTheme.colorScheme.error; secondary -> MaterialTheme.colorScheme.onSurfaceVariant; else -> androidx.compose.ui.graphics.Color.Unspecified })
}

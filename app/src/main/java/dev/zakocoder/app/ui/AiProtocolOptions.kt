package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.basic.RadioButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

internal fun protocolLabel(protocol: AiApiProtocol): Int = when (protocol) {
    AiApiProtocol.ChatCompletions -> R.string.ai_protocol_chat
    AiApiProtocol.Responses -> R.string.ai_protocol_responses
    AiApiProtocol.Anthropic -> R.string.ai_protocol_anthropic
    AiApiProtocol.Gemini -> R.string.ai_protocol_gemini
}

internal fun protocolParametersSupported(protocol: AiApiProtocol, parameters: AiParameters): Boolean {
    val effective = AiServiceRequestPolicy.effectiveConfig(AiConfig(parameters = parameters)).parameters
    val conflictingThinking = effective.thinking == AiThinking.Disabled && effective.reasoningEffort != null
    val penalties = effective.seed != null || effective.frequencyPenalty != null || effective.presencePenalty != null
    return when (protocol) {
        AiApiProtocol.ChatCompletions -> true
        AiApiProtocol.Responses -> !penalties && !conflictingThinking
        AiApiProtocol.Anthropic -> !penalties && !conflictingThinking && (effective.temperature == null || effective.temperature <= 1.0) &&
            (effective.thinking != AiThinking.Enabled || (effective.temperature == null && effective.topP == null &&
                (effective.maxTokens == null || effective.maxTokens > 1024)))
        AiApiProtocol.Gemini -> effective.reasoningEffort != AiReasoningEffort.Max && !conflictingThinking
    }
}

internal fun protocolParametersHint(protocol: AiApiProtocol): Int = when (protocol) {
    AiApiProtocol.ChatCompletions -> R.string.ai_protocol_chat_parameters_hint
    AiApiProtocol.Responses -> R.string.ai_protocol_responses_parameters_hint
    AiApiProtocol.Anthropic -> R.string.ai_protocol_anthropic_parameters_hint
    AiApiProtocol.Gemini -> R.string.ai_protocol_gemini_parameters_hint
}

/** One compact field in the editor; protocol decisions live in a separate native sheet. */
@Composable
internal fun AiProtocolOptions(protocol: AiApiProtocol, editable: Boolean, update: (AiApiProtocol) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val description = stringResource(R.string.ai_protocol_choose)
    WorkbenchCard(cornerRadius = 12.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().semantics { contentDescription = description }.clickable(enabled = editable) { choosing = true }
            .heightIn(min = 56.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(protocolLabel(protocol)), style = MaterialTheme.typography.labelLarge)
                Text(protocol.requestPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WorkbenchIcon(Glyph.Next, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (choosing && editable) WorkbenchSheet(stringResource(R.string.ai_protocol_choose), { choosing = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.ai_protocol_support_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CopyHelp(stringResource(R.string.copy_ai_protocol_help_title), stringResource(R.string.copy_ai_protocol_help))
            listOf(R.string.ai_protocol_group_openai to listOf(AiApiProtocol.ChatCompletions, AiApiProtocol.Responses),
                R.string.ai_protocol_group_anthropic to listOf(AiApiProtocol.Anthropic),
                R.string.ai_protocol_group_google to listOf(AiApiProtocol.Gemini)).forEach { (label, entries) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WorkbenchCard(cornerRadius = 12.dp) {
                        Column(Modifier.selectableGroup()) {
                            entries.forEachIndexed { index, option ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth().selectable(option == protocol, role = Role.RadioButton,
                                    onClick = { choosing = false; update(option) }).heightIn(min = 56.dp).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(protocolLabel(option)), style = MaterialTheme.typography.labelLarge)
                                        Text(option.requestPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    RadioButton(option == protocol, onClick = null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AiProtocolRequestAddress(base: String, protocol: AiApiProtocol, model: String = "", streaming: Boolean = true) {
    val address = runCatching { AiProviderRouting.requestAddress(base, protocol, model, streaming) }.getOrNull()
    Text(if (address == null) stringResource(R.string.ai_protocol_address_needs_model)
        else stringResource(R.string.ai_models_request_url, address), style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AiProviderPresetList(editable: Boolean, choose: (AiProviderPreset) -> Unit) {
    Text(stringResource(R.string.ai_provider_presets_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    WorkbenchCard(cornerRadius = 12.dp) {
        Column {
            AiProviderPresets.all.forEachIndexed { index, preset ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable(enabled = editable) { choose(preset) }.heightIn(min = 64.dp).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(preset.label), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(protocolLabel(preset.protocol)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(preset.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WorkbenchIcon(Glyph.Next, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

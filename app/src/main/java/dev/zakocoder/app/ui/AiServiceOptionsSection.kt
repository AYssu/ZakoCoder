package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

/** A preset summary is visible immediately; wire-level choices require opening advanced settings. */
@Composable
internal fun AiServiceOptionsSection(protocol: AiApiProtocol, value: AiServiceOptions, editable: Boolean,
    endpoint: String = "", update: (AiServiceOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val preset = AiServicePresets.provider(endpoint, protocol)
    val effective = AiServicePresets.resolve(value, endpoint, protocol)
    Text(stringResource(if (value.usePreset) R.string.ai_service_preset else R.string.ai_service_custom),
        style = MaterialTheme.typography.labelLarge)
    Text(if (!value.usePreset) stringResource(R.string.ai_service_existing_hint)
        else if (preset != null) stringResource(R.string.ai_service_preset_named, stringResource(preset.label))
        else stringResource(R.string.ai_service_preset_generic),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (protocol == AiApiProtocol.ChatCompletions && effective.chatThinking == AiChatThinkingFormat.EffortOnly)
        ServiceHint(R.string.ai_service_effort_only_hint)
    WorkbenchTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_service_title), Modifier.weight(1f))
        WorkbenchIcon(if (open) Glyph.Down else Glyph.Next)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_protocol_auto_defaults), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_service_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ServiceToggle(R.string.ai_service_preset, value.usePreset, editable) { automatic ->
            update(if (automatic) value.copy(usePreset = true) else effective.copy(usePreset = false))
        }
        if (value.usePreset) {
            Text(stringResource(R.string.ai_service_manual_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        ServiceToggle(R.string.ai_service_streaming, value.streaming, editable) { update(value.copy(streaming = it)) }
        ServiceHint(R.string.ai_service_streaming_hint)
        ServiceToggle(R.string.ai_service_sampling, value.samplingParameters, editable) { update(value.copy(samplingParameters = it)) }
        Text(stringResource(R.string.ai_service_sampling_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (protocol) {
            AiApiProtocol.ChatCompletions -> {
                ServiceToggle(R.string.ai_service_stream_usage, value.streamUsage, editable && value.streaming) { update(value.copy(streamUsage = it)) }
                ServiceHint(R.string.ai_service_stream_usage_hint)
                ServiceChoice(R.string.ai_service_chat_thinking, value.chatThinking, AiChatThinkingFormat.entries,
                    editable, { when (it) {
                        AiChatThinkingFormat.ThinkingObject -> R.string.ai_service_thinking_object
                        AiChatThinkingFormat.EnableThinking -> R.string.ai_service_enable_thinking
                        AiChatThinkingFormat.ReasoningObject -> R.string.ai_service_reasoning_object
                        AiChatThinkingFormat.None -> R.string.ai_service_omit_thinking
                        AiChatThinkingFormat.EffortOnly -> R.string.ai_service_effort_only
                    } }, explanation = { when (it) {
                        AiChatThinkingFormat.ThinkingObject -> R.string.ai_service_thinking_object_help
                        AiChatThinkingFormat.EnableThinking -> R.string.ai_service_enable_thinking_help
                        AiChatThinkingFormat.ReasoningObject -> R.string.ai_service_reasoning_object_help
                        AiChatThinkingFormat.None -> R.string.ai_service_no_thinking_hint
                        AiChatThinkingFormat.EffortOnly -> R.string.ai_service_effort_only_hint
                    } }) { update(value.copy(chatThinking = it)) }
                ServiceHint(R.string.ai_service_thinking_hint)
                ServiceChoice(R.string.ai_service_token_limit, value.chatTokenLimit, AiChatTokenLimit.entries,
                    editable, { if (it == AiChatTokenLimit.MaxTokens) R.string.ai_service_max_tokens else R.string.ai_service_max_completion_tokens },
                    explanation = { if (it == AiChatTokenLimit.MaxTokens) R.string.ai_service_max_tokens_help else R.string.ai_service_max_completion_tokens_help }) {
                    update(value.copy(chatTokenLimit = it))
                }
                ServiceHint(R.string.ai_service_token_hint)
            }
            AiApiProtocol.Responses -> {
                ServiceToggle(R.string.ai_service_responses_summary, value.responsesSummary, editable) { update(value.copy(responsesSummary = it)) }
                ServiceHint(R.string.ai_service_summary_hint)
                ServiceToggle(R.string.ai_service_encrypted_reasoning, value.responsesEncryptedReasoning, editable) { update(value.copy(responsesEncryptedReasoning = it)) }
                ServiceHint(R.string.ai_service_encrypted_hint)
                ServiceToggle(R.string.ai_service_responses_cache, value.responsesCacheKey, editable) { update(value.copy(responsesCacheKey = it)) }
                Text(stringResource(R.string.ai_service_cache_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AiApiProtocol.Anthropic -> {
                ServiceChoice(R.string.ai_service_anthropic_cache, value.anthropicCache, AiAnthropicCache.entries,
                    editable, { when (it) {
                        AiAnthropicCache.Disabled -> R.string.ai_service_cache_disabled
                        AiAnthropicCache.FiveMinutes -> R.string.ai_service_cache_five_minutes
                        AiAnthropicCache.OneHour -> R.string.ai_service_cache_one_hour
                    } }) { update(value.copy(anthropicCache = it)) }
                Text(stringResource(R.string.ai_service_cache_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AiApiProtocol.Gemini -> Unit
        }
    }
}

@Composable
private fun ServiceHint(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServiceToggle(label: Int, checked: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    val description = stringResource(label)
    SwitchPreference(checked = checked, onCheckedChange = update, title = description,
        enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun <T> ServiceChoice(label: Int, selected: T, choices: List<T>, enabled: Boolean,
    resource: (T) -> Int, explanation: (T) -> Int? = { null }, update: (T) -> Unit) {
    WindowDropdownPreference(
        title = stringResource(label), items = choices.map { stringResource(resource(it)) },
        selectedIndex = choices.indexOf(selected).coerceAtLeast(0),
        summary = explanation(selected)?.let { stringResource(it) },
        onSelectedIndexChange = { update(choices[it]) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    )
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

/** Low-frequency endpoint settings stay inside the existing advanced editor. */
@Composable
internal fun AiServiceOptionsSection(protocol: AiApiProtocol, value: AiServiceOptions, editable: Boolean,
    update: (AiServiceOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_service_title), Modifier.weight(1f))
        WorkbenchIcon(if (open) Glyph.Down else Glyph.Next)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_service_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ServiceToggle(R.string.ai_service_streaming, value.streaming, editable) { update(value.copy(streaming = it)) }
        ServiceToggle(R.string.ai_service_sampling, value.samplingParameters, editable) { update(value.copy(samplingParameters = it)) }
        Text(stringResource(R.string.ai_service_sampling_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (protocol) {
            AiApiProtocol.ChatCompletions -> {
                ServiceToggle(R.string.ai_service_stream_usage, value.streamUsage, editable && value.streaming) { update(value.copy(streamUsage = it)) }
                ServiceChoice(R.string.ai_service_chat_thinking, value.chatThinking, AiChatThinkingFormat.entries,
                    editable, { when (it) {
                        AiChatThinkingFormat.ThinkingObject -> R.string.ai_service_thinking_object
                        AiChatThinkingFormat.EnableThinking -> R.string.ai_service_enable_thinking
                        AiChatThinkingFormat.ReasoningObject -> R.string.ai_service_reasoning_object
                        AiChatThinkingFormat.None -> R.string.ai_service_omit_thinking
                    } }) { update(value.copy(chatThinking = it)) }
                ServiceChoice(R.string.ai_service_token_limit, value.chatTokenLimit, AiChatTokenLimit.entries,
                    editable, { if (it == AiChatTokenLimit.MaxTokens) R.string.ai_service_max_tokens else R.string.ai_service_max_completion_tokens }) {
                    update(value.copy(chatTokenLimit = it))
                }
            }
            AiApiProtocol.Responses -> {
                ServiceToggle(R.string.ai_service_responses_summary, value.responsesSummary, editable) { update(value.copy(responsesSummary = it)) }
                ServiceToggle(R.string.ai_service_encrypted_reasoning, value.responsesEncryptedReasoning, editable) { update(value.copy(responsesEncryptedReasoning = it)) }
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
private fun ServiceToggle(label: Int, checked: Boolean, enabled: Boolean, update: (Boolean) -> Unit) {
    val description = stringResource(label)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(description, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodySmall)
        Switch(checked, update, enabled = enabled, modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun <T> ServiceChoice(label: Int, selected: T, choices: List<T>, enabled: Boolean,
    resource: (T) -> Int, update: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { open = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(resource(selected)), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                WorkbenchIcon(Glyph.Down, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { choice -> DropdownMenuItem(
                    text = { Text(stringResource(resource(choice)), style = MaterialTheme.typography.bodySmall) },
                    onClick = { open = false; update(choice) }, enabled = enabled,
                    trailingIcon = if (choice == selected) {{ WorkbenchIcon(Glyph.Check, Modifier.size(16.dp)) }} else null,
                ) }
            }
        }
    }
}

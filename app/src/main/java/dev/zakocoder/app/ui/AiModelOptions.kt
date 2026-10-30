package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

/** Memory-only drafts. In particular replacement headers must never use rememberSaveable. */
internal class ModelOptionsDraft(profile: AiProfile?) {
    var thinking by mutableStateOf(profile?.parameters?.thinking ?: AiThinking.Default)
    var effort by mutableStateOf(profile?.parameters?.reasoningEffort)
    var frequency by mutableStateOf(profile?.parameters?.frequencyPenalty?.toString().orEmpty())
    var presence by mutableStateOf(profile?.parameters?.presencePenalty?.toString().orEmpty())
    var seed by mutableStateOf(profile?.parameters?.seed?.toString().orEmpty())
    var contextChars by mutableStateOf((profile?.context?.maxContextChars ?: 96000).toString())
    var automatic by mutableStateOf(profile?.context?.autoCompress ?: true)
    var threshold by mutableStateOf((profile?.context?.compressionThresholdPercent ?: 80).toString())
    var summaryChars by mutableStateOf((profile?.context?.summaryMaxChars ?: 6000).toString())
    var replaceHeaders by mutableStateOf(false)
    var headersText by mutableStateOf("")
    fun parameters(base: AiParameters) = base.copy(thinking = thinking, reasoningEffort = effort,
        frequencyPenalty = frequency.takeIf(String::isNotBlank)?.toDouble(), presencePenalty = presence.takeIf(String::isNotBlank)?.toDouble(), seed = seed.takeIf(String::isNotBlank)?.toInt())
    fun context() = AiContextOptions(contextChars.toInt(), automatic, threshold.toInt(), summaryChars.toInt()).also { it.validate() }
    fun headers(): Map<String, String>? = if (!replaceHeaders) null else {
        val pairs = headersText.lineSequence().filter(String::isNotBlank).map { line ->
            val colon = line.indexOf(':'); require(colon > 0)
            line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }.toList()
        require(pairs.map { it.first.lowercase(java.util.Locale.ROOT) }.distinct().size == pairs.size)
        pairs.toMap().also(AiRequestHeaders::validate)
    }
    fun valid() = runCatching { parameters(AiParameters()).validate(); context(); headers() }.isSuccess
    fun dirty(profile: AiProfile?) = runCatching {
        parameters(AiParameters()) != (profile?.parameters ?: AiParameters()).copy(maxTokens = null, temperature = null, topP = null) ||
            context() != (profile?.context ?: AiContextOptions()) || replaceHeaders
    }.getOrDefault(true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelOptions(draft: ModelOptionsDraft, profile: AiProfile?, editable: Boolean, changed: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ai_options_title), Modifier.weight(1f)); WorkbenchIcon(if (open) Glyph.Down else Glyph.Next)
    }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ai_options_thinking), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_options_thinking_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiThinking.entries.forEach { mode -> FilterChip(selected = draft.thinking == mode, enabled = editable,
                onClick = { draft.thinking = mode; changed() }, label = { Text(stringResource(when(mode) {
                    AiThinking.Default -> R.string.ai_options_provider_default
                    AiThinking.Enabled -> R.string.ai_options_enabled
                    AiThinking.Disabled -> R.string.ai_options_disabled
                })) }) }
        }
        Text(stringResource(R.string.ai_options_effort), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf<AiReasoningEffort?>(null) + AiReasoningEffort.entries).forEach { effort ->
                FilterChip(selected = draft.effort == effort, enabled = editable, onClick = { draft.effort = effort; changed() },
                    label = { Text(if (effort == null) stringResource(R.string.ai_options_provider_default) else effort.wire) })
            }
        }
        OptionField(draft.frequency, { draft.frequency = it; changed() }, R.string.ai_options_frequency, editable)
        OptionField(draft.presence, { draft.presence = it; changed() }, R.string.ai_options_presence, editable)
        OptionField(draft.seed, { draft.seed = it; changed() }, R.string.ai_options_seed, editable)
        HorizontalDivider()
        Text(stringResource(R.string.ai_options_context), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_options_context_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OptionField(draft.contextChars, { draft.contextChars = it; changed() }, R.string.ai_options_context_chars, editable)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_options_auto), Modifier.weight(1f)); Switch(draft.automatic, { draft.automatic = it; changed() }, enabled = editable)
        }
        OptionField(draft.threshold, { draft.threshold = it; changed() }, R.string.ai_options_threshold, editable)
        OptionField(draft.summaryChars, { draft.summaryChars = it; changed() }, R.string.ai_options_summary_chars, editable)
        HorizontalDivider()
        Text(stringResource(R.string.ai_options_headers), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_options_headers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profile?.headerNames?.isNotEmpty() == true) Text(stringResource(R.string.ai_options_headers_saved, profile.headerNames.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_options_headers_replace), Modifier.weight(1f)); Switch(draft.replaceHeaders, { draft.replaceHeaders = it; changed() }, enabled = editable)
        }
        if (draft.replaceHeaders) OutlinedTextField(draft.headersText, { draft.headersText = it.take(26000); changed() }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_options_headers_lines)) }, minLines = 3, maxLines = 7, enabled = editable,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            supportingText = { Text(stringResource(R.string.ai_options_headers_empty)) })
        if (!draft.valid()) Text(stringResource(R.string.ai_options_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionField(value: String, update: (String) -> Unit, label: Int, editable: Boolean) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false))
}

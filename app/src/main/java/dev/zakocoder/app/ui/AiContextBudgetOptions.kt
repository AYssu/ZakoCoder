package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiModelContextWindows
import java.util.Locale

internal data class ContextWindowPreset(val tokens: Int, val provider: Int, val modelId: String, val responsesRequired: Boolean = false)

/** The visible list is derived from the same catalogue used when switching models. */
internal val contextWindowPresets = AiModelContextWindows.referenceWindows().map { (id, tokens) ->
    val provider = when {
        id.startsWith("gpt-") -> R.string.ai_context_provider_openai
        id.startsWith("deepseek-") -> R.string.ai_context_provider_deepseek
        id.startsWith("gemini-") -> R.string.ai_context_provider_google
        id.startsWith("claude-") -> R.string.ai_context_provider_anthropic
        id.startsWith("grok-") -> R.string.ai_context_provider_xai
        id.startsWith("glm-") -> R.string.ai_context_provider_zai
        id.startsWith("qwen") -> R.string.ai_context_provider_qwen
        id.startsWith("minimax-") -> R.string.ai_context_provider_minimax
        id.startsWith("kimi-") -> R.string.ai_context_provider_kimi
        else -> error("Missing context preset provider for $id")
    }
    ContextWindowPreset(tokens, provider, id, responsesRequired = id in setOf(
        "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-sol", "gpt-6.1-terra"))
}

internal fun tokenWindowLabel(tokens: Long): String {
    val divisor = when { tokens >= 1_000_000 -> 1_000_000.0; tokens >= 1_000 -> 1_000.0; else -> 1.0 }
    val suffix = when { tokens >= 1_000_000 -> "M"; tokens >= 1_000 -> "K"; else -> "" }
    return if (divisor == 1.0) tokens.toString() else
        String.format(Locale.ROOT, "%.2f", tokens / divisor).trimEnd('0').trimEnd('.') + suffix
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextBudgetOptions(draft: ModelOptionsDraft, editable: Boolean, changed: () -> Unit) {
    var presets by remember { mutableStateOf(false) }
    Text(stringResource(R.string.ai_options_context), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.ai_options_context_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val windowPresets = listOf(32_000, 64_000, 128_000, 256_000, 500_000, 1_000_000, 2_000_000)
    WindowDropdownPreference(
        title = stringResource(R.string.ai_context_window_presets),
        items = windowPresets.map { tokenWindowLabel(it.toLong()) },
        selectedIndex = windowPresets.indexOf(draft.windowTokens.toIntOrNull()),
        showValue = draft.windowTokens.toIntOrNull() in windowPresets,
        onSelectedIndexChange = { draft.windowTokens = windowPresets[it].toString(); changed() },
        bottomAction = {
            WorkbenchTextButton(onClick = { presets = true }, enabled = editable) {
                Text(stringResource(R.string.ai_context_choose_model_window))
            }
        },
        enabled = editable, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
    val windowDescription = stringResource(R.string.ai_options_context_tokens)
    WorkbenchTextField(draft.windowTokens, { draft.windowTokens = it; changed() }, Modifier.fillMaxWidth().semantics { contentDescription = windowDescription },
        enabled = editable, singleLine = true, label = stringResource(R.string.ai_options_context_tokens),
        isError = !ModelInputValidation.window(draft.windowTokens),
        supportingText = { Text(stringResource(if (ModelInputValidation.window(draft.windowTokens)) R.string.ai_context_window_range else R.string.ai_reading_number_window)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false))
    Text(stringResource(R.string.ai_context_gateway_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchPreference(checked = draft.automatic, onCheckedChange = { draft.automatic = it; changed() },
        title = stringResource(R.string.ai_options_auto), enabled = editable,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
    ContextIntegerField(draft.threshold, { draft.threshold = it; changed() }, R.string.ai_options_threshold, editable,
        !ModelInputValidation.threshold(draft.threshold), R.string.ai_reading_number_threshold)
    ContextIntegerField(draft.summaryChars, { draft.summaryChars = it; changed() }, R.string.ai_options_summary_chars, editable,
        !ModelInputValidation.summary(draft.summaryChars), R.string.ai_reading_number_summary)
    Text(stringResource(R.string.ai_context_summary_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    CopyHelp(stringResource(R.string.copy_ai_context_help_title), stringResource(R.string.copy_ai_context_help))
    if (presets) WorkbenchSheet(stringResource(R.string.ai_context_choose_model_window), { presets = false },
        subtitle = stringResource(R.string.ai_context_reference_count, contextWindowPresets.size,
            contextWindowPresets.map { it.provider }.distinct().size)) {
        Text(stringResource(R.string.ai_context_presets_scope), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CopyHelp(stringResource(R.string.copy_ai_presets_help_title), stringResource(R.string.copy_ai_presets_help))
        contextWindowPresets.groupBy { it.provider }.forEach { (provider, entries) ->
            Text(stringResource(provider), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            WorkbenchCard(Modifier.fillMaxWidth(), cornerRadius = 12.dp,
                color = MaterialTheme.colorScheme.surface) {
                Column {
                    entries.forEachIndexed { index, preset ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = editable, role = Role.Button) {
                            draft.windowTokens = preset.tokens.toString(); changed(); presets = false
                        }.heightIn(min = 64.dp).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(preset.modelId, style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace)
                                Text(stringResource(R.string.ai_context_preset_exact, java.text.NumberFormat.getIntegerInstance().format(preset.tokens)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (preset.responsesRequired) Text(stringResource(R.string.ai_context_responses_required),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            WorkbenchIcon(Glyph.Next, Modifier.padding(top = 4.dp).size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextIntegerField(value: String, update: (String) -> Unit, label: Int, editable: Boolean, invalid: Boolean, hint: Int) {
    WorkbenchTextField(value, update, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
        label = stringResource(label), isError = invalid, supportingText = { Text(stringResource(hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false))
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

internal data class ContextWindowPreset(val label: Int, val tokens: Int, val provider: Int, val modelId: String, val responsesRequired: Boolean = false)

/** Recent primary-model reference windows, checked against official API docs on 2026-10-31.
 * Selecting a window never changes the selected model, endpoint or credentials. */
internal val contextWindowPresets = listOf(
    ContextWindowPreset(R.string.ai_context_preset_deepseek_flash, AiModelContextWindows.defaultTokens("deepseek-flash"), R.string.ai_context_provider_deepseek, "deepseek-flash"),
    ContextWindowPreset(R.string.ai_context_preset_deepseek_pro, AiModelContextWindows.defaultTokens("deepseek-v4-pro"), R.string.ai_context_provider_deepseek, "deepseek-v4-pro"),
    ContextWindowPreset(R.string.ai_context_preset_sol, AiModelContextWindows.defaultTokens("gpt-6.1-sol"), R.string.ai_context_provider_openai, "gpt-6.1-sol", true),
    ContextWindowPreset(R.string.ai_context_preset_terra, AiModelContextWindows.defaultTokens("gpt-6.1-terra"), R.string.ai_context_provider_openai, "gpt-6.1-terra", true),
    ContextWindowPreset(R.string.ai_context_preset_astra, AiModelContextWindows.defaultTokens("gpt-6-astra"), R.string.ai_context_provider_openai, "gpt-6-astra"),
    ContextWindowPreset(R.string.ai_context_preset_luna, AiModelContextWindows.defaultTokens("gpt-6-luna"), R.string.ai_context_provider_openai, "gpt-6-luna"),
    ContextWindowPreset(R.string.ai_context_preset_gemini, AiModelContextWindows.defaultTokens("gemini-4-argon"), R.string.ai_context_provider_google, "gemini-4-argon"),
    ContextWindowPreset(R.string.ai_context_preset_grok, AiModelContextWindows.defaultTokens("grok-4.7"), R.string.ai_context_provider_xai, "grok-4.7"),
)

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ai_context_window_presets), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { presets = true }, enabled = editable) { Text(stringResource(R.string.ai_context_choose_model_window)) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(32_000, 64_000, 128_000, 256_000, 500_000, 1_000_000, 2_000_000).forEach { tokens ->
            FilterChip(selected = draft.windowTokens.toIntOrNull() == tokens, enabled = editable,
                onClick = { draft.windowTokens = tokens.toString(); changed() }, label = { Text(tokenWindowLabel(tokens.toLong())) })
        }
    }
    val windowDescription = stringResource(R.string.ai_options_context_tokens)
    OutlinedTextField(draft.windowTokens, { draft.windowTokens = it; changed() }, Modifier.fillMaxWidth().semantics { contentDescription = windowDescription },
        enabled = editable, singleLine = true, label = { Text(stringResource(R.string.ai_options_context_tokens)) },
        supportingText = { Text(stringResource(R.string.ai_context_window_range)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false))
    Text(stringResource(R.string.ai_context_gateway_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ai_options_auto), Modifier.weight(1f))
        Switch(draft.automatic, { draft.automatic = it; changed() }, enabled = editable)
    }
    ContextIntegerField(draft.threshold, { draft.threshold = it; changed() }, R.string.ai_options_threshold, editable)
    ContextIntegerField(draft.summaryChars, { draft.summaryChars = it; changed() }, R.string.ai_options_summary_chars, editable)
    Text(stringResource(R.string.ai_context_summary_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (presets) WorkbenchSheet(stringResource(R.string.ai_context_choose_model_window), { presets = false },
        subtitle = stringResource(R.string.ai_context_presets_checked)) {
        Text(stringResource(R.string.ai_context_presets_scope), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        contextWindowPresets.groupBy { it.provider }.forEach { (provider, entries) ->
            Text(stringResource(provider), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    entries.forEachIndexed { index, preset ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = editable, role = Role.Button) {
                            draft.windowTokens = preset.tokens.toString(); changed(); presets = false
                        }.heightIn(min = 64.dp).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(preset.label), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(preset.modelId, style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.ai_context_preset_exact, java.text.NumberFormat.getIntegerInstance().format(preset.tokens)),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
private fun ContextIntegerField(value: String, update: (String) -> Unit, label: Int, editable: Boolean) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false))
}

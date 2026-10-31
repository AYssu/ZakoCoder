package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiModelContextWindows
import dev.zakocoder.app.ai.AiSystemPrompt

@Composable
internal fun AiProviderPromptField(value: String, changed: (String) -> Unit, editable: Boolean) {
    val invalid = value.length > AiSystemPrompt.MAX_CHARS
    val description = stringResource(R.string.ai_provider_prompt_field)
    Text(stringResource(R.string.ai_provider_prompt_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(value, changed, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }, enabled = editable,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        label = { Text(stringResource(R.string.ai_provider_prompt_field)) }, minLines = 3, maxLines = 7,
        isError = invalid, supportingText = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (invalid) R.string.ai_provider_prompt_too_long else R.string.ai_provider_prompt_privacy))
                Text(stringResource(R.string.ai_provider_prompt_count, value.length, AiSystemPrompt.MAX_CHARS))
            }
        })
}

@Composable
internal fun AiModelContextSummary(model: String, window: String) {
    val recommended = AiModelContextWindows.recommendedTokens(model)
    val tokens = window.toLongOrNull()?.takeIf { it > 0 }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.ai_provider_context_label), style = MaterialTheme.typography.bodySmall)
        Text(tokens?.let(::tokenWindowLabel) ?: stringResource(R.string.ai_provider_context_invalid),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Text(stringResource(when {
        model.isBlank() -> R.string.ai_provider_context_select
        recommended == null -> R.string.ai_provider_context_unknown
        tokens == recommended.toLong() -> R.string.ai_provider_context_recommended
        else -> R.string.ai_provider_context_custom
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiProfile
import dev.zakocoder.app.ai.AiThinking

/** Keep configuration beside the model, without adding another row above the composer. */
@Composable
internal fun AiComposerModelButton(profile: AiProfile?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ai_switch_model)
    val thinking = when {
        profile?.parameters?.thinking == AiThinking.Disabled -> stringResource(R.string.ai_composer_thinking_disabled)
        profile?.parameters?.reasoningEffort != null -> stringResource(R.string.ai_composer_thinking_effort, profile.parameters.reasoningEffort.wire)
        profile?.parameters?.thinking == AiThinking.Enabled -> stringResource(R.string.ai_composer_thinking_enabled)
        else -> stringResource(R.string.ai_composer_thinking_default)
    }
    val window = profile?.context?.contextWindowTokens?.toLong()?.let(::tokenWindowLabel)
    val summary = window?.let { stringResource(R.string.ai_composer_model_state, thinking, it) }
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).semantics {
        contentDescription = description
        if (summary != null) stateDescription = summary
    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(profile?.model ?: stringResource(R.string.ai_choose_model), Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profile != null) {
            Spacer(Modifier.width(8.dp))
            Text(thinking, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(requireNotNull(window), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
    }
}

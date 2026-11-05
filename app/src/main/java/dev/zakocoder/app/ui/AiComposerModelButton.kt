package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiProfile
import dev.zakocoder.app.ai.AiThinking

/** One compact model capsule; fixed actions keep their width even with long model names. */
@Composable
internal fun AiComposerModelButton(profile: AiProfile?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ai_switch_model)
    val thinking = when {
        profile?.parameters?.thinking == AiThinking.Disabled -> stringResource(R.string.ai_composer_thinking_disabled)
        profile?.parameters?.reasoningEffort != null -> stringResource(R.string.ai_composer_thinking_effort, stringResource(profile.parameters.reasoningEffort.labelResource()))
        profile?.parameters?.thinking == AiThinking.Enabled -> stringResource(R.string.ai_composer_thinking_enabled)
        else -> stringResource(R.string.ai_composer_thinking_default)
    }
    val model = profile?.model ?: stringResource(R.string.ai_choose_model)
    val window = profile?.context?.contextWindowTokens?.toLong()?.let(::tokenWindowLabel)
    val summary = window?.let { stringResource(R.string.ai_composer_model_state, thinking, it) }
    val color = if (LocalWorkbenchDark.current) Color(0xFF9AA5B1) else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick).semantics {
        contentDescription = description
        stateDescription = listOfNotNull(model, summary).joinToString(" · ")
    }, contentAlignment = Alignment.CenterStart) {
        Surface(shape = CircleShape, color = Color.Transparent) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = color)
                if (summary != null) {
                    Text(thinking, maxLines = 1, fontSize = 10.sp, color = color)
                    Text(requireNotNull(window), maxLines = 1, fontSize = 10.sp, color = color)
                }
                WorkbenchIcon(Glyph.ChevronDown, Modifier.size(10.dp), tint = color)
            }
        }
    }
}

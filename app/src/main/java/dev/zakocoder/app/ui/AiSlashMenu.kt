package dev.zakocoder.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiSkill
import dev.zakocoder.app.ai.AiSlashCommandId
import dev.zakocoder.app.ai.AiSlashCommands
import dev.zakocoder.app.ai.AiSlashParseResult

/** Inline suggestions remain in the composer hierarchy, so opening them never takes IME focus. */
@Composable
internal fun AiSlashMenu(
    input: String,
    skills: List<AiSkill>,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSkillChoose: ((AiSkill) -> Unit)? = null,
) {
    val text = input.trimStart()
    if (!text.startsWith('/') || text.startsWith("//")) return
    val selectingSkill = text.startsWith("/skill:", ignoreCase = true)
    if (!selectingSkill && text.any(Char::isWhitespace)) return
    if (!selectingSkill && text.drop(1).contains('/')) return
    val parsedSkill = if (selectingSkill) AiSlashCommands.parse(text) as? AiSlashParseResult.Skill else null
    if (parsedSkill != null && (parsedSkill.task.isNotEmpty() || text.last().isWhitespace())) return
    val matchingSkills = remember(text, skills) {
        if (!selectingSkill) emptyList() else {
            val query = text.substringAfter(':').trimStart().removePrefix("\"")
                .removeSuffix("\"").replace("\\\"", "\"").replace("\\\\", "\\")
            skills.filter { it.valid && (it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)) }
        }
    }
    val commands = remember(text) { if (selectingSkill) emptyList() else AiSlashCommands.suggestions(text) }
    val duplicateNames = remember(skills) {
        skills.filter(AiSkill::valid).groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(vertical = 4.dp)) {
            if (selectingSkill) {
                if (matchingSkills.isEmpty()) item {
                    SlashEmpty(stringResource(R.string.ai_slash_skill_empty))
                }
                items(matchingSkills, key = { it.path }) { skill ->
                    SlashSuggestion(
                        token = "/skill:${skill.name}",
                        description = skill.description,
                        source = if (skill.name in duplicateNames) stringResource(R.string.ai_slash_skill_source, skill.path) else null,
                        onClick = { if (onSkillChoose != null) onSkillChoose(skill) else onChoose(slashSkillCommand(skill.name)) },
                    )
                }
            } else {
                if (commands.isEmpty()) item { SlashEmpty(stringResource(R.string.ai_slash_empty)) }
                items(commands, key = { it.id }) { command ->
                    SlashSuggestion("/${command.token}", stringResource(slashCommandLabel(command.id)),
                        onClick = { onChoose("/${command.token}") })
                }
            }
        }
    }
}

/** Names are quoted as data; execution still resolves an exact skill identity before sending. */
internal fun slashSkillCommand(name: String): String =
    "/skill:\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" "

@Composable
private fun SlashSuggestion(token: String, description: String, source: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(token, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(description, fontSize = 11.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (source != null) Text(source, fontSize = 11.sp, lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SlashEmpty(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(12.dp),
        fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
internal fun slashCommandLabel(id: AiSlashCommandId): Int = when (id) {
    AiSlashCommandId.Help -> R.string.ai_slash_help
    AiSlashCommandId.New -> R.string.ai_slash_new
    AiSlashCommandId.Sessions -> R.string.ai_slash_sessions
    AiSlashCommandId.Rename -> R.string.ai_slash_rename
    AiSlashCommandId.Compact -> R.string.ai_slash_compact
    AiSlashCommandId.Context -> R.string.ai_slash_context
    AiSlashCommandId.Model -> R.string.ai_slash_model
    AiSlashCommandId.Thinking -> R.string.ai_slash_thinking
    AiSlashCommandId.Skill -> R.string.ai_slash_skill
    AiSlashCommandId.Mcp -> R.string.ai_slash_mcp
    AiSlashCommandId.Permissions -> R.string.ai_slash_permissions
    AiSlashCommandId.Stop -> R.string.ai_slash_stop
}

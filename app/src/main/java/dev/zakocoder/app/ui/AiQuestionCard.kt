package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiUserQuestion

/** A question stays with its tool event; replying never opens a separate navigation destination. */
@Composable
internal fun AiQuestionCard(
    question: AiUserQuestion,
    enabled: Boolean,
    onAnswer: (String) -> Unit,
    onResume: (() -> Unit)? = null,
) {
    var choice by rememberSaveable(question.id) { mutableStateOf<String?>(null) }
    var custom by rememberSaveable(question.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val answer = question.answer
    val canReply = enabled && answer == null && !question.cancelled
    val draft = custom.trim().ifEmpty { choice.orEmpty() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIcon(Glyph.Ai, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.ai_question_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(when { question.cancelled -> R.string.ai_question_cancelled; answer == null -> R.string.ai_question_waiting; else -> R.string.ai_question_answered }),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (answer == null && !question.cancelled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(question.prompt, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.bodyLarge)
            }
            if (answer != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ai_question_your_answer), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(answer, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (onResume != null && !question.cancelled) {
                    Text(stringResource(R.string.ai_question_resume_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onResume, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.ai_question_resume))
                    }
                }
            } else if (question.cancelled) {
                Text(stringResource(R.string.ai_question_cancelled_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (question.options.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.options.forEach { option ->
                            val active = choice == option && custom.isBlank()
                            Surface(
                                onClick = { choice = option; custom = ""; focus.clearFocus() },
                                enabled = canReply,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    selected = active
                                    role = Role.RadioButton
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = active, onClick = null, enabled = canReply)
                                    Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { value ->
                        if (value.length <= AiUserQuestion.MAX_ANSWER) {
                            custom = value
                            if (value.isNotBlank()) choice = null
                        }
                    },
                    enabled = canReply,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_question_custom)) },
                    placeholder = { Text(stringResource(R.string.ai_question_custom_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    shape = RoundedCornerShape(8.dp),
                    supportingText = if (custom.isNotEmpty()) ({
                        Text(stringResource(R.string.ai_question_character_count, custom.length, AiUserQuestion.MAX_ANSWER))
                    }) else null,
                )
                Text(stringResource(R.string.ai_question_waiting_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { if (draft.isNotBlank()) { focus.clearFocus(); onAnswer(draft) } },
                    enabled = canReply && draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.ai_question_submit))
                }
            }
        }
    }
}

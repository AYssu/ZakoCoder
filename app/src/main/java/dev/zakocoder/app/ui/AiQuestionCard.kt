package dev.zakocoder.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var customExpanded by rememberSaveable(question.id) { mutableStateOf(false) }
    var receiptExpanded by rememberSaveable(question.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val dark = LocalWorkbenchDark.current
    val cardColor = if (dark) Color(0xFF161C24) else MaterialTheme.colorScheme.surface
    val cardBorder = if (dark) Color(0xFF242C36) else MaterialTheme.colorScheme.outlineVariant
    val optionColor = if (dark) Color(0xFF121821) else MaterialTheme.colorScheme.surface
    val optionBorder = if (dark) Color(0xFF2A3340) else MaterialTheme.colorScheme.outlineVariant
    val selectedColor = if (dark) Color(0xFF16233B) else Color(0xFFEEF3FF)
    val selectedBorder = if (dark) Color(0xFF5B8CFF) else MaterialTheme.colorScheme.primary
    val answer = question.answer
    val canReply = enabled && answer == null && !question.cancelled
    val draft = custom.trim().ifEmpty { choice.orEmpty() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorder),
    ) {
        Column(Modifier.animateContentSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (answer != null) {
                val receiptState = stringResource(if (receiptExpanded) R.string.ai_question_receipt_expanded else R.string.ai_question_receipt_collapsed)
                Surface(
                    onClick = { receiptExpanded = !receiptExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        role = Role.Button
                        stateDescription = receiptState
                    },
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (question.cancelled) R.string.ai_question_cancelled else R.string.ai_question_answered),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(answer, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        WorkbenchIcon(if (receiptExpanded) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AnimatedVisibility(visible = receiptExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer {
                            Text(question.prompt, Modifier.fillMaxWidth().semantics { heading() },
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.ai_question_your_answer), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer { Text(answer, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                if (onResume != null && !question.cancelled) {
                    // A persisted answer may still need an explicit resume; keep that action visible even when collapsed.
                    Text(stringResource(R.string.ai_question_resume_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onResume, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.ai_question_resume))
                    }
                }
            } else if (question.cancelled) {
                SelectionContainer {
                    Text(question.prompt, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.ai_question_cancelled_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ai_question_confirmation_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Surface(color = Color(0xFFFFF7E8), shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, Color(0xFFF3E3C4))) {
                        Text(stringResource(R.string.ai_question_confirmation_pending), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFFA95000), fontWeight = FontWeight.SemiBold)
                    }
                }
                SelectionContainer {
                    Text(question.prompt, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.bodyMedium)
                }
                if (question.options.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        question.options.forEach { option ->
                            val active = choice == option && custom.isBlank()
                            Surface(
                                onClick = { choice = option; custom = ""; customExpanded = false; focus.clearFocus() },
                                enabled = canReply,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    selected = active
                                    role = Role.RadioButton
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) selectedColor else optionColor,
                                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) selectedBorder else optionBorder),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = active, onClick = null, enabled = canReply,
                                        modifier = Modifier.size(20.dp),
                                        colors = RadioButtonDefaults.colors(selectedColor = selectedBorder,
                                            unselectedColor = if (dark) Color(0xFF66758A) else Color(0xFF8B99AC)))
                                    Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = customExpanded || custom.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ai_question_waiting_note),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            minLines = 1,
                            maxLines = 4,
                            trailingIcon = {
                                IconButton(
                                    onClick = { custom = ""; customExpanded = false; focus.clearFocus() },
                                    enabled = canReply,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    WorkbenchIcon(Glyph.Close, Modifier.size(18.dp),
                                        description = stringResource(R.string.ai_question_custom_clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            supportingText = if (custom.isNotEmpty()) ({
                                Text(stringResource(R.string.ai_question_character_count, custom.length, AiUserQuestion.MAX_ANSWER))
                            }) else null,
                        )
                    }
                }
                if (!customExpanded && custom.isEmpty()) {
                    TextButton(
                        onClick = { customExpanded = true },
                        enabled = canReply,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.ai_question_custom_entry),
                        fontSize = 12.sp, fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Button(
                    onClick = { if (draft.isNotBlank()) { focus.clearFocus(); onAnswer(draft) } },
                    enabled = canReply && draft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (dark) Color(0xFF3F4855) else Color(0xFF101828),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.ai_question_submit), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

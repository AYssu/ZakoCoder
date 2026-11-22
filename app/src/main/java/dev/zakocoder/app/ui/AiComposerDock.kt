package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R

/** One input surface; the caller owns the configuration order and its available width. */
@Composable
internal fun AiComposerDock(
    input: String,
    onInput: (String) -> Unit,
    inputEnabled: Boolean,
    awaitingAnswer: Boolean,
    attachEnabled: Boolean,
    running: Boolean,
    sendEnabled: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    focusDismissState: ComposerFocusDismissState? = null,
    configuration: @Composable RowScope.() -> Unit,
) {
    val dark = LocalWorkbenchDark.current
    val surface = if (dark) Color(0xFF22272E) else Color.White
    val border = if (dark) Color(0xFF2D343D) else Color(0xFFE3E7ED)
    val text = if (dark) Color(0xFFE6EDF7) else MaterialTheme.colorScheme.onSurface
    val hint = if (dark) Color(0xFF8D98A5) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = surface,
        border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            BasicTextField(input, onInput, enabled = inputEnabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 28.dp)
                    .composerFocusTarget(focusDismissState),
                maxLines = 4,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 18.sp, color = text),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box {
                        if (input.isEmpty()) Text(
                            stringResource(if (awaitingAnswer) R.string.ai_question_composer_hint else R.string.ai_prompt),
                            fontSize = 12.sp, lineHeight = 18.sp, color = hint)
                        field()
                    }
                })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WorkbenchIconButton(onClick = onAttach, enabled = attachEnabled, modifier = Modifier.width(44.dp).height(48.dp)) {
                    WorkbenchIcon(Glyph.Add, Modifier.offset(x = 4.dp).size(24.dp), description = stringResource(R.string.ai_context_attach),
                        tint = if (attachEnabled) hint else hint.copy(alpha = .38f))
                }
                configuration()
                // Keep a 48dp target while matching the reference's restrained 36dp circle.
                val sendDescription = stringResource(if (running) R.string.ai_stop else R.string.ai_send)
                WorkbenchIconButton(onClick = onSend, enabled = sendEnabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = sendDescription }) {
                    val background = when {
                        running -> if (dark) Color(0xFF343D49) else Color(0xFFEEF1F6)
                        !sendEnabled -> if (dark) Color(0xFF343D49) else Color(0xFFEEF1F6)
                        dark -> Color(0xFF3F4855)
                        else -> Color(0xFF101828)
                    }
                    Surface(Modifier.size(36.dp), shape = CircleShape, color = background) {
                        Box(contentAlignment = Alignment.Center) {
                            if (running) {
                                WorkbenchCircularProgressIndicator(Modifier.clearAndSetSemantics {}, size = 20.dp,
                                    color = hint.copy(alpha = if (sendEnabled) 1f else .5f),
                                    strokeWidth = 2.dp, trackColor = hint.copy(alpha = .15f))
                            } else {
                                WorkbenchIcon(Glyph.Up, Modifier.size(22.dp),
                                    tint = if (sendEnabled) Color(0xFFE6EDF7) else if (dark) Color(0xFF8D98A5) else Color(0xFFAEB8C6))
                            }
                        }
                    }
                }
            }
        }
    }
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults as MiuixTextFieldDefaults

/** Compact app field backed by Miuix editing, preserving caller-owned validation and secrets. */
@Composable
internal fun WorkbenchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    label: String? = null,
    accessibilityLabel: String? = label,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    val colors = MaterialTheme.colorScheme
    val errorDescription = stringResource(R.string.workbench_input_error)
    val secondary = if (isError) colors.error else colors.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) CompositionLocalProvider(LocalContentColor provides secondary) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) { Text(label, Modifier.clearAndSetSemantics {}) }
        }
        Box(Modifier.fillMaxWidth()) {
            MiuixTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                    .then(if (isError) Modifier.border(1.dp, colors.error, shape) else Modifier)
                    .semantics {
                        if (!accessibilityLabel.isNullOrBlank()) contentDescription = accessibilityLabel
                        if (isError) error(errorDescription)
                    },
                insideMargin = DpSize(12.dp, 12.dp), cornerRadius = 12.dp,
                colors = MiuixTextFieldDefaults.textFieldColors(backgroundColor = colors.surfaceContainerLow,
                    labelColor = secondary, borderColor = if (isError) colors.error else colors.primary),
                enabled = enabled, readOnly = readOnly,
                textStyle = textStyle.copy(color = textStyle.color.takeUnless { it == Color.Unspecified }
                    ?: colors.onSurface.copy(alpha = if (enabled) 1f else .45f)),
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                leadingIcon = leadingIcon?.let { icon -> {
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { icon() }
                } }, trailingIcon = trailingIcon,
                singleLine = singleLine, minLines = minLines, maxLines = maxLines,
                visualTransformation = visualTransformation)
            if (value.isEmpty() && placeholder != null) Box(Modifier.align(Alignment.TopStart).padding(start = if (leadingIcon != null) 48.dp else 12.dp,
                end = if (trailingIcon != null) 48.dp else 12.dp, top = 12.dp, bottom = 12.dp).clearAndSetSemantics {}) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ProvideTextStyle(textStyle) { placeholder() }
                }
            }
        }
        if (supportingText != null) CompositionLocalProvider(LocalContentColor provides secondary) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) { supportingText() }
        }
    }
}

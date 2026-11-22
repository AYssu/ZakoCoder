package dev.zakocoder.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as MiuixCircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults

/** Miuix interaction with the workbench's existing touch targets and content colors. */
@Composable
internal fun WorkbenchIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    MiuixIconButton(onClick = onClick, modifier = modifier.semantics {
        role = Role.Button
        if (!enabled) disabled()
    }, enabled = enabled,
        minWidth = 48.dp, minHeight = 48.dp, cornerRadius = 12.dp) {
        CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(
            alpha = if (enabled) LocalContentColor.current.alpha else .38f), content = content)
    }
}

/** The Miuix button primitive also accommodates actions containing an icon and a label. */
@Composable
internal fun WorkbenchTextButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, shape: Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    val foreground = if (enabled) colors.contentColor else colors.disabledContentColor
    MiuixButton(onClick = onClick, modifier = modifier.clip(shape).heightIn(min = 48.dp), enabled = enabled,
        minWidth = 48.dp, minHeight = 48.dp, cornerRadius = 12.dp,
        insideMargin = contentPadding, interactionSource = interactionSource,
        colors = MiuixButtonDefaults.buttonColors(color = colors.containerColor,
            disabledColor = colors.disabledContainerColor, contentColor = colors.contentColor,
            disabledContentColor = colors.disabledContentColor)) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/** Null progress means unknown, never a fabricated zero-percent download. */
@Composable
internal fun WorkbenchLinearProgressIndicator(modifier: Modifier = Modifier,
    progress: (() -> Float)? = null, color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    MiuixLinearProgressIndicator(modifier = modifier, progress = progress?.invoke()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color, backgroundColor = trackColor),
        height = 4.dp)
}

@Composable
internal fun WorkbenchCircularProgressIndicator(modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary, strokeWidth: Dp = 3.dp,
    trackColor: Color = Color.Transparent,
    size: Dp = ProgressIndicatorDefaults.DefaultCircularProgressIndicatorSize) {
    // Miuix also uses size for the arc's drawing geometry, not just layout measurement.
    MiuixCircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth, size = size,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color,
            backgroundColor = trackColor))
}

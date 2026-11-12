package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults

/** Flat Miuix card; borders are reserved for meaningful selection or warning states. */
@Composable
internal fun WorkbenchCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    cornerRadius: Dp = 12.dp,
    border: BorderStroke? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactive = onClick != null || onLongClick != null
    val decorated = modifier
        .then(if (border != null) Modifier.border(border, RoundedCornerShape(cornerRadius)) else Modifier)
        .then(if (interactive) Modifier.semantics {
            role = Role.Button
            if (!enabled) disabled()
        } else Modifier)
    val colors = MiuixCardDefaults.defaultColors(color = color, contentColor = contentColor)
    val body: @Composable ColumnScope.() -> Unit = {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
    if (interactive) {
        MiuixCard(modifier = decorated, cornerRadius = cornerRadius, insideMargin = PaddingValues(0.dp),
            colors = colors, onClick = onClick.takeIf { enabled }, onLongPress = onLongClick.takeIf { enabled },
            showIndication = enabled, content = body)
    } else {
        MiuixCard(modifier = decorated, cornerRadius = cornerRadius, insideMargin = PaddingValues(0.dp),
            colors = colors, content = body)
    }
}

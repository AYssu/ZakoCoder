package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.zakocoder.app.R

/** One compact contextual action, kept beside its message and within the window. */
@Composable
internal fun MessageCopyPopup(onDismiss: () -> Unit, onCopy: () -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = (anchorBounds.right - popupContentSize.width).coerceIn(gap, maxOf(gap, windowSize.width - popupContentSize.width - gap))
            val above = anchorBounds.top - gap - popupContentSize.height
            val y = (if (above >= gap) above else anchorBounds.bottom + gap)
                .coerceIn(gap, maxOf(gap, windowSize.height - popupContentSize.height - gap))
            return IntOffset(x, y)
        }
    }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 4.dp) {
            Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onCopy)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchIcon(Glyph.Copy, Modifier.size(16.dp))
                Text(stringResource(R.string.ai_copy_user), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

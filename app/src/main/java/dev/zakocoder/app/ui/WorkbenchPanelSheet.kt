package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** Window host only: specialized panels keep their own scrolling and fixed action areas. */
@Composable
internal fun WorkbenchPanelSheet(onDismiss: () -> Unit, darkBackdrop: Boolean = false,
    cornerRadius: Dp = 24.dp, content: @Composable (requestDismiss: () -> Unit) -> Unit) {
    var shown by remember { mutableStateOf(true) }
    val requestDismiss = { shown = false }
    WindowBottomSheet(show = shown, onDismissRequest = requestDismiss, onDismissFinished = onDismiss,
        backgroundColor = MaterialTheme.colorScheme.surface, cornerRadius = cornerRadius,
        outsideMargin = DpSize(0.dp, 0.dp), insideMargin = DpSize(0.dp, 0.dp)) {
        val view = LocalView.current
        val dark = LocalWorkbenchDark.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark && !darkBackdrop
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) { content(requestDismiss) }
    }
}

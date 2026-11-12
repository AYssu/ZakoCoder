package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** Controlled dialog: only its caller decides whether a dismiss request removes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbenchAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val maxBodyHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .5f }
    // Do not mutate an internal `show` flag on dismissal: approvals deliberately pass {}.
    WindowDialog(show = true, modifier = modifier, onDismissRequest = { if (dismissible) onDismissRequest() },
        backgroundColor = colors.surface, cornerRadius = 24.dp,
        outsideMargin = DpSize(16.dp, 16.dp), insideMargin = DpSize(20.dp, 20.dp),
        defaultWindowInsetsPadding = true) {
        // Consume the whole gesture before the library can animate a non-dismissible approval away.
        NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = !dismissible, onBackCompleted = {})
        val view = LocalView.current
        val dark = LocalWorkbenchDark.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                icon?.let { Box(Modifier.align(Alignment.CenterHorizontally)) { it() } }
                title?.let { ProvideTextStyle(MaterialTheme.typography.titleMedium) { it() } }
                text?.let {
                    Column(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight).verticalScroll(rememberScrollState())) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dismissButton?.let { Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { it() } }
                    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { confirmButton() }
                }
            }
        }
    }
}

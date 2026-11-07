package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.theme.LocalWorkbenchPalette

internal enum class AppDestination(val label: Int, val glyph: Glyph) {
    Project(R.string.nav_workspace, Glyph.Projects),
    Environment(R.string.nav_environment, Glyph.Environment),
    Terminal(R.string.nav_terminal, Glyph.Terminal),
    Ai(R.string.nav_ai, Glyph.Ai),
    Settings(R.string.nav_settings, Glyph.Settings),
}

/** Primary destinations share one drawer, leaving the active workspace its full height. */
@Composable
internal fun AppNavigation(
    drawerState: DrawerState,
    selected: AppDestination,
    version: String,
    gesturesEnabled: Boolean,
    onClose: () -> Unit,
    onSelect: (AppDestination) -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 300.dp).fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(36.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                            color = LocalWorkbenchPalette.current.primaryFill) {
                            Box(contentAlignment = Alignment.Center) { WorkbenchBrandMark(Modifier.size(24.dp)) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium)
                            Text(version.ifBlank { stringResource(R.string.loading) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onClose) {
                            WorkbenchIcon(Glyph.Close, description = stringResource(R.string.app_navigation_close))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    AppDestination.entries.forEach { destination ->
                        NavigationDrawerItem(selected = selected == destination,
                            onClick = { onSelect(destination) },
                            label = { Text(stringResource(destination.label)) },
                            icon = { WorkbenchIcon(destination.glyph) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                }
            }
        }, content = content)
}

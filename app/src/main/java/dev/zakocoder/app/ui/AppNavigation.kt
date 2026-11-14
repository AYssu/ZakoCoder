package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.theme.LocalWorkbenchPalette

internal enum class AppDestination(val label: Int, val glyph: Glyph) {
    Ai(R.string.nav_ai, Glyph.Ai),
    Project(R.string.nav_workspace, Glyph.Projects),
    Environment(R.string.nav_environment, Glyph.Environment),
    Terminal(R.string.nav_terminal, Glyph.Terminal),
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
    onHistory: () -> Unit = {},
    onSearch: () -> Unit = {},
    conversations: (@Composable () -> Unit)? = null,
    onSkills: () -> Unit = {},
    onMcp: () -> Unit = {},
    onCapabilities: () -> Unit = {},
    shortcut: String? = null,
    foreground: @Composable () -> Unit = {},
    foregroundVisible: Boolean = false,
    content: @Composable () -> Unit,
) {
    val feedbackView = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    var drawerWidthPx by remember { mutableIntStateOf(with(density) { 320.dp.roundToPx() }) }
    val pageBackground = MaterialTheme.colorScheme.background
    // Keep Material's swipe anchors, modal accessibility, scrim dismissal and Back handling.
    // Reading the offset in the graphics layer updates only drawing while a gesture is moving.
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(if (foregroundVisible) Modifier.clearAndSetSemantics {} else Modifier)) {
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = gesturesEnabled,
        scrimColor = Color.Black.copy(alpha = 0.22f),
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp).fillMaxHeight()
                .onSizeChanged { if (it.width > 0) drawerWidthPx = it.width },
                drawerContainerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MiuixCard(modifier = Modifier.size(36.dp), cornerRadius = 12.dp,
                            colors = MiuixCardDefaults.defaultColors(color = LocalWorkbenchPalette.current.primaryFill,
                                contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                WorkbenchBrandMark(Modifier.fillMaxSize())
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium)
                            Text(version.ifBlank { stringResource(R.string.loading) },
                                style = MaterialTheme.typography.labelSmall, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = rememberWorkbenchClickFeedback(onSearch)) {
                            WorkbenchIcon(Glyph.Search, description = stringResource(R.string.conversation_search_title))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    if (conversations != null) Box(Modifier.weight(1f).fillMaxWidth()) { conversations() }
                    else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        DrawerDestinationRow(stringResource(R.string.ai_history), Glyph.History, false, onHistory)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        listOf(
                            Triple(R.string.nav_settings, R.drawable.ic_nav_settings_2, { onSelect(AppDestination.Settings) }),
                            Triple(R.string.nav_environment, R.drawable.ic_nav_container, { onSelect(AppDestination.Environment) }),
                            Triple(R.string.nav_terminal, R.drawable.ic_nav_square_terminal, { onSelect(AppDestination.Terminal) }),
                            Triple(R.string.ai_skills, R.drawable.ic_nav_book_open_check, onSkills),
                            Triple(R.string.mcp_title, R.drawable.ic_nav_plug, onMcp),
                            Triple(R.string.capability_packages_title, R.drawable.ic_nav_blocks, onCapabilities),
                        ).forEach { (label, glyph, action) ->
                            val active = if (shortcut != null) when (shortcut) {
                                "skill" -> label == R.string.ai_skills
                                "mcp" -> label == R.string.mcp_title
                                "capabilities" -> label == R.string.capability_packages_title
                                else -> false
                            } else selected.label == label
                            val foreground = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .background(if (active) LocalWorkbenchPalette.current.surfaceAccent else Color.Transparent)
                                .semantics { this.selected = active }
                                .clickable(role = Role.Tab, onClick = {
                                    if (!active) feedbackView.withWorkbenchClickFeedback(action) else action()
                                })
                                .heightIn(min = 64.dp).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(20.dp), tint = foreground)
                                Text(stringResource(label), fontSize = 11.sp,
                                    style = MaterialTheme.typography.labelMedium, color = foreground)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val offset = drawerState.currentOffset
            val progress = if (offset.isFinite() && drawerWidthPx > 0) {
                (1f + offset / drawerWidthPx).coerceIn(0f, 1f)
            } else if (drawerState.isOpen) 1f else 0f
            translationX = drawerWidthPx * 0.88f * progress * direction
            scaleX = 1f - 0.045f * progress
            scaleY = 1f - 0.045f * progress
            transformOrigin = TransformOrigin(if (direction > 0) 0f else 1f, 0.5f)
            shape = RoundedCornerShape(20.dp * progress)
            clip = progress > 0f
        }) {
            Surface(Modifier.fillMaxSize(), color = pageBackground, content = content)
        }
    }
    }
    foreground()
    }
}


/** Flat navigation rows retain explicit selected semantics without Material's capsule treatment. */
@Composable
private fun DrawerDestinationRow(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val background = if (selected) LocalWorkbenchPalette.current.surfaceAccent else Color.Transparent
    BasicComponent(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(background)
            .semantics { this.selected = selected },
        startAction = { WorkbenchIcon(glyph, tint = foreground) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        onClick = onClick,
        role = Role.Tab,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, color = foreground)
    }
}

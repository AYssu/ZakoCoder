package dev.zakocoder.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.projects.*

private enum class Page(val label: Int, val glyph: Glyph) {
    Project(R.string.nav_workspace, Glyph.Projects), Environment(R.string.nav_environment, Glyph.Environment),
    Terminal(R.string.nav_terminal, Glyph.Terminal), Ai(R.string.nav_ai, Glyph.Ai), Settings(R.string.nav_settings, Glyph.Settings),
}

@Composable
fun ZakoCoderApp(workbench: Workbench) {
    var referencedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val state by workbench.state.collectAsStateWithLifecycle()
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    LaunchedEffect(state.message, state.messageIsError) {
        if (state.message.isNotEmpty() && !state.messageIsError) {
            kotlinx.coroutines.delay(accessibility?.calculateRecommendedTimeoutMillis(2000L, containsText = true, containsControls = true) ?: 2000L)
            workbench.dismissMessage()
        }
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var page by rememberSaveable { mutableStateOf(Page.Project) }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsAdd by rememberSaveable { mutableStateOf(false) }
    var extensionsOpen by rememberSaveable { mutableStateOf(false) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var terminalOpen by rememberSaveable { mutableStateOf(false) }
    var openedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val assistantNavigation by workbench.assistantNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(assistantNavigation?.requestId) {
        val request = assistantNavigation ?: return@LaunchedEffect
        val ai = workbench.ai.state.value
        if (workbench.screenTests.taskLease(request.projectId) == request.screenLease &&
            state.projects.any { it.id == request.projectId } && (!ai.active || ai.projectId == request.projectId)) {
            if (ai.projectId != request.projectId) workbench.ai.select(request.projectId)
            modelsOpen = false
            modelsAdd = false
            extensionsOpen = false
            page = Page.Ai
        }
        workbench.ackAssistantNavigation(request.requestId)
    }
    val inFiles = page == Page.Project && state.projects.any { it.id == openedProjectId }
    val inTerminal = page == Page.Terminal && terminalOpen && state.sessions.isNotEmpty()
    var knownIds by remember { mutableStateOf(state.sessions.map { it.id }.toSet()) }
    LaunchedEffect(state.sessions.map { it.id }) {
        val newest = state.sessions.lastOrNull { it.id !in knownIds }
        if (newest != null) terminalId = newest.id
        else if (state.sessions.none { it.id == terminalId }) terminalId = state.sessions.lastOrNull()?.id
        if (state.sessions.isEmpty()) terminalOpen = false
        knownIds = state.sessions.map { it.id }.toSet()
    }
    LaunchedEffect(page, state.busy) {
        if (!state.busy && (page == Page.Environment || page == Page.Settings)) workbench.refreshStorage()
    }
    val aiPageState = rememberSaveableStateHolder()
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val dark = isSystemInDarkTheme()
    val activity = androidx.activity.compose.LocalActivity.current
    SideEffect {
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !inTerminal && !dark
                isAppearanceLightNavigationBars = !inTerminal && !dark
            }
        }
    }
    CompositionLocalProvider(LocalWorkbenchDark provides dark, LocalMinimumInteractiveComponentSize provides 48.dp) {
    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography, shapes = Shapes(extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp))) {
        UpdateBindings(workbench.updates) { updatesOpen = true }
        if (updatesOpen) UpdatePage(workbench.updates) { updatesOpen = false }
        Scaffold(containerColor = if (inTerminal) TerminalBackground else MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars,
            bottomBar = {
                if (!inTerminal && !inFiles && !modelsOpen && !extensionsOpen && !keyboardOpen) Surface(color = PanelBackground) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                            Page.entries.forEach { item ->
                                val active = page == item
                                val tint = if (active) MaterialTheme.colorScheme.primary else TertiaryText
                                Column(Modifier.weight(1f).fillMaxHeight().semantics { selected = active }.clickable(role = Role.Tab) {
                                    focus.clearFocus(); keyboard?.hide(); terminalOpen = false; page = item
                                }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50)).padding(horizontal = 13.dp, vertical = 4.dp)) {
                                        WorkbenchIcon(item.glyph, tint = tint)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(stringResource(item.label), color = tint, fontSize = 10.5.sp, lineHeight = 14.sp,
                                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                if (!inTerminal && !inFiles && !modelsOpen && !extensionsOpen && page != Page.Ai) Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Surface(color = BrandFill, shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)) {
                        Box(Modifier.size(29.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            WorkbenchBrandMark(Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.5.sp)
                    Spacer(Modifier.weight(1f))
                    Surface(color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                        Text(stringResource(R.string.development_version), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 10.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.loading) { WorkbenchLoading(); return@Column }
                if (state.busy && state.showProgress) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(state.operation, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = workbench::cancel) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
                if (extensionsOpen) AiExtensionsPage(workbench, state) { extensionsOpen = false }
                else if (modelsOpen) AiModelsPage(workbench.ai, startAdding = modelsAdd, onStartAddingConsumed = { modelsAdd = false }) { modelsOpen = false; modelsAdd = false } else when (page) {
                    Page.Project -> ProjectLibraryPage(workbench, state, openedProjectId, { openedProjectId = it; referencedPath = null },
                        onEnvironment = { page = Page.Environment }, onAi = { id -> workbench.ai.select(id); page = Page.Ai },
                        referencedPath = referencedPath, onTerminal = { projectId -> workbench.select(projectId); focus.clearFocus(); keyboard?.hide(); terminalOpen = false; page = Page.Terminal })
                    Page.Environment -> Box(Modifier.padding(horizontal = 20.dp)) { EnvironmentPage(workbench, state, onTerminal = { terminalOpen = false; page = Page.Terminal }, onProjects = { openedProjectId = null; page = Page.Project }, onAi = { page = Page.Ai }) }
                    Page.Terminal -> if (inTerminal) TerminalPage(workbench, state, terminalId, { terminalId = it }, { terminalOpen = false })
                        else TerminalLibraryPage(workbench, state,
                            onOpen = { terminalId = it; terminalOpen = true },
                            onEnvironment = { page = Page.Environment }, onProjects = { page = Page.Project })
                    Page.Ai -> aiPageState.SaveableStateProvider("ai") { AiPage(workbench, state, onModels = { modelsOpen = true }, onProjects = { openedProjectId = null; page = Page.Project }, onTerminal = { id ->
                        if (state.sessions.any { it.id == id && it.projectId == workbench.ai.state.value.projectId }) {
                            focus.clearFocus(); keyboard?.hide(); terminalId = id; terminalOpen = true; page = Page.Terminal
                        }
                    }, onExtensions = { focus.clearFocus(); keyboard?.hide(); extensionsOpen = true },
                        onAddModel = { focus.clearFocus(); keyboard?.hide(); modelsAdd = true; modelsOpen = true },
                        onProjectPath = { projectId, path ->
                            workbench.select(projectId); referencedPath = path; openedProjectId = projectId
                            focus.clearFocus(); keyboard?.hide(); page = Page.Project
                        }) }
                    Page.Settings -> Box(Modifier.padding(horizontal = 20.dp)) { SettingsPage(workbench, state, onModels = { modelsOpen = true }, onExtensions = { extensionsOpen = true }, onUpdates = { updatesOpen = true }) }
                }
            }
            if (state.message.isNotEmpty()) Surface(Modifier.align(Alignment.TopCenter).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = cardElevation()) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(state.message, Modifier.weight(1f).heightIn(max = 96.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = workbench::dismissMessage) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.dismiss)) }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun KindLabels(kinds: Set<ProjectKind>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { SuggestionChip(onClick = {}, label = { Text(stringResource(it.label)) }) }
    }
}


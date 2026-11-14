package dev.zakocoder.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.projects.*
import kotlinx.coroutines.launch

@Composable
fun ZakoCoderApp(workbench: Workbench) {
    var referencedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val state by workbench.state.collectAsStateWithLifecycle()
    val updateInfo by workbench.updates.state.collectAsStateWithLifecycle()
    val assistantEntry by remember(workbench) {
        workbench.ai.state.map { ai ->
            AssistantEntry(ai.projectId, ai.permissionsLoading || ai.restoring,
                if (ai.active) emptyList() else ai.timeline.asReversed().asSequence()
                    .filter { it.role == dev.zakocoder.app.ai.AiRole.Assistant && it.toolId == null && it.text.isNotBlank() }
                    .take(2).map { it.text.take(AiTextPages.MARKDOWN_LIMIT) }.toList())
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(AssistantEntry(null, false, emptyList()))
    val modelEntry by workbench.ai.models.state.collectAsStateWithLifecycle()
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    LaunchedEffect(state.message, state.messageIsError) {
        if (state.message.isNotEmpty() && !state.messageIsError) {
            kotlinx.coroutines.delay(accessibility?.calculateRecommendedTimeoutMillis(2000L, containsText = true, containsControls = true) ?: 2000L)
            workbench.dismissMessage()
        }
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var page by rememberSaveable { mutableStateOf(AppDestination.Ai) }
    var historyRequest by rememberSaveable { mutableIntStateOf(0) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val navigationScope = rememberCoroutineScope()
    val openNavigation: () -> Unit = {
        focus.clearFocus()
        keyboard?.hide()
        navigationScope.launch { drawerState.open() }
    }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var proxyOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsAdd by rememberSaveable { mutableStateOf(false) }
    var extensionsOpen by rememberSaveable { mutableStateOf(false) }
    var extensionsTab by rememberSaveable { mutableIntStateOf(0) }
    var shortcutPage by rememberSaveable { mutableStateOf<String?>(null) }
    var searchConversations by rememberSaveable { mutableStateOf(false) }
    var terminalId by rememberSaveable { mutableStateOf<String?>(null) }
    var terminalOpen by rememberSaveable { mutableStateOf(false) }
    var openedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    var drawerOpening by remember { mutableStateOf(false) }
    var drawerOpenError by remember { mutableStateOf(false) }
    var drawerNavigationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var drawerNavigationGeneration by remember { mutableIntStateOf(0) }
    val cancelDrawerNavigation: () -> Unit = {
        drawerNavigationGeneration++
        drawerNavigationJob?.cancel()
        drawerNavigationJob = null
        drawerOpening = false
    }
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Closed && drawerOpening) cancelDrawerNavigation()
    }
    val openDrawerConversation: (String, String?) -> Unit = { project, conversation ->
        if (!drawerOpening) {
        val generation = ++drawerNavigationGeneration
        drawerNavigationJob = navigationScope.launch {
            drawerOpening = true; drawerOpenError = false
            try {
                val opened = withTimeoutOrNull(20_000) {
                    if (!workbench.openWorkspace(project)) return@withTimeoutOrNull false
                    combine(workbench.state, workbench.ai.state) { app, ai -> !app.busy && !ai.permissionsLoading && !ai.restoring }.first { it }
                    val ready = workbench.ai.state.value
                    if (workbench.state.value.selectedId != project || ready.projectId != project) return@withTimeoutOrNull false
                    if (ready.historyError) {
                        workspaceId = project; openedProjectId = null; page = AppDestination.Project
                        modelsOpen = false; extensionsOpen = false; shortcutPage = null
                        drawerOpening = false
                        drawerState.close()
                        return@withTimeoutOrNull true
                    }
                    if (conversation == null && (ready.active || ready.permissionsSaving)) return@withTimeoutOrNull false
                    val previous = ready.conversationId
                    workbench.ai.conversation(conversation)
                    val result = workbench.ai.state.first { !it.permissionsLoading && !it.restoring }
                    if (result.projectId != project || result.historyError ||
                        (conversation != null && result.conversationId != conversation) ||
                        (conversation == null && result.conversationId == previous)) return@withTimeoutOrNull false
                    modelsOpen = false; extensionsOpen = false; shortcutPage = null; openedProjectId = null; workspaceId = project
                    page = AppDestination.Ai
                    // The requested navigation is complete. Do not mistake our own close for a dismissal.
                    drawerOpening = false
                    drawerState.close()
                    true
                } ?: false
                drawerOpenError = !opened
            } finally { if (generation == drawerNavigationGeneration) drawerOpening = false }
        }
        }
    }
    val browserNavigation by workbench.browserNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(browserNavigation) {
        browserNavigation?.let { request ->
            focus.clearFocus(); keyboard?.hide(); modelsOpen = false; extensionsOpen = false
            shortcutPage = "browser"; drawerState.close(); workbench.consumeBrowserNavigation(request)
        }
    }
    // A setting-owned dialog must not survive navigation and appear over an unrelated
    // destination. AnimatedContent can keep old callbacks alive through its exit frames.
    LaunchedEffect(page) {
        if (page != AppDestination.Settings) proxyOpen = false
    }
    val overlayOpen = shortcutPage != null || modelsOpen || extensionsOpen
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
            page = AppDestination.Ai
            drawerState.close()
        }
        workbench.ackAssistantNavigation(request.requestId)
    }
    val inFiles = page == AppDestination.Project && state.projects.any { it.id == openedProjectId }
    val inTerminal = page == AppDestination.Terminal && terminalOpen && state.sessions.isNotEmpty()
    var knownIds by remember { mutableStateOf(state.sessions.map { it.id }.toSet()) }
    LaunchedEffect(state.sessions.map { it.id }) {
        val newest = state.sessions.lastOrNull { it.id !in knownIds }
        if (newest != null) terminalId = newest.id
        else if (state.sessions.none { it.id == terminalId }) terminalId = state.sessions.lastOrNull()?.id
        if (state.sessions.isEmpty()) terminalOpen = false
        knownIds = state.sessions.map { it.id }.toSet()
    }
    LaunchedEffect(page, state.busy) {
        if (!state.busy && (page == AppDestination.Environment || page == AppDestination.Settings)) workbench.refreshStorage()
    }
    val aiPageState = rememberSaveableStateHolder()
    val wantsSetup = state.onboarding.required || setupOpen
    LaunchedEffect(state.loading, state.selectedId) {
        if (!state.loading && workbench.ai.state.value.projectId == null) {
            workbench.ai.select(state.selectedId)
        }
    }
    val enteringAssistant = !state.loading && !wantsSetup && page == AppDestination.Ai && !modelsOpen && !extensionsOpen
    val markdownPrepared by produceState(false, assistantEntry) {
        value = false
        if (!assistantEntry.loading) {
            try {
                withContext(Dispatchers.Default) { assistantEntry.markdown.forEach { AiMarkdownCache.parse(it) } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Optional warm-up must never prevent entry. Do not log conversation content.
                android.util.Log.w("ZakoCoder", "Markdown warm-up unavailable; using normal message rendering")
            }
            value = true
        }
    }
    val assistantReady = !modelEntry.loading && !assistantEntry.loading &&
        (state.selectedId == null || assistantEntry.projectId != null) && markdownPrepared
    val entryDestination = if (state.loading) "startup" else if (state.onboarding.required) "setup" else "workbench"
    val entryReady = !state.loading && (wantsSetup || !enteringAssistant || assistantReady)
    val entryAnimationFinished = remember { mutableStateOf(false) }
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
    WorkbenchMiuixTheme(dark) {
    val mainBack = rememberWorkbenchBackMotion(resetKey = page, enabled = shortcutPage == null && page != AppDestination.Project &&
        (page != AppDestination.Ai || assistantEntry.projectId != null) && !(page == AppDestination.Terminal && terminalOpen && state.sessions.isNotEmpty()) &&
        !modelsOpen && !extensionsOpen && !updatesOpen && !proxyOpen && !drawerState.isOpen &&
        drawerState.targetValue != DrawerValue.Open && !drawerState.isAnimationRunning && !setupOpen && !state.onboarding.required) {
        if (page == AppDestination.Ai) {
            workspaceId = assistantEntry.projectId
            openedProjectId = null
            page = AppDestination.Project
        } else page = AppDestination.Ai
    }
        Box(Modifier.fillMaxSize()) {
        // Reopening setup is an overlay: keep the user's actual settings page and scroll
        // position alive underneath, so a cancelled back gesture has a real destination.
        val coveredWorkbench = if (setupOpen && !state.onboarding.required) Modifier.clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            } else Modifier
        // One startup cover/indicator survives startup -> setup/workbench. Construct the
        // first destination behind it only after local data is ready; later route changes
        // use AnimatedContent without restarting the startup indicator.
        WorkbenchEntrance("application", entryReady,
            onRevealed = { entryAnimationFinished.value = true }) {
        AnimatedContent(entryDestination, modifier = Modifier.fillMaxSize().then(coveredWorkbench), transitionSpec = {
            fadeIn(tween(340)) togetherWith fadeOut(tween(280, delayMillis = 80))
        }, label = "application-entry") { shownEntry ->
        if (shownEntry == "setup") {
            OnboardingPage(workbench, state, startFromBeginning = setupOpen, onExit = { setupOpen = false }, onComplete = {
                workbench.completeOnboarding {
                    setupOpen = false
                    page = AppDestination.Ai
                }
            })
            return@AnimatedContent
        }
        UpdateBindings(workbench.updates) { updatesOpen = true }
        if (updatesOpen) UpdatePage(workbench.updates) { updatesOpen = false }
        if (proxyOpen && page == AppDestination.Settings) ProxySettingsPage(workbench, state) { proxyOpen = false }
        AppNavigation(drawerState = drawerState, selected = page, version = updateInfo.currentVersion,
            onSearch = { searchConversations = true },
            gesturesEnabled = !inTerminal && !inFiles && !overlayOpen && !updatesOpen && !proxyOpen,
            onClose = { cancelDrawerNavigation(); navigationScope.launch { drawerState.close() } },
            onSkills = {
                cancelDrawerNavigation(); focus.clearFocus(); keyboard?.hide()
                modelsOpen = false; extensionsOpen = false; shortcutPage = "skill"
                navigationScope.launch { drawerState.close() }
            },
            onMcp = {
                cancelDrawerNavigation(); focus.clearFocus(); keyboard?.hide()
                modelsOpen = false; extensionsOpen = false; shortcutPage = "mcp"
                navigationScope.launch { drawerState.close() }
            },
            shortcut = shortcutPage,
            onCapabilities = {
                cancelDrawerNavigation(); focus.clearFocus(); keyboard?.hide()
                modelsOpen = false; extensionsOpen = false; shortcutPage = "capabilities"
                navigationScope.launch { drawerState.close() }
            },
            conversations = {
                ConversationDrawer(workbench, state, visible = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open,
                    searching = searchConversations, onSearchDismiss = { searchConversations = false },
                    busy = drawerOpening, error = drawerOpenError, onOpen = openDrawerConversation,
                    onManage = {
                        if (!drawerOpening) {
                            modelsOpen = false; extensionsOpen = false; shortcutPage = null; openedProjectId = null; workspaceId = null
                            referencedPath = null; page = AppDestination.Project
                            navigationScope.launch { drawerState.close() }
                        }
                    })
            },
            onHistory = {
                focus.clearFocus(); keyboard?.hide()
                modelsOpen = false; extensionsOpen = false
                workspaceId = workbench.ai.state.value.projectId
                openedProjectId = null
                page = AppDestination.Project
                navigationScope.launch { drawerState.close() }
            },
            onSelect = { destination ->
                cancelDrawerNavigation()
                shortcutPage = null; modelsOpen = false; extensionsOpen = false
                focus.clearFocus()
                keyboard?.hide()
                terminalOpen = false
                if (destination == AppDestination.Project) { openedProjectId = null; workspaceId = null; referencedPath = null }
                page = destination
                navigationScope.launch { drawerState.close() }
            }) {
        Scaffold(containerColor = if (inTerminal) TerminalBackground else MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.systemBars) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // The assistant is a single retained composition, shared by foreground and back preview.
            // It never runs a second model task or duplicates the saveable-state provider.
            val blockBackground = Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            if (page == AppDestination.Ai) {
                val targetWorkspace = state.projects.singleOrNull { it.id == assistantEntry.projectId }
                if (targetWorkspace != null) Column(Modifier.fillMaxSize().then(blockBackground)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 8.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {}) { WorkbenchIcon(Glyph.Menu) }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.5.sp)
                    }
                    WorkspaceConversationsPage(workbench, state, targetWorkspace,
                        onBack = {}, onFiles = {}, onTerminal = {}, onAi = {}, backEnabled = false)
                }
            }
            Box(Modifier.fillMaxSize()
                .then(if (page == AppDestination.Ai) mainBack.modifier else Modifier)
                .then(if (page != AppDestination.Ai || overlayOpen) blockBackground else Modifier)) {
CompositionLocalProvider(LocalWorkbenchPageInteractive provides (page == AppDestination.Ai && !overlayOpen && !updatesOpen && !proxyOpen)) {
WorkbenchEntrance("assistant", assistantReady, animate = entryAnimationFinished.value) { aiPageState.SaveableStateProvider("ai") { AiPage(workbench, state, onMemory = { shortcutPage = "memory" }, onBrowser = workbench::openBrowser, historyRequest = historyRequest, onHistoryRequestConsumed = { historyRequest = 0 }, onConversations = {
                        workspaceId = workbench.ai.state.value.projectId; openedProjectId = null; page = AppDestination.Project
                    }, onOpenNavigation = openNavigation, onModels = { modelsOpen = true }, onProjects = { openedProjectId = null; page = AppDestination.Project }, onTerminal = { id ->
                        if (state.sessions.any { it.id == id && it.projectId == workbench.ai.state.value.projectId }) {
                            focus.clearFocus(); keyboard?.hide(); terminalId = id; terminalOpen = true; page = AppDestination.Terminal
                        }
                    }, onExtensions = { focus.clearFocus(); keyboard?.hide(); extensionsOpen = true },
                        onAddModel = { focus.clearFocus(); keyboard?.hide(); modelsAdd = true; modelsOpen = true },
                        onProjectPath = { projectId, path ->
                            workbench.select(projectId); referencedPath = path; openedProjectId = projectId; workspaceId = projectId
                            focus.clearFocus(); keyboard?.hide(); page = AppDestination.Project
                        }) } }
                }
            }
            if (page != AppDestination.Ai) {
            CompositionLocalProvider(LocalWorkbenchPageInteractive provides (!overlayOpen && !updatesOpen && !proxyOpen)) {
            // Keep the actual destination composed behind child pages, including its scroll and drafts.
            // Its pixels are the predictive-back destination; input and accessibility stay on the overlay.
            Column(Modifier.fillMaxSize().then(mainBack.modifier)
                .then(if (overlayOpen) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                } else Modifier)) {
                if (!inTerminal && !inFiles && page != AppDestination.Ai) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 8.dp, end = 20.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = openNavigation, modifier = Modifier.size(48.dp)) {
                        WorkbenchIcon(Glyph.Menu, description = stringResource(R.string.app_navigation_open))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.5.sp)
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
                val contentDestination = page.name
                AnimatedContent(contentDestination, modifier = Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 40)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 18 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { -it / 24 })
                }, label = "workbench-page") { shownDestination ->
                when (AppDestination.valueOf(shownDestination)) {
                    AppDestination.Project -> ProjectLibraryPage(workbench, state, openedProjectId, { openedProjectId = it; if (it != null) workspaceId = it; referencedPath = null },
                        onEnvironment = { page = AppDestination.Environment }, onAi = { id ->
                            if (state.selectedId == id && workbench.ai.state.value.projectId == id && !workbench.ai.state.value.permissionsLoading) {
                                page = AppDestination.Ai
                            } else if (workbench.openWorkspace(id)) { workspaceId = id; openedProjectId = null }
                        },
                        workspaceId = workspaceId, onWorkspace = { workspaceId = it },
                        backEnabled = !overlayOpen && !drawerState.isOpen && drawerState.targetValue != DrawerValue.Open && !drawerState.isAnimationRunning,
                        referencedPath = referencedPath, onTerminal = { projectId -> workbench.select(projectId); focus.clearFocus(); keyboard?.hide(); terminalOpen = false; page = AppDestination.Terminal })
                    AppDestination.Environment -> Box(Modifier.padding(horizontal = 20.dp)) { EnvironmentPage(workbench, state, onTerminal = { terminalOpen = false; page = AppDestination.Terminal }, onProjects = { openedProjectId = null; page = AppDestination.Project }, onAi = { page = AppDestination.Ai }) }
                    AppDestination.Terminal -> if (inTerminal) Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().then(blockBackground)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 8.dp, end = 20.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {}) { WorkbenchIcon(Glyph.Menu) }
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.5.sp)
                            }
                            TerminalLibraryPage(workbench, state, onOpen = {}, onEnvironment = {}, onProjects = {})
                        }
                        TerminalPage(workbench, state, terminalId, { terminalId = it }, { terminalOpen = false })
                    }
                        else TerminalLibraryPage(workbench, state,
                            onOpen = { terminalId = it; terminalOpen = true },
                            onEnvironment = { page = AppDestination.Environment }, onProjects = { page = AppDestination.Project })
                    AppDestination.Ai -> Unit
                    AppDestination.Settings -> Box(Modifier.padding(horizontal = 20.dp)) { SettingsPage(workbench, state, onMemory = { shortcutPage = "memory" }, onBrowser = workbench::openBrowser, onSetup = { workbench.saveOnboardingStep(0); setupOpen = true }, onModels = { modelsOpen = true }, onExtensions = { extensionsOpen = true }, onUpdates = { updatesOpen = true }, onProxy = { if (page == AppDestination.Settings && !overlayOpen && !updatesOpen &&
                        !drawerState.isOpen && drawerState.targetValue != DrawerValue.Open &&
                        !drawerState.isAnimationRunning && !mainBack.exiting.value) {
                        focus.clearFocus(); keyboard?.hide(); proxyOpen = true
                    } }) }
                }
            }
            }
            }
            }
            // Child pages draw over the entire base column, including its app header. Their own
            // back modifier moves their opaque surface, exposing this still-live destination.
            if (overlayOpen) Box(Modifier.fillMaxSize()) {
                when {
                    shortcutPage == "memory" -> AgentMemoryPage(workbench.ai.memory) { shortcutPage = null }
                    // AiPage stays noninteractive underneath. This foreground destination owns
                    // its approval host, so tool confirmation never requires destroying WebView.
                    shortcutPage == "browser" -> AgentBrowserWorkspacePage(workbench) { shortcutPage = null }
                    shortcutPage == "capabilities" -> CapabilityPackageBindings(workbench, state.selectedId,
                        state.project?.name.orEmpty(), fullPage = true) { shortcutPage = null }
                    shortcutPage != null -> AiExtensionsPage(workbench, state,
                        initialTab = if (shortcutPage == "mcp") 1 else 0, standalone = true) { shortcutPage = null }
                    extensionsOpen -> AiExtensionsPage(workbench, state, initialTab = extensionsTab) { extensionsOpen = false }
                    modelsOpen -> AiModelsPage(workbench.ai, startAdding = modelsAdd,
                        onStartAddingConsumed = { modelsAdd = false }) { modelsOpen = false; modelsAdd = false }
                }
            }
            if (state.message.isNotEmpty()) Surface(Modifier.align(Alignment.TopCenter).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
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
        if (setupOpen && !state.onboarding.required) {
            WorkbenchEntrance("setup-overlay", ready = true, retainBackground = false) {
                OnboardingPage(workbench, state, startFromBeginning = true,
                    onExit = { setupOpen = false }, onComplete = {
                        workbench.completeOnboarding { setupOpen = false; page = AppDestination.Ai }
                    })
            }
        }
        }
    }
    }
    // Register after page handlers so Back always closes an open navigation drawer first.
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open || drawerState.isAnimationRunning) {
        cancelDrawerNavigation()
        navigationScope.launch { drawerState.close() }
    }
}

private data class AssistantEntry(val projectId: String?, val loading: Boolean, val markdown: List<String>)

@Composable
internal fun KindLabels(kinds: Set<ProjectKind>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { SuggestionChip(onClick = {}, label = { Text(stringResource(it.label)) }) }
    }
}


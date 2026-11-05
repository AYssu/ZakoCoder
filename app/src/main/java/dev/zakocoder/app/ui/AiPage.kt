package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.zakocoder.app.ai.AiRole
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState

@Composable
internal fun AiPage(workbench: Workbench, workbenchState: WorkbenchState, onModels: () -> Unit, onProjects: () -> Unit, onTerminal: (String) -> Unit, onExtensions: () -> Unit = {}, onAddModel: () -> Unit = onModels, onOpenNavigation: () -> Unit = {}, onProjectPath: (String, String) -> Unit = { _, _ -> }) {
    val state by workbench.ai.state.collectAsStateWithLifecycle()
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val usageStatistics by workbench.ai.usageStatistics.collectAsStateWithLifecycle()
    val changeReads = remember(state.projectId, state.conversationId, state.workspaceRevision,
        state.checkpointId, state.turn, state.active, state.restoring, state.permissionsLoading) {
        AiConversationChangesCache(workbench.ai::checkpointList, workbench.ai::checkpointChanges)
    }
    val entryAnimations = remember(state.projectId, state.conversationId) { LiveEntryAnimations(state.timeline.map { it.id }) }
    entryAnimations.observe(state.timeline.map { it.id }, state.active)
    var commands by rememberSaveable(state.projectId, state.conversationId) { mutableStateOf(false) }
    var commandsTurn by rememberSaveable(state.projectId, state.conversationId) { mutableIntStateOf(0) }
    var checkpoints by remember { mutableStateOf(false) }
    var retryTarget by remember(state.projectId, state.conversationId) { mutableStateOf<dev.zakocoder.app.ai.AiEntry?>(null) }
    var editingMessage by remember { mutableStateOf(false) }
    var messageActionError by remember(state.projectId, state.conversationId) { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var imageTarget by remember { mutableStateOf<Pair<String?, String>?>(null) }
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        imageTarget?.let { (projectId, conversationId) -> workbench.ai.addImages(uris, projectId, conversationId) }
        imageTarget = null
    }
    var disclosure by remember { mutableStateOf(false) }
    var capabilityPackagesOpen by remember(state.projectId) { mutableStateOf(false) }
    var inspecting by remember { mutableStateOf<dev.zakocoder.app.ai.AiReview?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var follow by rememberSaveable(state.projectId, state.conversationId) { mutableStateOf(true) }
    var automaticScroll by remember { mutableStateOf(false) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val uiScope = rememberCoroutineScope()
    var contentHeight by remember { mutableIntStateOf(0) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(listState, state.conversationId) {
        var userScrolled = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !automaticScroll) { userScrolled = true; follow = false }
            if (!scrolling && userScrolled) {
                if (!listState.canScrollForward) follow = true
                userScrolled = false
            }
        }
    }
    LaunchedEffect(dragging) { if (dragging) follow = false }
    LaunchedEffect(state.timeline.size, state.drafts.size, state.timeline.lastOrNull()?.text?.length, state.events.lastOrNull()?.output?.length, contentHeight, viewportHeight) {
        if (follow && !dragging && !listState.isScrollInProgress && state.timeline.isNotEmpty()) {
            automaticScroll = true
            try { listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } finally { automaticScroll = false }
        }
    }
    var history by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    var permissions by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf(false) }
    var planDetails by remember(state.projectId, state.conversationId) { mutableStateOf(false) }
    var detailPanel by rememberSaveable(state.projectId, state.conversationId) { mutableStateOf<String?>(null) }
    val composerState = rememberSaveableStateHolder()
    val detailState = rememberSaveableStateHolder()
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var more by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    LaunchedEffect(workbenchState.selectedId) { if (state.projectId == null) workbench.ai.select(workbenchState.selectedId) }
    val project = workbenchState.projects.singleOrNull { it.id == state.projectId }
    val preparationTexts = setOf(stringResource(R.string.ai_checkpoint_created), stringResource(R.string.ai_project_overview_loaded))
    val displayActivity = remember(state.timeline, state.events, preparationTexts) { aiDisplayActivity(state, preparationTexts) }
    val lastAnswers = remember(displayActivity.entries) { displayActivity.entries.filter { it.role == AiRole.Assistant && it.text.isNotEmpty() && it.toolId == null }.associateBy { it.turn } }

    if (capabilityPackagesOpen) CapabilityPackageBindings(workbench, state.projectId, project?.name.orEmpty()) { capabilityPackagesOpen = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenNavigation) {
                WorkbenchIcon(Glyph.Menu, Modifier.size(22.dp), description = stringResource(R.string.app_navigation_open))
            }
            TextButton(onClick = { choose = true }, enabled = !state.active && !state.restoring && !state.imagesLoading && !state.permissionsLoading && !state.permissionsSaving,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.conversations.firstOrNull { it.id == state.conversationId }?.title?.trim()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ai_conversation),
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project?.name ?: stringResource(R.string.select_project), maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                        WorkbenchIcon(Glyph.ChevronDown, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = { workbench.ai.conversation(); follow = true }, enabled = project != null && !state.active && !state.restoring && !state.imagesLoading && !state.permissionsLoading && !state.permissionsSaving && !state.historyError) { WorkbenchIcon(Glyph.Add, description = stringResource(R.string.ai_new_conversation)) }
            Box {
                IconButton(onClick = { more = true }) { WorkbenchIcon(Glyph.More, description = stringResource(R.string.ai_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_history)) }, leadingIcon = { WorkbenchIcon(Glyph.History) },
                        enabled = project != null && !state.active && !state.restoring,
                        onClick = { more = false; history = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_file_changes_title)) },
                        onClick = { more = false; detailPanel = "changes" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_current_plan)) }, enabled = state.plan.isNotEmpty(),
                        onClick = { more = false; planDetails = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_requests)) }, leadingIcon = { WorkbenchIcon(Glyph.Info) },
                        onClick = { more = false; detailPanel = "requests" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_validation)) }, leadingIcon = { WorkbenchIcon(Glyph.Check) },
                        onClick = { more = false; detailPanel = "validation" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.capability_packages_title)) }, leadingIcon = { WorkbenchIcon(Glyph.Extensions) },
                        onClick = { more = false; capabilityPackagesOpen = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_extensions)) }, leadingIcon = { WorkbenchIcon(Glyph.Extensions) },
                        onClick = { more = false; onExtensions() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_checkpoints)) }, leadingIcon = { WorkbenchIcon(Glyph.Undo) }, enabled = project != null && !state.active && !state.restoring,
                        onClick = { more = false; checkpoints = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_permissions)) }, leadingIcon = { WorkbenchIcon(Glyph.Shield) }, enabled = project != null,
                        onClick = { more = false; permissions = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_settings)) }, leadingIcon = { WorkbenchIcon(Glyph.Settings) }, onClick = { more = false; onModels() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_data_details)) }, leadingIcon = { WorkbenchIcon(Glyph.Info) }, onClick = { more = false; disclosure = true })
                }
            }
        }
        if (messageActionError) Text(stringResource(R.string.ai_message_retry_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.interrupted) Text(stringResource(R.string.ai_interrupted), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.active) AiRetryStatus(state.retryNotice)
        if (state.historyError && workbench.ai.canRetryHistorySave) TextButton(onClick = { workbench.ai.retryHistorySave() }) {
            Text(stringResource(R.string.ai_history_retry_save))
        }
        if (state.active || state.permissionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        if (state.permissionsLoading) Text(stringResource(R.string.ai_loading_conversation), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.recoveryNotes.isNotEmpty() && !state.active) Text(stringResource(R.string.ai_recovery_available), style = MaterialTheme.typography.labelSmall)
        if (state.historyTrimmed) Text(stringResource(R.string.ai_history_trimmed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        key(state.projectId, state.conversationId) { Box(Modifier.weight(1f).fillMaxWidth()) { LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)) {
            if (state.timeline.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkbenchIcon(Glyph.Ai, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ai_ready_title), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(stringResource(R.string.ai_execution_empty), fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (project == null) OutlinedButton(onClick = onProjects) { Text(stringResource(R.string.ai_select_project_first)) }
                else if (!state.configured) OutlinedButton(onClick = onModels) { Text(stringResource(R.string.ai_models_add_first)) }
            } }
            itemsIndexed(displayActivity.entries, key = { _, entry -> entry.id }) { index, entry ->
                val animateOnMount = remember(entry.id) { entryAnimations.consume(entry.id) }
                Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = androidx.compose.animation.core.tween(180),
                    placementSpec = if (state.active && entry.turn == state.turn) null else androidx.compose.animation.core.tween(220), fadeOutSpec = null)) {
                if (displayActivity.tools.containsKey(entry.id)) AiReadActivity(requireNotNull(displayActivity.tools[entry.id]), displayActivity.preparation[entry.id].orEmpty())
                else if (displayActivity.preparation.containsKey(entry.id)) AiPreparationActivity(requireNotNull(displayActivity.preparation[entry.id]))
                else if (entry.toolId == null) Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight++ }) {
                    AiMessage(entry, state.projectId, onProjectPath = { path -> state.projectId?.let { onProjectPath(it, path) } }, onLink = { link = it }, imageLoader = workbench.ai::imageThumbnail, onReadingInteraction = { follow = false }, animateOnMount = animateOnMount, reasoningStreaming = state.active && state.reasoningEntryId == entry.id, responseStreaming = state.active && state.timeline.lastOrNull { it.role == AiRole.Assistant && it.toolId == null }?.id == entry.id,
                        onRetry = if (!state.active && state.questionContinuation == null && !state.restoring && !state.permissionsLoading && !state.permissionsSaving && !state.historyError && state.configured && state.timeline.any { it.turn == entry.turn && it.role == AiRole.User }) ({
                            retryTarget = state.timeline.first { it.turn == entry.turn && it.role == AiRole.User }; editingMessage = false
                        }) else null,
                        onEdit = if (entry.role == AiRole.User && !state.active && state.questionContinuation == null && !state.restoring && !state.permissionsLoading && !state.permissionsSaving && !state.historyError && state.configured) ({ retryTarget = entry; editingMessage = true }) else null,
                        onRequests = if (state.requests.any { it.turn == entry.turn }) ({ detailPanel = "requests" }) else null,
                        actions = if (entry.turn > 0 && lastAnswers[entry.turn]?.id == entry.id && !(state.active && entry.turn == state.turn)) ({
                            AiConversationChangeCard(state, entry.turn, workbench.editor.languages, workbench.ai::undo,
                                changeReads::checkpoints, changeReads::changes, compact = true)
                        }) else null)
                }
                else state.events.find { it.id == entry.toolId }?.let { event ->
                    val terminalId = if (event.tool in setOf(dev.zakocoder.app.ai.AiTool.StartTerminal, dev.zakocoder.app.ai.AiTool.ReadTerminal, dev.zakocoder.app.ai.AiTool.SendTerminalInput) && event.status == dev.zakocoder.app.ai.AiToolStatus.Succeeded)
                        runCatching { org.json.JSONObject(event.output).getString("terminal_id") }.getOrNull() else null
                    val terminal = workbenchState.sessions.singleOrNull { it.id == terminalId && it.projectId == state.projectId }
                    val question = state.questions.singleOrNull { it.eventId == event.id }
                    if (question != null) {
                        val projectId = state.projectId
                        val continuation = state.questionContinuation?.takeIf { it.questionId == question.id }
                        val canAnswer = continuation != null && projectId != null && !state.active && !state.restoring && !state.historyError && state.configured &&
                            !state.permissionsLoading && !state.permissionsSaving && !models.loading && !models.saving && !workbenchState.busy
                        AiQuestionCard(question, enabled = canAnswer,
                            onAnswer = { answer -> if (projectId != null && !workbench.ai.answerQuestion(projectId, state.conversationId, question.id, answer)) messageActionError = true },
                            onResume = if (continuation != null && question.answer != null && !state.active) ({
                                if (projectId != null && !workbench.ai.resumeQuestion(projectId, state.conversationId, question.id)) messageActionError = true
                            }) else null)
                    } else AiToolCard(event, terminal?.number, onTerminal = { terminal?.let { onTerminal(it.id) } })
                }
                if (entry.turn > 0 && displayActivity.entries.getOrNull(index + 1)?.turn != entry.turn) {
                    if (entry.turn == state.turn && state.active) {
                        AiConversationProgress(state, onDetails = { planDetails = true })
                    }
                    if (lastAnswers[entry.turn] == null && !(state.active && entry.turn == state.turn))
                        AiConversationChangeCard(state, entry.turn, workbench.editor.languages, workbench.ai::undo,
                            changeReads::checkpoints, changeReads::changes, compact = true)
                    if (!((state.active || state.questionContinuation != null) && entry.turn == state.turn)) AiTurnFooter(state, entry.turn)
                }
                }
            }
            items(state.drafts, key = { "draft-${it.index}" }) { draft ->
                AiToolLivePreview(draft)
            }
            item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
        }
        }
        }
        if (!follow && !atBottom) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                follow = true
                uiScope.launch {
                    automaticScroll = true
                    try { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } finally { automaticScroll = false; follow = true }
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                WorkbenchIcon(Glyph.Down, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.ai_latest), fontSize = 12.sp)
            }
        }
        AiImageAttachments(state.pendingImages, workbench.ai::imageThumbnail, enabled = !state.active && !state.imagesLoading, onRemove = workbench.ai::removeImage)
        if (state.imagesLoading) Text(stringResource(R.string.ai_images_loading), style = MaterialTheme.typography.labelSmall)
        if (state.references.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.references.forEachIndexed { index, reference -> InputChip(selected = true, onClick = { workbench.ai.removeReference(index) }, enabled = !state.active,
                label = { Text(if (reference.kind == "error") reference.displayName ?: stringResource(R.string.ai_log_reference) else reference.path.substringAfterLast('/') + if (reference.unsaved) " *" else "", modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, trailingIcon = { WorkbenchIcon(Glyph.Close, Modifier.size(14.dp)) }) }
        }
        AiTaskQueuePanel(state.queuedMessages, state.active, enabled = !state.restoring && !state.historyError && !state.permissionsLoading && !state.permissionsSaving && state.questionContinuation == null,
            onCancel = workbench.ai::cancelQueued, onStart = { if (!workbench.ai.startQueued(it)) messageActionError = true })
        composerState.SaveableStateProvider("${state.projectId}:${state.conversationId}") {
        var input by rememberSaveable { mutableStateOf("") }
        if (state.active && state.questionContinuation == null && input.isNotBlank()) AiRunningInputActions(
            enabled = input.isNotBlank() && !state.historyError && state.phase != dev.zakocoder.app.ai.AiPhase.Stopping &&
                state.queuedMessages.count { it.status == dev.zakocoder.app.ai.AiQueuedStatus.Pending } < dev.zakocoder.app.ai.AiTaskQueue.MAX_PENDING,
            onSteer = { if (workbench.ai.enqueue(input, dev.zakocoder.app.ai.AiQueuedKind.Steer)) input = "" else messageActionError = true },
            onFollowUp = { if (workbench.ai.enqueue(input, dev.zakocoder.app.ai.AiQueuedKind.FollowUp)) input = "" else messageActionError = true })
        AiComposerDock(input, { if (it.length <= 16000) input = it },
            inputEnabled = state.questionContinuation == null && !state.restoring && !state.imagesLoading && !state.permissionsLoading && !state.permissionsSaving,
            awaitingAnswer = state.questionContinuation != null,
            attachEnabled = project != null && !state.active && state.questionContinuation == null && !state.restoring && !state.imagesLoading && !state.permissionsLoading && !state.permissionsSaving,
            running = state.active || state.questionContinuation != null,
            sendEnabled = if (state.active || state.questionContinuation != null) !state.restoring && state.phase != dev.zakocoder.app.ai.AiPhase.Stopping else project != null && !state.restoring && state.configured && !models.loading && !models.saving && !state.permissionsLoading && !state.permissionsSaving && !state.historyError && (input.isNotBlank() || state.pendingImages.isNotEmpty()) && !workbenchState.busy,
            onAttach = { attaching = true },
            onSend = { if (state.active || state.questionContinuation != null) workbench.ai.stop() else if (workbench.ai.send(input)) { input = ""; follow = true } }) {
            val permissionState = stringResource(R.string.ai_permission_current, stringResource(if (state.policy.yolo) R.string.ai_permission_preset_yolo else state.policy.default.label), state.policy.overrides.size)
            val compactPermission = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width / androidx.compose.ui.platform.LocalDensity.current.density < 360f || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
            val permissionLabel = stringResource(when {
                state.policy.yolo -> R.string.ai_permission_short_yolo
                state.policy.default == dev.zakocoder.app.ai.AiPermission.Allow -> R.string.ai_permission_short_allow
                state.policy.default == dev.zakocoder.app.ai.AiPermission.Deny -> R.string.ai_permission_short_deny
                else -> R.string.ai_permission_short_ask
            })
            TextButton(onClick = { permissions = true }, enabled = project != null,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = permissionState; contentDescription = permissionLabel + " · " + permissionState }) {
                val permissionTint = if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF9AA5B1) else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF333B45) else MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkbenchIcon(Glyph.Shield, Modifier.size(14.dp), description = stringResource(R.string.ai_permissions),
                            tint = permissionTint)
                        if (!compactPermission || state.policy.yolo) Text(permissionLabel, fontSize = 10.sp,
                            color = permissionTint)
                    }
                }
            }
            AiComposerModelButton(models.catalog.active, onClick = { modelPicker = true }, modifier = Modifier.weight(1f))

        }
    }
    }
    retryTarget?.let { selected ->
        val ownerProject = state.projectId
        val ownerConversation = state.conversationId
        AiMessageRetryDialog(selected, editingMessage, onDismiss = { retryTarget = null }, onConfirm = { text ->
            retryTarget = null
            if (ownerProject != null) uiScope.launch {
                val sent = workbench.ai.retryTurn(ownerProject, ownerConversation, selected.turn, text)
                if (sent) { follow = true }
                else if (workbench.ai.state.value.let { it.projectId == ownerProject && it.conversationId == ownerConversation }) messageActionError = true
            }
        })
    }
    if (planDetails) WorkbenchSheet(stringResource(R.string.ai_current_plan), { planDetails = false }) {
        Text(stringResource(state.phase.label), style = MaterialTheme.typography.bodyMedium)
        if (state.round > 0) Text(stringResource(R.string.ai_task_budget, state.round, state.toolCount), style = MaterialTheme.typography.bodySmall)
        AiTaskPlanPanel(state, 0, visible = true)
    }
    if (detailPanel == "changes") WorkbenchSheet(stringResource(R.string.ai_file_changes_title), { detailPanel = null }) {
        AiChangesPanel(state, workbench.editor.languages, workbench.ai::undo, { checkpoints = true },
            workbench.ai::checkpointList, workbench.ai::checkpointChanges, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp))
    }
    if (modelPicker) AiModelPicker(workbench.ai, { modelPicker = false },
        onAdd = { modelPicker = false; onAddModel() }, onManage = { modelPicker = false; onModels() })
    if (detailPanel == "requests") AiFullScreen(stringResource(R.string.ai_requests), { detailPanel = null }) {
        detailState.SaveableStateProvider("${state.projectId}:${state.conversationId}:requests") {
            Box(Modifier.padding(horizontal = 20.dp)) { AiTaskPlanPanel(state, 2, visible = !keyboardOpen) }
            AiRequestsPanel(state, Modifier.weight(1f).padding(horizontal = 16.dp), usageState = usageStatistics, onRefreshUsage = workbench.ai::refreshUsageStatistics)
        }
    }
    if (detailPanel == "validation") AiFullScreen(stringResource(R.string.project_validation), { detailPanel = null }) {
        detailState.SaveableStateProvider("${state.projectId}:${state.conversationId}:validation") {
            Box(Modifier.padding(horizontal = 20.dp)) { AiTaskPlanPanel(state, 4, visible = !keyboardOpen) }
            AiValidationPanel(workbench, Modifier.weight(1f).padding(horizontal = 16.dp),
                onCommands = { turn -> commandsTurn = turn; commands = true },
                onReference = { detailPanel = null; follow = true })
        }
    }
    if (commands && project != null) ProjectTasksPage(workbench, project.id, onAi = { commands = false }, validationConversation = state.conversationId.takeIf { commandsTurn > 0 }, validationTurn = commandsTurn) { commands = false }
    if (checkpoints) AiCheckpointSheet(workbench, close = { checkpoints = false }, onFork = workbench.ai::forkConversation)
    if (attaching) AiContextSheet(workbench.ai, state, onImages = {
        attaching = false
        imageTarget = state.projectId to state.conversationId
        imagePicker.launch(arrayOf("image/*"))
    }) { attaching = false }
    if (history) WorkbenchSheet(stringResource(R.string.ai_history), { history = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.conversations.isEmpty()) item { Text(stringResource(R.string.ai_history_empty), Modifier.padding(16.dp)) }
            items(state.conversations, key = { it.id }) { item ->
                Surface(shape = RoundedCornerShape(12.dp), color = if (item.id == state.conversationId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { workbench.ai.conversation(item.id); history = false }, modifier = Modifier.weight(1f), enabled = !state.permissionsLoading) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title.ifBlank { stringResource(R.string.ai_empty_conversation) }, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.updated)) + " · " + stringResource(R.string.ai_turn, item.turns), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { deleting = item.id }, enabled = !state.permissionsLoading) { WorkbenchIcon(Glyph.Trash, description = stringResource(R.string.ai_history_delete)) }
                    }
                }
            }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.ai_history_delete)) },
        text = { Text(stringResource(R.string.ai_history_delete_detail)) },
        confirmButton = { TextButton(onClick = { workbench.ai.deleteConversation(id); deleting = null }) { Text(stringResource(R.string.ai_history_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
    link?.let { destination -> AlertDialog(onDismissRequest = { link = null }, title = { Text(stringResource(R.string.ai_open_link)) },
        text = { Text(stringResource(R.string.ai_open_link_detail, destination)) },
        confirmButton = { TextButton(onClick = { try { uriHandler.openUri(destination) } catch (_: android.content.ActivityNotFoundException) { } finally { link = null } }) { Text(stringResource(R.string.ai_open_browser)) } },
        dismissButton = { TextButton(onClick = { link = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text(stringResource(R.string.ai_data_details)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (state.contextTurns.isEmpty()) R.string.ai_history_local else R.string.ai_context_count, state.contextTurns.size))
            if (state.estimatedContextTokens > 0) {
                Text(stringResource(R.string.ai_context_status, tokenWindowLabel(state.estimatedContextTokens), tokenWindowLabel(state.contextWindowTokens.toLong()), state.contextCompactions))
                Text(stringResource(R.string.ai_context_reserve_status, tokenWindowLabel(state.contextOutputReserveTokens.toLong())), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.ai_disclosure))
        }
    }, confirmButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.close)) } })
    if (state.approval == null) inspecting?.let { review -> AiCodeReview(review, workbench.editor.languages) { inspecting = null } }
    if (choose) WorkbenchSheet(stringResource(R.string.select_project), { choose = false }) {
        if (workbenchState.projects.isEmpty()) {
            Text(stringResource(R.string.ai_no_projects), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { choose = false; onProjects() }) { Text(stringResource(R.string.ai_select_project_first)) }
        }
        workbenchState.projects.forEach { item -> TextButton(onClick = { workbench.ai.select(item.id); choose = false }) { Text(item.name) } }
    }
    state.approval?.let { approval ->
        val event = state.events.find { it.id == approval.eventId }
        AlertDialog(onDismissRequest = { }, title = { Text(approval.title) }, text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event != null) {
                    Text(stringResource(event.tool?.label ?: R.string.ai_review_request), style = MaterialTheme.typography.labelLarge)
                    Text(event.reason.ifBlank { stringResource(R.string.ai_reason_missing) }, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.ai_tool_authority, stringResource(event.permissionSource), stringResource(event.permission.label)), style = MaterialTheme.typography.labelSmall)
                }
                if (approval.review != null) TextButton(onClick = { inspecting = approval.review }) { Text(stringResource(R.string.ai_review_content)) }
                androidx.compose.foundation.text.selection.SelectionContainer { Text(diffText(approval.detail), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (event != null) {
                    Text(stringResource(R.string.ai_approval_scope), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { workbench.ai.approve(dev.zakocoder.app.ai.AiDecision.TaskTarget) }) { Text(stringResource(R.string.ai_approve_target)) }
                    TextButton(onClick = { workbench.ai.approve(dev.zakocoder.app.ai.AiDecision.TaskTool) }) { Text(stringResource(R.string.ai_approve_tool_task)) }
                    Text(stringResource(R.string.ai_approve_tool_task_scope), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { permissions = true }) { Text(stringResource(R.string.ai_permissions)) }
                }
                TextButton(onClick = workbench.ai::stop) { Text(stringResource(R.string.ai_stop)) }
            }
        }, confirmButton = { TextButton(onClick = { workbench.ai.approve(true) }) { Text(stringResource(R.string.ai_approve_once)) } },
            dismissButton = { TextButton(onClick = { workbench.ai.approve(false) }) { Text(stringResource(R.string.ai_reject)) } })
    }
    if (state.approval != null) inspecting?.let { review -> AiCodeReview(review, workbench.editor.languages) { inspecting = null } }
    if (permissions) AiPermissionsSheet(workbench, project?.name.orEmpty(), { permissions = false })
}

@Composable
private fun diffText(text: String): androidx.compose.ui.text.AnnotatedString {
    val normal = MaterialTheme.colorScheme.onSurface
    val removed = MaterialTheme.colorScheme.error
    val added = androidx.compose.ui.graphics.Color(if (LocalWorkbenchDark.current) 0xFF6EE7B7 else 0xFF087F5B)
    return androidx.compose.ui.text.buildAnnotatedString {
        text.lineSequence().forEach { line ->
            pushStyle(androidx.compose.ui.text.SpanStyle(color = when { line.startsWith("+") -> added; line.startsWith("-") -> removed; else -> normal }))
            append(line); append("\n"); pop()
        }
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun AiMessage(entry: dev.zakocoder.app.ai.AiEntry, projectId: String?, onProjectPath: (String) -> Unit, onLink: (String) -> Unit, imageLoader: (suspend (dev.zakocoder.app.ai.AiImageAttachment) -> android.graphics.Bitmap)? = null, reasoningStreaming: Boolean = false, responseStreaming: Boolean = false, animateOnMount: Boolean = false, onReadingInteraction: () -> Unit = {}, onRetry: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null, onRequests: (() -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val visibleResponse = if (entry.role == AiRole.Assistant) smoothStreamingText(entry.id, entry.text, responseStreaming, animateOnMount) else entry.text
    val visibleReasoning = if (entry.role == AiRole.Assistant) smoothStreamingText("${entry.id}:reasoning", entry.reasoning, reasoningStreaming, animateOnMount) else entry.reasoning
    when (entry.role) {
        AiRole.User -> Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (imageLoader != null) AiImageAttachments(entry.images, imageLoader)
            else entry.images.forEach { Text(it.displayName, style = MaterialTheme.typography.labelSmall) }
            // Sent image metadata remains attached to this user turn after restart.
            var actions by remember(entry.id) { mutableStateOf(false) }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                if (entry.text.isEmpty()) IconButton(onClick = { actions = true }) {
                    WorkbenchIcon(Glyph.More, description = stringResource(R.string.ai_message_actions))
                } else Surface(onClick = { actions = true }, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF1C2636) else androidx.compose.ui.graphics.Color(0xFFE9ECF2), modifier = Modifier.widthIn(max = minOf(maxWidth * .84f, 420.dp))) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        if (entry.text.isNotEmpty()) Text(entry.text, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
                if (actions) MessageCopyPopup(onDismiss = { actions = false },
                    onCopy = { clipboard.setText(AnnotatedString(entry.text)); actions = false },
                    onEdit = onEdit?.let { action -> { actions = false; action() } },
                    onRetry = onRetry?.let { action -> { actions = false; action() } },
                    onRequests = onRequests?.let { action -> { actions = false; action() } })
            }
        }
        AiRole.Assistant -> if (entry.text.isNotEmpty() || entry.reasoning.isNotEmpty() || entry.reasoningTruncated) Column(Modifier.padding(top = if (entry.text.isNotEmpty()) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AiReasoningContent(entry, streaming = reasoningStreaming, displayedReasoning = visibleReasoning, onReadingInteraction = onReadingInteraction,
                trailingActions = if (!reasoningStreaming && entry.text.isEmpty() && (onRetry != null || onRequests != null)) {
                    { AiMessageMore(entry, onRetry, onRequests) }
                } else null)
            if (entry.text.isNotEmpty()) AiMarkdown(visibleResponse, projectId = projectId, onOpenProjectPath = onProjectPath, streaming = responseStreaming, onReadingInteraction = onReadingInteraction, onLink = onLink)
            if (entry.text.isNotEmpty() && !responseStreaming && visibleResponse == entry.text) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { Row { actions?.invoke(this) } }
                IconButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }) { WorkbenchIcon(Glyph.Copy, Modifier.size(15.dp), description = stringResource(R.string.ai_copy_response), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (onRetry != null || onRequests != null) AiMessageMore(entry, onRetry, onRequests)
            }
        }
        AiRole.Notice -> Text(entry.text, Modifier.padding(vertical = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun AiMessageMore(entry: dev.zakocoder.app.ai.AiEntry, onRetry: (() -> Unit)?, onRequests: (() -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    Box {
        var actions by remember(entry.id) { mutableStateOf(false) }
        IconButton(onClick = { actions = true }) { WorkbenchIcon(Glyph.More, Modifier.size(16.dp), description = stringResource(R.string.ai_message_actions)) }
        if (actions) MessageCopyPopup({ actions = false }, {
            clipboard.setText(AnnotatedString(entry.text.ifEmpty { entry.reasoning })); actions = false
        }, onRetry = onRetry?.let { action -> { actions = false; action() } },
            onRequests = onRequests?.let { action -> { actions = false; action() } })
    }
}

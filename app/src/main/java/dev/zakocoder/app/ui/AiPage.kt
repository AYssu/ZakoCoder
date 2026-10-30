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
internal fun AiPage(workbench: Workbench, workbenchState: WorkbenchState, onModels: () -> Unit, onProjects: () -> Unit, onTerminal: (String) -> Unit, onExtensions: () -> Unit = {}, onAddModel: () -> Unit = onModels, onProjectPath: (String, String) -> Unit = { _, _ -> }) {
    val state by workbench.ai.state.collectAsStateWithLifecycle()
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val extensions by workbench.mcp.state.collectAsStateWithLifecycle()
    var commands by rememberSaveable(state.projectId, state.conversationId) { mutableStateOf(false) }
    var commandsTurn by rememberSaveable(state.projectId, state.conversationId) { mutableIntStateOf(0) }
    var checkpoints by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
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
    var panel by rememberSaveable(state.projectId, state.conversationId) { mutableIntStateOf(0) }
    var detailPanel by rememberSaveable(state.projectId, state.conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(panel) { if (panel !in 0..1) panel = 0 }
    val composerState = rememberSaveableStateHolder()
    val detailState = rememberSaveableStateHolder()
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    var more by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    LaunchedEffect(workbenchState.selectedId) { if (state.projectId == null) workbench.ai.select(workbenchState.selectedId) }
    val project = workbenchState.projects.singleOrNull { it.id == state.projectId }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { choose = true }, enabled = !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.extensions_context), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(project?.name ?: stringResource(R.string.select_project), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                WorkbenchIcon(Glyph.ChevronDown, Modifier.padding(start = 8.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { history = true }, enabled = project != null && !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving) { WorkbenchIcon(Glyph.History, description = stringResource(R.string.ai_history)) }
            IconButton(onClick = { workbench.ai.conversation(); follow = true }, enabled = project != null && !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving && !state.historyError) { WorkbenchIcon(Glyph.Add, description = stringResource(R.string.ai_new_conversation)) }
            Box {
                IconButton(onClick = { more = true }) { WorkbenchIcon(Glyph.More, description = stringResource(R.string.ai_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_requests)) }, leadingIcon = { WorkbenchIcon(Glyph.Info) },
                        onClick = { more = false; detailPanel = "requests" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_validation)) }, leadingIcon = { WorkbenchIcon(Glyph.Check) },
                        onClick = { more = false; detailPanel = "validation" })
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
        if (state.interrupted) Text(stringResource(R.string.ai_interrupted), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (state.historyError && workbench.ai.canRetryHistorySave) TextButton(onClick = { workbench.ai.retryHistorySave() }) {
            Text(stringResource(R.string.ai_history_retry_save))
        }
        if (!keyboardOpen) {
        if (state.active || state.round > 0 || state.permissionsLoading) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (state.active) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (state.permissionsLoading) R.string.ai_loading_conversation else state.phase.label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (state.round > 0) Text(stringResource(R.string.ai_task_budget, state.round, state.toolCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        AiTaskPlanPanel(state, panel, visible = !keyboardOpen)
        if (!keyboardOpen) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryTabRow(selectedTabIndex = panel.coerceIn(0, 1), modifier = Modifier.weight(1f), containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                listOf(stringResource(R.string.ai_conversation), stringResource(R.string.ai_changes_title)).forEachIndexed { index, label ->
                    Tab(selected = panel == index, onClick = { panel = index }, text = { Text(label, maxLines = 1, fontSize = 12.sp) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        if (state.recoveryNotes.isNotEmpty()) Text(stringResource(R.string.ai_recovery_available), style = MaterialTheme.typography.labelSmall)
        if (state.historyTrimmed) Text(stringResource(R.string.ai_history_trimmed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (panel == 1) AiChangesPanel(state, workbench.editor.languages, workbench.ai::undo, { checkpoints = true },
            workbench.ai::checkpointList, workbench.ai::checkpointChanges, Modifier.weight(1f)) else Box(Modifier.weight(1f).fillMaxWidth()) { LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            if (state.timeline.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkbenchIcon(Glyph.Ai, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ai_ready_title), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(stringResource(R.string.ai_execution_empty), fontSize = 13.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (project == null) OutlinedButton(onClick = onProjects) { Text(stringResource(R.string.ai_select_project_first)) }
                else if (!state.configured) OutlinedButton(onClick = onModels) { Text(stringResource(R.string.ai_models_add_first)) }
            } }
            itemsIndexed(state.timeline, key = { _, entry -> entry.id }) { index, entry ->
                if (entry.toolId == null) Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight++ }) {
                    AiMessage(entry, state.projectId, onProjectPath = { path -> state.projectId?.let { onProjectPath(it, path) } }, onLink = { link = it })
                }
                else state.events.find { it.id == entry.toolId }?.let { event ->
                    val terminalId = if (event.tool in setOf(dev.zakocoder.app.ai.AiTool.StartTerminal, dev.zakocoder.app.ai.AiTool.ReadTerminal, dev.zakocoder.app.ai.AiTool.SendTerminalInput) && event.status == dev.zakocoder.app.ai.AiToolStatus.Succeeded)
                        runCatching { org.json.JSONObject(event.output).getString("terminal_id") }.getOrNull() else null
                    val terminal = workbenchState.sessions.singleOrNull { it.id == terminalId && it.projectId == state.projectId }
                    AiToolCard(event, terminal?.number, onTerminal = { terminal?.let { onTerminal(it.id) } })
                }
                if (entry.turn > 0 && state.timeline.getOrNull(index + 1)?.turn != entry.turn && !(state.active && entry.turn == state.turn))
                    AiTurnFooter(state, entry.turn)
            }
            items(state.drafts, key = { "draft-${it.index}" }) { draft -> Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(stringResource(R.string.ai_receiving_arguments), style = MaterialTheme.typography.labelSmall)
                Text(draft.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(draft.arguments.takeLast(500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 4)
            } }
            item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
        }
            if (!atBottom && panel == 0) FilledTonalButton(onClick = {
                follow = true
                uiScope.launch {
                    automaticScroll = true
                    try { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } finally { automaticScroll = false; follow = true }
                }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp)) {
                WorkbenchIcon(Glyph.Down, Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.ai_latest), fontSize = 12.sp)
            }
        }
        if (state.references.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.references.forEachIndexed { index, reference -> InputChip(selected = true, onClick = { workbench.ai.removeReference(index) }, enabled = !state.active,
                label = { Text(if (reference.kind == "error") reference.displayName ?: stringResource(R.string.ai_log_reference) else reference.path.substringAfterLast('/') + if (reference.unsaved) " *" else "", modifier = Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, trailingIcon = { WorkbenchIcon(Glyph.Close, Modifier.size(14.dp)) }) }
        }
        composerState.SaveableStateProvider("${state.projectId}:${state.conversationId}") {
        var input by rememberSaveable { mutableStateOf("") }
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column {
            if (state.active && state.turn > 0) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { AiTurnFooter(state, state.turn) }
            Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                IconButton(onClick = { attaching = true }, enabled = project != null && !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving, modifier = Modifier.padding(bottom = 4.dp)) { WorkbenchIcon(Glyph.Add, description = stringResource(R.string.ai_context_attach)) }
                TextField(input, { if (it.length <= 16000) input = it }, enabled = !state.active && !state.restoring && !state.permissionsLoading && !state.permissionsSaving, placeholder = { Text(stringResource(R.string.ai_prompt), fontSize = 13.sp) },
                    modifier = Modifier.weight(1f), maxLines = 4, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 20.sp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                IconButton(onClick = { if (state.active) workbench.ai.stop() else if (workbench.ai.send(input)) { input = ""; panel = 0; follow = true } },
                    enabled = if (state.active) state.phase != dev.zakocoder.app.ai.AiPhase.Stopping else project != null && !state.restoring && state.configured && !models.loading && !models.saving && !state.permissionsLoading && !state.permissionsSaving && !state.historyError && input.isNotBlank() && !workbenchState.busy,
                    modifier = Modifier.padding(bottom = 4.dp)) {
                    WorkbenchIcon(if (state.active) Glyph.Close else Glyph.Send, description = stringResource(if (state.active) R.string.ai_stop else R.string.ai_send), tint = if (state.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val switchDescription = stringResource(R.string.ai_switch_model)
            TextButton(onClick = { modelPicker = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = switchDescription },
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(models.catalog.active?.model ?: stringResource(R.string.ai_choose_model), Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                WorkbenchIcon(Glyph.ChevronDown, Modifier.size(16.dp))
            }
            val permissionState = stringResource(R.string.ai_permission_current, stringResource(state.policy.default.label), state.policy.overrides.size)
            val permissionDescription = stringResource(R.string.ai_permissions)
            TextButton(onClick = { permissions = true }, enabled = project != null,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = permissionDescription; stateDescription = permissionState }) {
                WorkbenchIcon(Glyph.Shield, Modifier.size(20.dp), description = stringResource(R.string.ai_permissions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(state.policy.default.label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val extensionSummary = stringResource(R.string.ai_extensions_summary, state.enabledSkills.size, extensions.servers.count { it.enabled })
            IconButton(onClick = onExtensions, modifier = Modifier.semantics { stateDescription = extensionSummary }) {
                WorkbenchIcon(Glyph.Extensions, Modifier.size(20.dp), description = stringResource(R.string.ai_extensions))
            }
        }
    }
    }
    if (modelPicker) AiModelPicker(workbench.ai, { modelPicker = false },
        onAdd = { modelPicker = false; onAddModel() }, onManage = { modelPicker = false; onModels() })
    if (detailPanel == "requests") AiFullScreen(stringResource(R.string.ai_requests), { detailPanel = null }) {
        detailState.SaveableStateProvider("${state.projectId}:${state.conversationId}:requests") {
            Box(Modifier.padding(horizontal = 20.dp)) { AiTaskPlanPanel(state, 2, visible = !keyboardOpen) }
            AiRequestsPanel(state, Modifier.weight(1f).padding(horizontal = 16.dp))
        }
    }
    if (detailPanel == "validation") AiFullScreen(stringResource(R.string.project_validation), { detailPanel = null }) {
        detailState.SaveableStateProvider("${state.projectId}:${state.conversationId}:validation") {
            Box(Modifier.padding(horizontal = 20.dp)) { AiTaskPlanPanel(state, 4, visible = !keyboardOpen) }
            AiValidationPanel(workbench, Modifier.weight(1f).padding(horizontal = 16.dp),
                onCommands = { turn -> commandsTurn = turn; commands = true },
                onReference = { detailPanel = null; panel = 0; follow = true })
        }
    }
    if (commands && project != null) ProjectTasksPage(workbench, project.id, onAi = { commands = false }, validationConversation = state.conversationId.takeIf { commandsTurn > 0 }, validationTurn = commandsTurn) { commands = false }
    if (checkpoints) AiCheckpointSheet(workbench, close = { checkpoints = false }, onFork = workbench.ai::forkConversation)
    if (attaching) AiContextSheet(workbench.ai, state) { attaching = false }
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
            if (state.contextChars > 0) Text(stringResource(R.string.ai_context_status, state.contextChars, state.contextCompactions))
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
internal fun AiMessage(entry: dev.zakocoder.app.ai.AiEntry, projectId: String?, onProjectPath: (String) -> Unit, onLink: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    when (entry.role) {
        AiRole.User -> Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ai_turn, entry.turn), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var actions by remember(entry.id) { mutableStateOf(false) }
            Box {
                Surface(onClick = { actions = true }, shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 300.dp)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(entry.text, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }
                if (actions) MessageCopyPopup(onDismiss = { actions = false },
                    onCopy = { clipboard.setText(AnnotatedString(entry.text)); actions = false })
            }
        }
        AiRole.Assistant -> if (entry.text.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AiMarkdown(entry.text, projectId = projectId, onOpenProjectPath = onProjectPath, onLink = onLink)
            IconButton(onClick = { clipboard.setText(AnnotatedString(entry.text)) }, modifier = Modifier.size(36.dp)) { WorkbenchIcon(Glyph.Copy, Modifier.size(15.dp), description = stringResource(R.string.ai_copy_response), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        AiRole.Notice -> Text(entry.text, Modifier.padding(vertical = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

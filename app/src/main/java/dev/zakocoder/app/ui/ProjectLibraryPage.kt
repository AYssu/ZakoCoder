package dev.zakocoder.app.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.projects.Project
import dev.zakocoder.app.projects.ProjectFile
import dev.zakocoder.app.projects.ProjectTemplate
import dev.zakocoder.app.projects.ProjectTemplates
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectLibraryPage(workbench: Workbench, state: WorkbenchState, openedId: String?, onOpened: (String?) -> Unit,
                               onEnvironment: () -> Unit, onTerminal: (String) -> Unit, onAi: (String) -> Unit, referencedPath: String? = null) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(workbench::import) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(workbench::export) }
    var cloning by remember { mutableStateOf(false) }
    var cloneUrl by remember { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var binding by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var templateName by rememberSaveable { mutableStateOf(ProjectTemplate.Other.name) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<Project?>(null) }
    var actions by remember { mutableStateOf<Project?>(null) }
    val opened = state.projects.singleOrNull { it.id == openedId }
    BackHandler(opened != null) { onOpened(null) }

    if (opened != null) ProjectFilesPage(workbench, state, opened, onBack = { onOpened(null) },
        onExport = { exporter.launch(null) }, onRemove = { removing = opened }, onAi = onAi, referencedPath = referencedPath)
    else Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 20.dp)) {
PageHeading(stringResource(R.string.projects_title)) {
            FilledTonalButton(shape = RoundedCornerShape(12.dp), onClick = { adding = true }, enabled = !state.busy, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)) {
                WorkbenchIcon(Glyph.Add, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.mobile_add_project))
            }
        }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
        // Banner (42), following gap (14) and bottom inset (20) leave the empty state's real viewport.
        val emptyHeight = (maxHeight - 76.dp).coerceAtLeast(320.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(onClick = onEnvironment, shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (state.installed) ReadyGreen else WaitingAmber)
                    Text(stringResource(if (state.installed) R.string.library_environment_ready else R.string.library_environment_missing),
                        Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (state.installed && state.bundleTools.isEmpty()) stringResource(if (state.checkingTools) R.string.checking_tools else R.string.bundle_unknown)
                        else if (state.installed) stringResource(R.string.library_environment_summary,
                            state.bundleTools.count { it.status == dev.zakocoder.app.runtime.ToolStatus.Ready }, dev.zakocoder.app.runtime.DevelopmentTools.catalog.size)
                        else stringResource(R.string.library_environment_short), fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
                        color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WorkbenchIcon(Glyph.Next, Modifier.size(14.dp), tint = TertiaryText)
                }
            }
        }
        if (state.projects.isEmpty()) item {
            Column(Modifier.fillMaxWidth().height(emptyHeight).padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                EmptyArtwork(Glyph.ProjectAdd)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.library_empty_title), fontSize = 17.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.library_empty_description), style = MaterialTheme.typography.bodyMedium, color = TertiaryText, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { adding = true }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 30.dp, vertical = 13.dp)) {
                    WorkbenchIcon(Glyph.Add, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.mobile_add_project))
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.projects_section), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.project_count, state.projects.size), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }, Modifier.size(48.dp)) {
                        WorkbenchIcon(Glyph.Search, Modifier.size(17.dp), description = stringResource(R.string.search_projects), tint = TertiaryText)
                    }
                }
                if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.search_projects)) },
                    leadingIcon = { WorkbenchIcon(Glyph.Search) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.clear_search)) } })
            }
            val results = state.projects.filter { it.name.contains(query, ignoreCase = true) }.sortedByDescending { it.id == state.selectedId }
            if (results.isEmpty()) item { Text(stringResource(R.string.project_search_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { it.id }) { project ->
                Surface(shape = RoundedCornerShape(18.dp), shadowElevation = cardElevation(), border = cardBorder()) {
                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { workbench.select(project.id); onOpened(project.id) },
                        onLongClickLabel = stringResource(R.string.project_actions), onLongClick = { actions = project }).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile(Glyph.Projects)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                ProjectKindsText(project)
                                val boundPath = workbench.projects.bindingPath(project.id)
                                Text(stringResource(if (boundPath == null) R.string.local_project_managed_label else R.string.local_project_bound_label),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (boundPath != null) Text(boundPath, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { actions = project }) { WorkbenchIcon(Glyph.More, description = stringResource(R.string.project_actions)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactAction(stringResource(R.string.nav_ai), { onAi(project.id) }, Modifier.weight(1f), enabled = !state.busy)
                            CompactAction(stringResource(R.string.open_terminal), { onTerminal(project.id) }, Modifier.weight(1f), primary = true, enabled = !state.busy)
                            CompactAction(stringResource(R.string.browse_files), { workbench.select(project.id); onOpened(project.id) }, Modifier.weight(1f), enabled = !state.busy)
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.project_card_hint), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = TertiaryText, textAlign = TextAlign.Center) }
        }
    }
    }
    }
    if (adding) AddProjectSheet(enabled = !state.busy, onDismiss = { adding = false },
        onImport = { adding = false; importer.launch(null) },
        onCreate = { adding = false; creating = true },
        onClone = { adding = false; cloning = true },
        onBind = { adding = false; binding = true })
    if (binding) LocalProjectBindingSheet(!state.busy, { binding = false }, workbench::bindLocalProject)
    if (creating) WorkbenchSheet(stringResource(R.string.create_project), { creating = false }, stringResource(R.string.library_description)) {
        // A restored saveable may name a template that no longer exists; fall back to Other everywhere.
        val selectedTemplate = ProjectTemplates.entries.firstOrNull { it.name == templateName } ?: ProjectTemplate.Other
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.create_template_section), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.create_template_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ProjectTemplates.entries.forEach { template ->
            val selected = template == selectedTemplate
            SelectablePanel(selected = selected, onClick = { templateName = template.name }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(template.label), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(template.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SelectionCheck(selected)
            }
        }
        WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = {
            creating = false; onOpened(null); workbench.create(name, selectedTemplate); name = ""; templateName = ProjectTemplate.Other.name
        }, enabled = name.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create)) }
        TextButton(onClick = { creating = false }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
    }
    if (cloning) WorkbenchSheet(stringResource(R.string.git_clone), { cloning = false }, stringResource(R.string.git_clone_help)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cloneUrl, { cloneUrl = it }, label = { Text(stringResource(R.string.git_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { workbench.cloneProject(name, cloneUrl); cloning = false; name = ""; cloneUrl = "" }, enabled = !state.busy && state.installed && name.isNotBlank() && runCatching { dev.zakocoder.app.git.publicCloneUrl(cloneUrl) }.isSuccess, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.git_clone)) }
    }
    actions?.let { project -> WorkbenchSheet(project.name, { actions = null }) {
        WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { actions = null; workbench.select(project.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.use_project)) }
        OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { actions = null; workbench.select(project.id); onOpened(project.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.browse_project, project.name))
        }
        TextButton(onClick = { actions = null; removing = project },
            enabled = !state.busy && state.sessions.none { it.projectId == project.id && it.session.isRunning }) {
            Text(stringResource(R.string.remove_project), color = MaterialTheme.colorScheme.error)
        }
    } }
    removing?.let { project -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.remove_project)) },
        text = { Text(stringResource(if (workbench.projects.isBound(project.id)) R.string.local_project_unbind_confirmation else R.string.remove_confirmation, project.name)) },
        confirmButton = { TextButton(onClick = { removing = null; onOpened(null); workbench.removeProject(project.id) }) { Text(stringResource(R.string.remove)) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
internal fun ProjectKindsText(project: Project) {
    val kinds = project.kinds.map { stringResource(it.label) }
    Text(if (kinds.isEmpty()) stringResource(R.string.project_type_unset) else kinds.joinToString(stringResource(R.string.type_separator)),
        style = MaterialTheme.typography.bodySmall, color = TertiaryText,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ProjectFilesPage(workbench: Workbench, state: WorkbenchState, project: Project, onBack: () -> Unit, onExport: () -> Unit, onRemove: () -> Unit, onAi: (String) -> Unit, referencedPath: String? = null) {
    var showReferencedDirectory by remember(project.id, referencedPath) { mutableStateOf(referencedPath != null) }
    var tasks by rememberSaveable(project.id) { mutableStateOf(false) }
    var git by rememberSaveable(project.id) { mutableStateOf(false) }
    var directory by rememberSaveable(project.id) { mutableStateOf(referencedPath?.substringBeforeLast('/', "").orEmpty()) }
    LaunchedEffect(referencedPath) { if (referencedPath != null) directory = referencedPath.substringBeforeLast('/', "") }
    var revision by remember { mutableIntStateOf(0) }
    val listing = rememberDirectoryListing(project.id, directory, revision, state.fileRevision) {
        withContext(Dispatchers.IO) { workbench.projects.files(project.id, directory) }
    }
    val files = listing.entries
    val linkedListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(files, referencedPath) {
        val index = files.indexOfFirst { it.relative == referencedPath }
        if (index >= 0) linkedListState.scrollToItem(index)
    }
    val failure = listing.failure?.message.orEmpty()
    val loading = listing.loading
    var menu by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var fileActions by remember { mutableStateOf<ProjectFile?>(null) }
    var fileOperation by remember { mutableStateOf<Pair<ProjectFile, String>?>(null) }
    var actionName by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val fileScope = rememberCoroutineScope()
    val apkContext = androidx.compose.ui.platform.LocalContext.current
    val apkInstaller = remember(workbench) { dev.zakocoder.app.projects.ProjectApkInstaller(apkContext, workbench.projects) }
    var installingApk by remember(project.id) { mutableStateOf<ProjectFile?>(null) }
    var apkBusy by remember(project.id) { mutableStateOf(false) }
    var apkFailure by remember(project.id) { mutableStateOf<String?>(null) }
    var apkPreparation by remember(project.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val currentApkProject by rememberUpdatedState(project.id)
    val apkUnavailableMessage = stringResource(R.string.apk_install_unavailable)
    val apkPrepareFailedMessage = stringResource(R.string.apk_prepare_failed)
    DisposableEffect(project.id) {
        onDispose { apkPreparation?.cancel() }
    }
    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun openFile(file: ProjectFile) {
        if (file.directory) directory = file.relative
        else if (file.name.endsWith(".apk", ignoreCase = true)) installingApk = file
        else { showReferencedDirectory = false; workbench.editor.open(project.id, file.relative) }
    }

    var fileName by remember { mutableStateOf("") }
    var uploadProjectId by rememberSaveable { mutableStateOf(project.id) }
    var uploadDirectory by rememberSaveable { mutableStateOf("") }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) workbench.importFile(uploadProjectId, uploadDirectory, uri)
    }
    BackHandler { if (directory.isNotEmpty()) directory = directory.substringBeforeLast('/', "") else onBack() }
    if (tasks) { ProjectTasksPage(workbench, project.id, onAi = onAi) { tasks = false }; return }
    if (git) { ProjectGitPage(workbench, project.id) { git = false }; return }
    val editor by workbench.editor.state.collectAsStateWithLifecycle()
    if (editor.projectId == project.id && !showReferencedDirectory) {
        CodeEditorPage(workbench.editor) { reference -> if (workbench.referenceAi(project.id, reference)) onAi(project.id) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.back_to_library)) }
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(workbench.projects.bindingPath(project.id) ?: stringResource(R.string.working_copy),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { WorkbenchIcon(Glyph.More, description = stringResource(R.string.project_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.git_title)) }, onClick = { menu = false; git = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_commands)) }, onClick = { menu = false; tasks = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.detect_project)) }, enabled = !state.busy && state.selectedId == project.id,
                        onClick = { menu = false; workbench.redetect() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_project)) }, enabled = !state.busy && state.selectedId == project.id,
                        onClick = { menu = false; onExport() }, leadingIcon = { WorkbenchIcon(Glyph.Export) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_project), color = MaterialTheme.colorScheme.error) },
                        enabled = !state.busy && state.sessions.none { it.projectId == project.id && it.session.isRunning },
                        onClick = { menu = false; onRemove() })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (directory.isNotEmpty()) IconButton(onClick = { directory = directory.substringBeforeLast('/', "") }) { WorkbenchIcon(Glyph.Up, description = stringResource(R.string.parent_directory)) }
            Text(directory.ifEmpty { stringResource(R.string.project_root) }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { revision++ }) { WorkbenchIcon(Glyph.Refresh, description = stringResource(R.string.refresh)) }
        }
        if (editor.tabs.any { it.projectId == project.id }) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = { editor.tabs.firstOrNull { it.projectId == project.id }?.let { showReferencedDirectory = false; workbench.editor.open(it.projectId, it.path) } }) { Text(stringResource(R.string.editor_drafts)) }
        }
        LazyColumn(Modifier.weight(1f), state = linkedListState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failure.isNotEmpty()) item { Text(failure, color = MaterialTheme.colorScheme.error) }
            if (!loading && failure.isEmpty() && referencedPath != null && directory == referencedPath.substringBeforeLast('/', "") && files.none { it.relative == referencedPath }) item {
                Text(stringResource(R.string.ai_linked_file_missing, referencedPath), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!loading && files.isEmpty() && failure.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Glyph.Projects)
                    Text(stringResource(R.string.directory_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.empty_files_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(files, key = { it.relative }) { file ->
                val first = file == files.first()
                val last = file == files.last()
                Surface(color = if (file.relative == referencedPath) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = cardBorder(), shape = RoundedCornerShape(
                    topStart = if (first) 18.dp else 0.dp, topEnd = if (first) 18.dp else 0.dp,
                    bottomStart = if (last) 18.dp else 0.dp, bottomEnd = if (last) 18.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { openFile(file) }, onLongClick = { fileActions = file })
                        .padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile(if (file.directory) Glyph.Projects else Glyph.File, Modifier.size(36.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (file.relative == referencedPath) Text(stringResource(R.string.ai_linked_file), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (file.modifiedAt > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(file.modifiedAt))
                                else stringResource(R.string.file_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (file.directory) WorkbenchIcon(Glyph.Next) else Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, file.sizeBytes),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (file != files.last()) GroupDivider()
            }
        }
        Surface(color = PanelBackground, shadowElevation = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(shape = RoundedCornerShape(12.dp), onClick = { uploadProjectId = project.id; uploadDirectory = directory; upload.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    WorkbenchIcon(Glyph.Import, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.upload_file))
                }
                WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { creatingFile = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    WorkbenchIcon(Glyph.Add, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.create_file))
                }
            }
        }
    }
    installingApk?.let { file ->
        val needsPermission = apkInstaller.needsSourcePermission()
        AlertDialog(onDismissRequest = { if (!apkBusy) installingApk = null },
            title = { Text(stringResource(if (needsPermission) R.string.apk_source_permission else R.string.apk_install)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (needsPermission) R.string.apk_source_permission_hint else R.string.apk_install_confirmation, file.relative))
                if (apkBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.apk_preparing)) }
                apkFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !apkBusy, onClick = {
                apkFailure = null
                if (needsPermission) {
                    try { apkLauncher.launch(apkInstaller.sourcePermissionIntent()); installingApk = null }
                    catch (_: android.content.ActivityNotFoundException) { apkFailure = apkUnavailableMessage }
                    catch (_: SecurityException) { apkFailure = apkUnavailableMessage }
                } else apkPreparation = fileScope.launch {
                    apkBusy = true
                    try {
                        val prepared = apkInstaller.prepare(project.id, file.relative)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (currentApkProject == project.id) {
                            apkLauncher.launch(prepared)
                            installingApk = null
                        }
                    }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: android.content.ActivityNotFoundException) { apkFailure = apkUnavailableMessage }
                    catch (_: SecurityException) { apkFailure = apkUnavailableMessage }
                    catch (error: Exception) { apkFailure = error.message ?: apkPrepareFailedMessage }
                    finally { apkBusy = false }
                }
            }) { Text(stringResource(if (needsPermission) R.string.apk_source_permission else R.string.apk_install)) } },
            dismissButton = { TextButton(enabled = !apkBusy, onClick = { installingApk = null; apkFailure = null }) { Text(stringResource(R.string.cancel)) } })
    }
    fileActions?.let { file -> WorkbenchSheet(file.name, { fileActions = null }) {
        TextButton(onClick = { fileActions = null; openFile(file) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_open)) }
        TextButton(onClick = { fileActions = null; actionName = file.name; fileOperation = file to "rename" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_rename)) }
        if (!file.directory) TextButton(onClick = { fileActions = null; actionName = file.name; fileOperation = file to "copy" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_copy)) }
        TextButton(onClick = { fileScope.launch { clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("", file.relative))) }; fileActions = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_copy_path)) }
        TextButton(onClick = { fileActions = null; fileOperation = file to "delete" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.file_delete), color = MaterialTheme.colorScheme.error) }
    } }
    fileOperation?.let { (file, action) ->
        AlertDialog(onDismissRequest = { fileOperation = null }, title = { Text(stringResource(when (action) { "rename" -> R.string.file_rename; "copy" -> R.string.file_copy; else -> R.string.file_delete })) },
            text = { if (action == "delete") Text(stringResource(R.string.file_delete_confirmation, file.relative))
                else OutlinedTextField(actionName, { actionName = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }) },
            confirmButton = { TextButton(onClick = { fileOperation = null; workbench.fileAction(project.id, file.relative, action, actionName) }, enabled = !state.busy && (action == "delete" || actionName.isNotBlank())) { Text(stringResource(if (action == "delete") R.string.file_delete else R.string.editor_save)) } },
            dismissButton = { TextButton(onClick = { fileOperation = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (creatingFile) WorkbenchSheet(stringResource(R.string.create_file), { creatingFile = false }) {
        OutlinedTextField(fileName, { fileName = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
        WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { creatingFile = false; workbench.createFile(project.id, directory, fileName); fileName = "" },
            enabled = !state.busy && fileName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.create)) }
        OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { creatingFile = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }

}

/** Existing project entry points, exposed before the name-only creation form. */
@Composable
internal fun AddProjectSheet(enabled: Boolean, onDismiss: () -> Unit, onImport: () -> Unit,
                             onCreate: () -> Unit, onClone: () -> Unit, onBind: (() -> Unit)? = null) {
    WorkbenchSheet(stringResource(R.string.mobile_add_project), onDismiss,
        stringResource(if (onBind == null) R.string.library_description else R.string.local_project_source_hint)) {
        ProjectEntryOption(stringResource(R.string.mobile_import_source), stringResource(R.string.mobile_import_source_hint),
            Glyph.Import, enabled, primary = true, onClick = onImport)
        ProjectEntryOption(stringResource(R.string.mobile_create_empty), stringResource(R.string.mobile_create_empty_hint),
            Glyph.ProjectAdd, enabled, onClick = onCreate)
        if (onBind != null) ProjectEntryOption(stringResource(R.string.local_project_bind), stringResource(R.string.local_project_bind_hint),
            Glyph.Projects, enabled, onClick = onBind)
        ProjectEntryOption(stringResource(R.string.git_clone), stringResource(R.string.mobile_clone_hint),
            Glyph.History, enabled, onClick = onClone)
    }
}

@Composable
private fun ProjectEntryOption(title: String, description: String, glyph: Glyph, enabled: Boolean,
                               primary: Boolean = false, onClick: () -> Unit) {
    val foreground = if (primary) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = if (primary) BrandFill else MaterialTheme.colorScheme.surface,
        contentColor = foreground,
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkbenchIcon(glyph, Modifier.size(20.dp), tint = foreground)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = if (primary) foreground else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WorkbenchIcon(Glyph.Next, Modifier.size(16.dp), tint = foreground)
        }
    }
}

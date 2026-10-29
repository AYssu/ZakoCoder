package dev.zakocoder.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.*
import dev.zakocoder.app.workflow.ProjectRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EnvironmentPage(workbench: Workbench, state: WorkbenchState, onTerminal: () -> Unit, onProjects: () -> Unit, onAi: ((String) -> Unit)? = null) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val project = state.project
    val resourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(workbench::installRuntime) }
    var maintenance by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(false) }
    var dependency by remember { mutableStateOf<Pair<String, DependencyPlan>?>(null) }
    var selectedBundles by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedRun by remember(project?.id) { mutableStateOf<ProjectRun?>(null) }
    val dependencyRuns = rememberDirectoryListing(project?.id, state.busy, state.fileRevision, enabled = project != null) {
        withContext(Dispatchers.IO) { workbench.projectTasks.runs(project!!.id).filter { it.source == "dependency" } }
    }
    LaunchedEffect(project?.id, state.fileRevision) { workbench.refreshDependencies() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, workbench) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) workbench.refreshDependencies()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.bundleTools) {
        selectedBundles = selectedBundles.filter { id -> ToolBundles.catalog.any { it.id == id && ToolBundles.status(it, state.bundleTools) != BundleStatus.Ready } }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeading(stringResource(R.string.environment_title)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            OutlinedIconButton(onClick = workbench::doctor, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), enabled = !state.busy && !state.checkingTools && !state.checkingPlans) {
                WorkbenchIcon(Glyph.Refresh, Modifier.size(17.dp), description = stringResource(R.string.check_tools))
            }
            }
        }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(top = 0.dp, bottom = 20.dp)) {
        item { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF1E3450) else androidx.compose.ui.graphics.Color(0xFFE3EAFB))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(Glyph.Environment, tint = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.ubuntu_version), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.runtime_architecture), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(stringResource(if (state.installed) R.string.bundle_ready else R.string.bundle_missing), if (state.installed) ReadyGreen else WaitingAmber)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.storage_title), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.usedBytes?.let(StorageUsage::format) ?: stringResource(R.string.storage_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (state.checkingTools) R.string.checking_tools else if (state.bundleTools.isEmpty()) R.string.bundle_unknown else R.string.tools_check_complete),
                        Modifier.weight(1f), fontSize = 11.5.sp, color = TertiaryText)
                    val maintenanceDescription = stringResource(R.string.environment_maintenance)
                    TextButton(onClick = { maintenance = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = maintenanceDescription }) {
                        Text(stringResource(R.string.maintenance_short), fontSize = 12.5.sp)
                        Spacer(Modifier.width(4.dp))
                        WorkbenchIcon(Glyph.Next, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!state.installed) WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { workbench.installRuntime() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.install_runtime)) }
            }
        } }
        item { DetailGroup(stringResource(R.string.project_selected)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { if (project == null) onProjects() else profiles = true }
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Glyph.Projects, Modifier.size(38.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(project?.name ?: stringResource(R.string.select_project_first), style = MaterialTheme.typography.titleSmall)
                    if (project != null) ProjectKindsText(project)
                }
                WorkbenchIcon(Glyph.Next, Modifier.size(17.dp), tint = TertiaryText)
            }
        } }
        item { ToolBundleList(workbench, state, selectedBundles, { selectedBundles = it }, onTerminal) }
        if (project != null) {
            item { DetailGroup(stringResource(R.string.project_dependencies)) {
                if (state.plansProjectId != project.id || state.checkingPlans) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                else if (state.plansError) Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.dependency_scan_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = workbench::refreshDependencies) { Text(stringResource(R.string.editor_retry)) }
                }
                else if (state.plans.isEmpty()) Text(stringResource(R.string.no_dependency_plan), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else state.plans.forEachIndexed { index, plan ->
                    if (index > 0) GroupDivider()
                    DetailRow(plan.command, stringResource(R.string.install_dependencies), enabled = state.installed && !state.busy, onClick = { dependency = project.id to plan })
                }
            } }
            item { DetailGroup(stringResource(R.string.dependency_run_history)) {
                when {
                    dependencyRuns.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    dependencyRuns.failure != null -> Text(stringResource(R.string.project_tasks_load_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    dependencyRuns.entries.isEmpty() -> Text(stringResource(R.string.dependency_run_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    else -> dependencyRuns.entries.forEachIndexed { index, run ->
                        if (index > 0) GroupDivider()
                        DetailRow(run.script.take(120), runStatus(run.status) + " · " + (run.exit?.let { stringResource(R.string.project_exit_value, it) } ?: stringResource(R.string.project_exit_unknown)), onClick = { selectedRun = run })
                    }
                }
            } }
        }
        if (state.log.isNotEmpty()) item { DetailGroup(stringResource(R.string.execution_log)) {
            DetailRow(stringResource(if (logs) R.string.hide_log else R.string.show_log), "", onClick = { logs = !logs })
            if (logs) SelectionContainer { Text(state.log.takeLast(20000), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        } }
    }
    ToolBundleInstallAction(workbench, state, selectedBundles) { selectedBundles = emptyList() }
    }
    if (maintenance) WorkbenchSheet(stringResource(R.string.environment_maintenance), { maintenance = false }, stringResource(R.string.ubuntu_version)) {
        Text(stringResource(R.string.runtime_description), style = MaterialTheme.typography.bodyMedium)
        if (state.installed) WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { maintenance = false; repair = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.repair_packages)) }
        else {
            OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { maintenance = false; resourcePicker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_runtime)) }
            Text(stringResource(R.string.offline_runtime_help), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (profiles && project != null) {
        var kinds by remember(project.id, project.kinds) { mutableStateOf(project.kinds) }
        WorkbenchSheet(stringResource(R.string.configure_project), { profiles = false }, stringResource(R.string.profile_help)) {
            ProjectKind.entries.forEach { kind -> Row(Modifier.fillMaxWidth().clickable { kinds = if (kind in kinds) kinds - kind else kinds + kind }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(kind in kinds, onCheckedChange = { checked -> kinds = if (checked) kinds + kind else kinds - kind })
                Text(stringResource(kind.label))
            } }
            WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { profiles = false; workbench.kinds(kinds) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_settings)) }
            OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { profiles = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
    if (repair) AlertDialog(onDismissRequest = { repair = false }, title = { Text(stringResource(R.string.repair_packages)) },
        text = { Text(stringResource(R.string.repair_confirmation)) },
        confirmButton = { TextButton(onClick = { repair = false; workbench.repairPackages() }) { Text(stringResource(R.string.run)) } },
        dismissButton = { TextButton(onClick = { repair = false }) { Text(stringResource(R.string.cancel)) } })
    dependency?.let { (id, plan) -> AlertDialog(onDismissRequest = { dependency = null }, title = { Text(stringResource(R.string.install_dependencies)) },
        text = { Text(stringResource(R.string.dependency_confirmation, "/workspace/$id", plan.command)) },
        confirmButton = { TextButton(onClick = { dependency = null; workbench.installDependencies(id, plan) }) { Text(stringResource(R.string.run)) } },
        dismissButton = { TextButton(onClick = { dependency = null }) { Text(stringResource(R.string.cancel)) } }) }
    selectedRun?.let { run -> project?.let { current ->
        ProjectRunLog(workbench, current.id, run, onAi) { selectedRun = null }
    } }
}

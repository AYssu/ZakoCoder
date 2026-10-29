package dev.zakocoder.app.ui

import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.runtime.*

@Composable
internal fun ToolBundleList(workbench: Workbench, state: WorkbenchState, selected: List<String>, onSelected: (List<String>) -> Unit, onTerminal: () -> Unit) {
    var detail by remember { mutableStateOf<ToolBundle?>(null) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val checks = state.bundleTools
    val ready = ToolBundles.catalog.count { ToolBundles.status(it, checks) == BundleStatus.Ready }
    val pending = ToolBundles.catalog.count { ToolBundles.status(it, checks) in setOf(BundleStatus.Missing, BundleStatus.Broken) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tool_bundles), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { managing = !managing; if (!managing) onSelected(emptyList()) },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(stringResource(if (managing) R.string.done else R.string.manage_tools), fontSize = 11.5.sp)
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(stringResource(R.string.filter_all), stringResource(R.string.filter_ready, ready), stringResource(R.string.filter_pending, pending)).forEachIndexed { index, title ->
                Box(Modifier.weight(1f).heightIn(min = 48.dp).background(if (filter == index) {
                    if (LocalWorkbenchDark.current) androidx.compose.ui.graphics.Color(0xFF2E3A49) else MaterialTheme.colorScheme.surface
                } else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                    .selectable(selected = filter == index, role = androidx.compose.ui.semantics.Role.Tab) { filter = index }
                    .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = if (filter == index) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (filter == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.checkingTools) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.checking_tools), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.toolCheckError.isNotEmpty()) Text(state.toolCheckError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        val bundles = ToolBundles.catalog.filter { bundle ->
            val status = ToolBundles.status(bundle, checks)
            filter == 0 || filter == 1 && status == BundleStatus.Ready || filter == 2 && status in setOf(BundleStatus.Missing, BundleStatus.Broken)
        }
        Surface(shape = RoundedCornerShape(18.dp), border = cardBorder(), shadowElevation = cardElevation()) {
            Column {
                if (bundles.isEmpty()) Text(stringResource(R.string.no_matching_tools), Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                bundles.forEachIndexed { index, bundle ->
                    if (index > 0) GroupDivider()
                    val status = ToolBundles.status(bundle, checks)
                    val selectable = !bundle.manual && status != BundleStatus.Ready && state.installed && !state.busy && !state.checkingTools
                    val selectLabel = stringResource(R.string.select_bundle, stringResource(bundle.title))
                    val detailLabel = stringResource(R.string.bundle_details, stringResource(bundle.title))
                    Row(Modifier.fillMaxWidth().clickable { detail = bundle }.semantics { contentDescription = detailLabel }
                        .padding(start = if (managing) 4.dp else 14.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (managing) Checkbox(checked = bundle.id in selected,
                            onCheckedChange = { checked -> onSelected(if (checked) (selected + bundle.id).distinct() else selected - bundle.id) },
                            enabled = selectable, modifier = Modifier.semantics { contentDescription = selectLabel })
                        else Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                Text(bundleMonogram(bundle), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(bundle.title), style = MaterialTheme.typography.titleSmall)
                            val version = checks.firstOrNull { it.id == bundle.tools.first() && it.status == ToolStatus.Ready }?.version
                            Text(version ?: stringResource(bundle.description), style = MaterialTheme.typography.bodySmall,
                                color = TertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BundleStatusChip(bundle, checks)
                        WorkbenchIcon(Glyph.Next, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(stringResource(if (!state.installed) R.string.tools_install_hint else R.string.tool_bundles_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    detail?.let { bundle -> WorkbenchSheet(stringResource(bundle.title), { detail = null }, stringResource(bundle.description),
        leading = {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Text(bundleMonogram(bundle), style = MaterialTheme.typography.titleSmall) }
            }
        }, status = { BundleStatusChip(bundle, checks) }) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bundle.tools.forEach { id ->
            val check = checks.singleOrNull { it.id == id }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(id, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when (check?.status) { ToolStatus.Ready -> R.string.tool_ready; ToolStatus.Missing -> R.string.tool_missing; ToolStatus.Broken -> R.string.tool_broken; null -> R.string.bundle_unknown }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            check?.takeIf { it.status != ToolStatus.Missing }?.let {
                Text(it.version, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        }
        if (ToolBundles.status(bundle, checks) == BundleStatus.Ready) WorkbenchPrimaryButton(onClick = { detail = null; onTerminal() },
            enabled = state.project != null && state.installed, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.use_in_terminal)) }
        if (state.project == null && ToolBundles.status(bundle, checks) == BundleStatus.Ready) Text(stringResource(R.string.select_project_first), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        if (bundle.id == "android") Text(stringResource(R.string.android_build_help), style = MaterialTheme.typography.bodySmall)
        if (bundle.id == "android-ndk") Text(stringResource(R.string.android_ndk_build_help), style = MaterialTheme.typography.bodySmall)
        if (bundle.manual) Text(stringResource(R.string.manual_toolchain), style = MaterialTheme.typography.bodySmall)
        else if (ToolBundles.status(bundle, checks) != BundleStatus.Ready) WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = {
            managing = true; onSelected((selected + bundle.id).distinct()); detail = null
        }, enabled = state.installed && !state.busy && !state.checkingTools, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.select_for_install))
        }
        OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = workbench::doctor, enabled = state.installed && !state.busy && !state.checkingTools, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.check_tools))
        }
    } }
}

@Composable
private fun BundleStatusChip(bundle: ToolBundle, checks: List<ToolCheck>) {
    val status = ToolBundles.status(bundle, checks)
    val partial = status == BundleStatus.Missing && checks.any { it.id in bundle.tools && it.status == ToolStatus.Ready }
    val label = if (bundle.manual && status != BundleStatus.Ready) R.string.bundle_manual else when (status) {
        BundleStatus.Unknown -> R.string.bundle_unknown
        BundleStatus.Ready -> R.string.bundle_ready
        BundleStatus.Missing -> if (partial) R.string.bundle_partial else R.string.bundle_missing
        BundleStatus.Broken -> R.string.tool_broken
    }
    StatusChip(stringResource(label), when (status) {
        BundleStatus.Ready -> ReadyGreen; BundleStatus.Missing -> WaitingAmber; BundleStatus.Broken -> FailureRed
        BundleStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    })
}

@Composable
internal fun ToolBundleInstallAction(workbench: Workbench, state: WorkbenchState, selected: List<String>, onInstalled: () -> Unit) {
    var confirmation by remember { mutableStateOf<Set<String>?>(null) }
    var androidLicense by remember { mutableStateOf(false) }
    var licenseBrowserError by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val checks = state.bundleTools
    val actionable = selected.filter { id -> ToolBundles.catalog.any { it.id == id && !it.manual && ToolBundles.status(it, checks) != BundleStatus.Ready } }.toSet()
    if (selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.background) {
        WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { androidLicense = false; licenseBrowserError = false; confirmation = actionable }, enabled = state.installed && !state.busy && !state.checkingTools && actionable.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(stringResource(R.string.install_selected_count, actionable.size))
        }
    }
    confirmation?.let { ids ->
        val packages = ToolBundles.packages(ids, checks)
        val sdkReady = "android" in ids || checks.any { it.id == "android-sdk" && it.status == ToolStatus.Ready }
        val canInstallNdk = "android-ndk" !in ids || sdkReady
        WorkbenchSheet(stringResource(R.string.install_selected_bundles), { confirmation = null }) {
            Text(ids.map { id -> stringResource(ToolBundles.catalog.single { it.id == id }.title) }.joinToString(stringResource(R.string.type_separator)))
            if (packages.isNotEmpty()) Text(stringResource(R.string.package_confirmation, packages.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
            else Text(stringResource(R.string.android_managed_repair), style = MaterialTheme.typography.bodyMedium)
            if ("android-ndk" in ids) {
                Text(stringResource(R.string.android_ndk_install_details), style = MaterialTheme.typography.bodySmall)
                if (!canInstallNdk) Text(stringResource(R.string.android_ndk_sdk_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if ("android" in ids) {
                Text(stringResource(R.string.android_install_details), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    try {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            "https://developer.android.com/studio/terms".toUri()))
                        licenseBrowserError = false
                    } catch (_: android.content.ActivityNotFoundException) { licenseBrowserError = true }
                    catch (_: SecurityException) { licenseBrowserError = true }
                }) { Text(stringResource(R.string.android_sdk_license_open)) }
                if (licenseBrowserError) Text(stringResource(R.string.android_license_browser_unavailable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { androidLicense = !androidLicense }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = androidLicense, onCheckedChange = { androidLicense = it })
                    Text(stringResource(R.string.android_sdk_license), style = MaterialTheme.typography.bodySmall)
                }
            }
            WorkbenchPrimaryButton(shape = RoundedCornerShape(12.dp), onClick = { confirmation = null; onInstalled(); workbench.installToolBundles(ids, androidLicense) }, enabled = !state.busy && !state.checkingTools && (packages.isNotEmpty() || ids.any { it == "android" || it == "android-ndk" }) && canInstallNdk && ("android" !in ids || androidLicense), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.install)) }
            OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun bundleMonogram(bundle: ToolBundle): String = stringResource(when (bundle.id) {
    "git" -> R.string.tool_mark_git; "node" -> R.string.tool_mark_node; "python" -> R.string.tool_mark_python
    "go" -> R.string.tool_mark_go; "rust" -> R.string.tool_mark_rust; "jdk" -> R.string.tool_mark_java
    else -> bundle.title
}).take(2).trim()

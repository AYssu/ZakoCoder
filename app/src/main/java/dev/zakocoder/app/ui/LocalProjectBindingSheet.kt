package dev.zakocoder.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.zakocoder.app.R
import dev.zakocoder.app.projects.LocalDirectoryBrowser

/** Browses actual directories through an IO controller. No document-provider URI conversion. */
@Composable
internal fun LocalProjectBindingSheet(enabled: Boolean, onDismiss: () -> Unit, onBind: (String) -> Unit, initialDirectory: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = remember(context, scope) { LocalDirectoryBrowser(context, scope) }
    val state by browser.state.collectAsState()
    var permissionRevision by remember { mutableIntStateOf(0) }
    var permissionError by remember { mutableStateOf(false) }
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val authorized = remember(permissionRevision) { hasAccess() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++ }
    LaunchedEffect(authorized, permissionRevision) { if (authorized) browser.open(initialDirectory) }
    WorkbenchSheet(stringResource(R.string.local_browser_title), onDismiss, stringResource(R.string.local_browser_description)) {
        if (!authorized) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkbenchIcon(Glyph.Projects, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.local_browser_permission_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.local_browser_permission_description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = {
                permissionError = false
                if (Build.VERSION.SDK_INT >= 30) {
                    try { settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())) }
                    catch (_: android.content.ActivityNotFoundException) {
                        try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        catch (_: android.content.ActivityNotFoundException) { permissionError = true }
                        catch (_: SecurityException) { permissionError = true }
                    } catch (_: SecurityException) { permissionError = true }
                } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.local_browser_grant))
            }
        } else {
            if (state.roots.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.roots.forEach { root ->
                    OutlinedButton(onClick = { browser.open(root.path) }, enabled = enabled && !state.loading) {
                        Text(stringResource(R.string.local_browser_storage, root.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.local_browser_current), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.current?.path.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.parent != null) TextButton(onClick = { state.parent?.let(browser::open) }, enabled = enabled && !state.loading) {
                WorkbenchIcon(Glyph.Back, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.local_browser_up))
            }
            when {
                state.loading -> Row(Modifier.heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.local_browser_loading))
                }
                state.error.isNotEmpty() -> {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { browser.open(state.current?.path) }) { Text(stringResource(R.string.local_browser_retry)) }
                }
                state.directories.isEmpty() -> Text(stringResource(R.string.local_browser_empty), Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    state.directories.forEach { directory ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { browser.open(directory.path) }
                            .heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WorkbenchIcon(Glyph.Projects, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(directory.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            WorkbenchIcon(Glyph.Next, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.truncated) TextButton(onClick = browser::more, enabled = enabled && !state.loading) { Text(stringResource(R.string.local_browser_more)) }
            Text(stringResource(if (state.selectable) R.string.local_browser_original_notice else R.string.local_browser_choose_project),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { state.current?.path?.let(onBind); onDismiss() },
                enabled = enabled && !state.loading && state.selectable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.local_browser_use)) }
        }
        if (permissionError) Text(stringResource(R.string.local_project_storage_error), color = MaterialTheme.colorScheme.error)
    }
}

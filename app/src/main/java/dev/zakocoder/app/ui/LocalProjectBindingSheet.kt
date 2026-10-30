package dev.zakocoder.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.zakocoder.app.R

/** Explicit filesystem access, independent from model tool permissions. Never converts a SAF URI. */
@Composable
internal fun LocalProjectBindingSheet(enabled: Boolean, onDismiss: () -> Unit, onBind: (String) -> Unit) {
    val context = LocalContext.current
    var path by rememberSaveable { mutableStateOf("") }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var permissionError by remember { mutableStateOf(false) }
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val authorized = remember(permissionRevision) { hasAccess() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionRevision++ }
    WorkbenchSheet(stringResource(R.string.local_project_bind), onDismiss, stringResource(R.string.local_project_bind_hint)) {
        OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.local_project_path)) },
            placeholder = { Text(stringResource(R.string.local_project_path_example)) },
            supportingText = { Text(stringResource(R.string.local_project_path_help)) },
            modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true)
        if (!authorized) {
            Text(stringResource(R.string.local_project_storage_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                permissionError = false
                if (Build.VERSION.SDK_INT >= 30) {
                    try { settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())) }
                    catch (_: android.content.ActivityNotFoundException) {
                        try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        catch (_: android.content.ActivityNotFoundException) { permissionError = true }
                        catch (_: SecurityException) { permissionError = true }
                    } catch (_: SecurityException) { permissionError = true }
                } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }, enabled = enabled) { Text(stringResource(R.string.local_project_storage_grant)) }
        }
        if (permissionError) Text(stringResource(R.string.local_project_storage_error), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.local_project_direct_warning), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onBind(path.trim()); onDismiss() }, enabled = enabled && path.trim().startsWith('/'), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.local_project_bind))
        }
    }
}

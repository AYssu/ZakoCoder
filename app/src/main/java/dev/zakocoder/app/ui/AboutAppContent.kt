package dev.zakocoder.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun installedAppVersion(): String {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.about_version_unavailable)
    val version by produceState(initialValue = stringResource(R.string.loading), key1 = context, key2 = unavailable) {
        value = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName?.takeIf(String::isNotBlank) ?: unavailable
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { unavailable }
        }
    }
    return version
}

@Composable
internal fun AboutAppContent(version: String, onSshLicense: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = BrandFill, shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { WorkbenchBrandMark(Modifier.fillMaxSize()) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(stringResource(R.string.about_product_description))
    CommunityEntry()
    CopyHelp(stringResource(R.string.about_license_summary), stringResource(R.string.about_license_components))
    LicenseOriginalsEntry()
    SshLicenseEntry(onSshLicense)
}

@Composable
private fun CommunityEntry() {
    val context = LocalContext.current
    val groupName = stringResource(R.string.community_group_name)
    val groupNumber = stringResource(R.string.community_group_number)
    val groupUrl = stringResource(R.string.community_group_url)
    var feedback by remember { mutableStateOf<Int?>(null) }
    WorkbenchCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.community_title), style = MaterialTheme.typography.titleSmall)
                Text(groupName, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.community_group_number_label, groupNumber),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchPrimaryButton(onClick = {
                    feedback = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, groupUrl.toUri())
                            .addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        feedback = R.string.community_open_failed
                    } catch (_: SecurityException) {
                        feedback = R.string.community_open_failed
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.community_join))
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    try {
                        if (clipboard == null) {
                            feedback = R.string.community_copy_failed
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText(groupName, groupNumber))
                            feedback = R.string.community_copied
                        }
                    } catch (_: SecurityException) {
                        feedback = R.string.community_copy_failed
                    }
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.community_copy_number))
                }
            }
            feedback?.let { message ->
                Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

private data class LicenseListing(val files: List<String>? = null, val failed: Boolean = false)

private data class LicenseDocument(val content: String? = null, val failed: Boolean = false)

@Composable
private fun LicenseOriginalsEntry() {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.about_license_originals))
    }
    if (choosing) {
        val listing by produceState(initialValue = LicenseListing()) {
            value = try {
                LicenseListing(files = withContext(Dispatchers.IO) { context.assets.list("licenses")?.sorted().orEmpty() })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { LicenseListing(failed = true) }
        }
        WorkbenchAlertDialog(onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.about_license_originals_title)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    val available = listing.files
                    if (listing.failed || available?.isEmpty() == true) Text(stringResource(R.string.about_license_read_failed), color = MaterialTheme.colorScheme.error)
                    else if (available == null) Text(stringResource(R.string.loading))
                    else available.forEach { file ->
                        WorkbenchDialogButton(onClick = { choosing = false; selected = file }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(file.removeSuffix(".txt"), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { WorkbenchDialogButton(onClick = { choosing = false }) { Text(stringResource(R.string.close)) } })
    }
    selected?.let { file ->
        val document by produceState(initialValue = LicenseDocument(), key1 = file) {
            value = try {
                LicenseDocument(content = withContext(Dispatchers.IO) {
                    context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { LicenseDocument(failed = true) }
        }
        WorkbenchSheet(file.removeSuffix(".txt"), { selected = null }) {
            val dismissSheet = LocalWorkbenchSheetDismiss.current
            when {
                document.failed -> Text(stringResource(R.string.about_license_read_failed), color = MaterialTheme.colorScheme.error)
                document.content == null -> Text(stringResource(R.string.loading))
                else -> SelectionContainer { Text(document.content.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = dismissSheet, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

package dev.zakocoder.app.ui

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
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { WorkbenchBrandMark(Modifier.size(28.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(stringResource(R.string.about_product_description))
    CopyHelp(stringResource(R.string.about_license_summary), stringResource(R.string.about_license_components))
    LicenseOriginalsEntry()
    SshLicenseEntry(onSshLicense)
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
                        TextButton(onClick = { choosing = false; selected = file }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(file.removeSuffix(".txt"), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.close)) } })
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
            when {
                document.failed -> Text(stringResource(R.string.about_license_read_failed), color = MaterialTheme.colorScheme.error)
                document.content == null -> Text(stringResource(R.string.loading))
                else -> SelectionContainer { Text(document.content.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { selected = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

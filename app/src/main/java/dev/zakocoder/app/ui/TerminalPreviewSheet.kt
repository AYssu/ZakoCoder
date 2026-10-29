package dev.zakocoder.app.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.terminal.LocalPreviewAddress
import dev.zakocoder.app.terminal.TerminalItem

/** Ephemeral URL editor: no saved URLs, readiness probes, WebView or implicit browser launches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalPreviewSheet(item: TerminalItem, discover: () -> List<String>, close: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { windowHeight.toDp() * .85f }
    var candidates by remember(item.id) { mutableStateOf(discover()) }
    var address by remember(item.id) { mutableStateOf(candidates.firstOrNull().orEmpty()) }
    var failed by remember(item.id) { mutableStateOf(false) }
    val normalized = LocalPreviewAddress.normalize(address)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, modifier = Modifier.statusBarsPadding(),
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = false, isAppearanceLightNavigationBars = !LocalWorkbenchDark.current),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.terminal_preview), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = close) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.close)) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.terminal_preview_source, item.number, item.projectName), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.terminal_preview_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.terminal_preview_candidates), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { candidates = discover() }) { Text(stringResource(R.string.terminal_preview_refresh)) }
                }
                if (candidates.isEmpty()) Text(stringResource(R.string.terminal_preview_empty), style = MaterialTheme.typography.bodySmall)
                candidates.forEach { candidate ->
                    SelectablePanel(address == candidate, { address = candidate; failed = false }) {
                        Text(candidate, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        SelectionCheck(address == candidate)
                    }
                }
                OutlinedTextField(address, { address = it.take(2049); failed = false }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.terminal_preview_address)) },
                    placeholder = { Text(stringResource(R.string.terminal_preview_placeholder)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = address.isNotBlank() && normalized == null,
                    supportingText = { if (address.isNotBlank() && normalized == null) Text(stringResource(R.string.terminal_preview_invalid)) })
                Text(stringResource(R.string.terminal_preview_mapping), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (failed) Text(stringResource(R.string.terminal_preview_browser_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            WorkbenchPrimaryButton(onClick = {
                if (normalized != null) try { uriHandler.openUri(normalized); failed = false }
                catch (_: ActivityNotFoundException) { failed = true }
                catch (_: SecurityException) { failed = true }
                catch (_: IllegalArgumentException) { failed = true }
            }, enabled = normalized != null, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.terminal_preview_open))
            }
        }
    }
}

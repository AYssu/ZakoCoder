package dev.zakocoder.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

@Composable
internal fun AiContextSheet(ai: AiController, state: AiState, onImages: (() -> Unit)? = null, close: () -> Unit) {
    var directory by remember(state.projectId) { mutableStateOf("") }
    var directoryRetry by remember(state.projectId, directory) { mutableIntStateOf(0) }
    val listing = rememberDirectoryListing(state.projectId, directory, directoryRetry) { ai.referenceFiles(directory) }
    var errorText by remember(state.projectId) { mutableStateOf("") }
    var tab by remember(state.projectId) { mutableIntStateOf(0) }
    var rulesExpanded by remember(state.projectId) { mutableStateOf(false) }
    val canReference = !state.active && state.references.size < 4
    WorkbenchSheet(stringResource(R.string.ai_context_attach), close,
        subtitle = stringResource(R.string.ai_attachment_subtitle)) {
        // Three equal actions remain reachable at 360dp and use the same themed surfaces as the app.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onImages != null) AttachmentAction(
                stringResource(R.string.ai_images_add), null, false,
                !state.active && !state.imagesLoading && state.pendingImages.size < AiImages.MAX_PER_TURN,
                Modifier.weight(1f), onImages,
            )
            AttachmentAction(stringResource(R.string.browse_files), Glyph.File, tab == 0, !state.active,
                Modifier.weight(1f), { tab = 0 })
            AttachmentAction(stringResource(R.string.ai_attachment_build_log), Glyph.Terminal, tab == 1, !state.active,
                Modifier.weight(1f), { tab = 1 })
        }
        if (state.imagesLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.ai_images_loading), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onImages != null) Text(
            stringResource(R.string.ai_attachment_image_count, state.pendingImages.size, AiImages.MAX_PER_TURN),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tab == 1) {
            Text(stringResource(R.string.ai_attachment_log_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(errorText, { if (it.length <= 6000) errorText = it },
                label = { Text(stringResource(R.string.ai_reference_error_log)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 128.dp, max = 224.dp),
                shape = RoundedCornerShape(12.dp), minLines = 4)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ai_attachment_log_length, errorText.length),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { ai.addReference(AiReference("build-output", errorText, "error")); close() },
                    enabled = errorText.isNotBlank() && canReference, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ai_context_add))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (directory.isNotEmpty()) IconButton(onClick = { directory = directory.substringBeforeLast('/', "") },
                    modifier = Modifier.size(48.dp)) {
                    WorkbenchIcon(Glyph.Back, description = stringResource(R.string.editor_back))
                } else WorkbenchIcon(Glyph.Projects, Modifier.size(20.dp))
                Text(directory.ifEmpty { stringResource(R.string.project_root) }, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.ai_attachment_reference_count, state.references.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                when {
                    listing.loading -> Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.ai_attachment_loading_files),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    listing.failure != null -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.directory_read_error), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { directoryRetry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.directory_retry))
                        }
                    }
                    listing.entries.isEmpty() -> Row(Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        WorkbenchIcon(Glyph.Projects)
                        Text(stringResource(R.string.directory_empty), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 264.dp)) {
                        items(listing.entries, key = { it.first }) { (path, isDirectory) ->
                            ListItem(
                                headlineContent = { Text(path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(stringResource(if (isDirectory) R.string.ai_attachment_open_folder else R.string.ai_attachment_select_file),
                                    style = MaterialTheme.typography.labelSmall) },
                                leadingContent = { WorkbenchIcon(if (isDirectory) Glyph.Projects else Glyph.File,
                                    tint = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                                trailingContent = { WorkbenchIcon(if (isDirectory) Glyph.Next else Glyph.Add, Modifier.size(18.dp)) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                modifier = Modifier.heightIn(min = 64.dp).clickable(
                                    enabled = if (isDirectory) !state.active else canReference, role = Role.Button,
                                ) {
                                    if (isDirectory) directory = path else { ai.addFileReference(path); close() }
                                },
                            )
                        }
                    }
                }
            }
            Text(stringResource(R.string.ai_attachment_file_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.references.size >= 4) Text(stringResource(R.string.ai_attachment_reference_full),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.active) Text(stringResource(R.string.ai_attachment_wait), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.ruleSources.isNotEmpty() || state.rulesLimited || state.policy.permission(AiTool.ReadFile) != AiPermission.Allow) {
            TextButton(onClick = { rulesExpanded = !rulesExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                WorkbenchIcon(Glyph.Shield, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_attachment_rules))
                Spacer(Modifier.width(4.dp))
                WorkbenchIcon(if (rulesExpanded) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp))
            }
            if (rulesExpanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.ruleSources.isNotEmpty()) Text(stringResource(R.string.ai_rules_sources, state.ruleSources.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.policy.permission(AiTool.ReadFile) != AiPermission.Allow) Text(stringResource(R.string.ai_rules_permission_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.rulesLimited) Text(stringResource(R.string.ai_rules_limited),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AttachmentAction(label: String, glyph: Glyph?, selected: Boolean, enabled: Boolean,
                             modifier: Modifier, click: () -> Unit) {
    val foreground = (if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        .copy(alpha = if (enabled) 1f else .38f)
    Surface(onClick = click, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = foreground) {
        Column(Modifier.heightIn(min = 88.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (glyph != null) WorkbenchIcon(glyph, Modifier.size(24.dp), tint = foreground)
            else Canvas(Modifier.size(24.dp)) {
                val stroke = Stroke(1.8.dp.toPx())
                val unit = size.width / 24f
                drawRoundRect(foreground, Offset(3 * unit, 3 * unit), Size(18 * unit, 18 * unit), CornerRadius(2 * unit), style = stroke)
                drawCircle(foreground, 1.5f * unit, Offset(8 * unit, 8 * unit))
                drawPath(Path().apply {
                    moveTo(4 * unit, 17 * unit); lineTo(10 * unit, 12 * unit)
                    lineTo(14 * unit, 15 * unit); lineTo(17 * unit, 11 * unit); lineTo(21 * unit, 15 * unit)
                }, foreground, style = stroke)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

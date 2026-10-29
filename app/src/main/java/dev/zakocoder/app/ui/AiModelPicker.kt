package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiController

/** Profile summaries only: opening this picker never reads keys or contacts a model service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiModelPicker(ai: AiController, close: () -> Unit, onAdd: () -> Unit, onManage: () -> Unit) {
    val state by ai.models.state.collectAsStateWithLifecycle()
    val task by ai.state.collectAsStateWithLifecycle()
    val editable = !task.active && !task.restoring && !state.loading && !state.saving && state.error.isEmpty()
    var selecting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.catalog.activeId, state.saving) {
        if (selecting != null && !state.saving && state.catalog.activeId == selecting) close()
    }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { windowHeight.toDp() * .8f }
    ModalBottomSheet(onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !LocalWorkbenchDark.current,
            isAppearanceLightNavigationBars = !LocalWorkbenchDark.current),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_choose_model), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = close) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.close)) }
            }
            if (task.active || task.restoring) Text(stringResource(R.string.ai_models_task_locked),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = ai.models::retry, enabled = !state.loading && !state.saving) { Text(stringResource(R.string.editor_retry)) }
            }
            if (!state.loading && state.error.isEmpty() && state.catalog.profiles.isEmpty()) {
                Text(stringResource(R.string.ai_models_empty_hint), Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)) {
                items(state.catalog.profiles, key = { it.id }) { profile ->
                    val active = profile.id == state.catalog.activeId
                    Surface(onClick = { if (active) close() else { selecting = profile.id; ai.models.activate(profile.id) } },
                        enabled = editable, modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton; selected = active },
                        shape = RoundedCornerShape(12.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(profile.model, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (active) WorkbenchIcon(Glyph.Check, description = stringResource(R.string.ai_models_current), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FilledTonalButton(onClick = onAdd, enabled = editable && state.catalog.profiles.size < 32,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                WorkbenchIcon(Glyph.Add, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ai_models_add))
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ai_manage_models))
            }
        }
    }
}

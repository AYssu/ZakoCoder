package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import java.net.URI

@Composable
internal fun AiModelsPage(ai: AiController, startAdding: Boolean = false, onStartAddingConsumed: () -> Unit = {}, close: () -> Unit) {
    val state by ai.models.state.collectAsStateWithLifecycle()
    val task by ai.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(startAdding) }
    // Consume the navigation request; editor drafts intentionally remain memory-only.
    LaunchedEffect(startAdding) { if (startAdding) onStartAddingConsumed() }
    var profile by remember { mutableStateOf<AiProfile?>(null) }
    var deleting by remember { mutableStateOf<AiProfile?>(null) }
    if (editing) {
        AiModelEditor(ai, profile) { editing = false }
        return
    }
    BackHandler(onBack = close)
    Column(Modifier.fillMaxSize()) {
        ModelNavigation(stringResource(R.string.ai_settings), close) {
            TextButton(onClick = { profile = null; ai.models.clearError(); editing = true }, enabled = !task.active && !state.loading && !state.saving && state.catalog.profiles.size < 32) {
                WorkbenchIcon(Glyph.Add, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ai_models_add))
            }
        }
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (task.active) Text(stringResource(R.string.ai_models_task_locked), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ai.models::retry) { Text(stringResource(R.string.editor_retry)) }
        }
        if (!state.loading && state.error.isEmpty() && state.catalog.profiles.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) { Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { WorkbenchIcon(Glyph.Ai, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) } }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.ai_models_empty), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.ai_models_empty_hint), Modifier.padding(top = 10.dp, bottom = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WorkbenchPrimaryButton(onClick = { profile = null; ai.models.clearError(); editing = true }, enabled = !task.active) { Text(stringResource(R.string.ai_models_add_first)) }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.ai_models_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.catalog.profiles, key = { it.id }) { item ->
                val selected = item.id == state.catalog.activeId
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            if (selected) Text(stringResource(R.string.ai_models_current), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        }
                        Text(item.model, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(URI(item.endpoint).host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { ai.models.activate(item.id) }, enabled = !selected && !task.active && !state.saving) { Text(stringResource(if (selected) R.string.ai_models_in_use else R.string.ai_models_use)) }
                            Spacer(Modifier.weight(1f))
                            val editDescription = stringResource(R.string.ai_models_edit_named, item.name)
                            TextButton(onClick = { profile = item; ai.models.clearError(); editing = true }, modifier = Modifier.semantics { contentDescription = editDescription }) { Text(stringResource(R.string.ai_models_edit)) }
                            val deleteDescription = stringResource(R.string.ai_models_delete_named, item.name)
                            IconButton(onClick = { deleting = item }, enabled = !task.active && !state.saving) { WorkbenchIcon(Glyph.Trash, Modifier.size(18.dp), description = deleteDescription) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.ai_models_delete_title)) },
        text = { Text(stringResource(R.string.ai_models_delete_hint, item.name) + if (item.id == "legacy") "\n" + stringResource(R.string.ai_models_legacy_retained) else "") }, confirmButton = { TextButton(onClick = { deleting = null; ai.models.delete(item.id) }) { Text(stringResource(R.string.file_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun ModelNavigation(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.ai_models_back)) }
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AiModelEditor(ai: AiController, original: AiProfile?, close: () -> Unit) {
    val state by ai.models.state.collectAsStateWithLifecycle()
    val task by ai.state.collectAsStateWithLifecycle()
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var endpoint by remember(original?.id) { mutableStateOf(original?.endpoint.orEmpty()) }
    var model by remember(original?.id) { mutableStateOf(original?.model.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    var maxEnabled by remember { mutableStateOf(original?.parameters?.maxTokens != null) }
    var maxTokens by remember { mutableStateOf(original?.parameters?.maxTokens?.toString() ?: "4096") }
    var tempEnabled by remember { mutableStateOf(original?.parameters?.temperature != null) }
    var temperature by remember { mutableStateOf(original?.parameters?.temperature?.toString() ?: "1.0") }
    var topEnabled by remember { mutableStateOf(original?.parameters?.topP != null) }
    var topP by remember { mutableStateOf(original?.parameters?.topP?.toString() ?: "1.0") }
    var picker by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var savingRevision by remember { mutableStateOf<Int?>(null) }
    val parameters = AiParameters(if (maxEnabled) maxTokens.toIntOrNull() else null, if (tempEnabled) temperature.toDoubleOrNull() else null, if (topEnabled) topP.toDoubleOrNull() else null)
    val parametersValid = (!maxEnabled || maxTokens.toIntOrNull() != null) && (!tempEnabled || temperature.toDoubleOrNull() != null) && (!topEnabled || topP.toDoubleOrNull() != null) && runCatching { parameters.validate() }.isSuccess
    val normalized = runCatching { AiConfigStore.endpoint(endpoint) }.getOrNull()
    val valid = name.isNotBlank() && name.length <= 80 && normalized != null && model.isNotBlank() && model.length <= 200 && parametersValid
    val dirty = name != original?.name.orEmpty() || endpoint != original?.endpoint.orEmpty() || model != original?.model.orEmpty() || key.isNotEmpty() || clearKey || parameters != (original?.parameters ?: AiParameters()) || !parametersValid
    val editable = !task.active && !state.saving
    fun changed() { ai.models.cancelProbe(); ai.models.clearError() }
    fun draft() = AiModelDraft(original?.id, name, endpoint, model, if (clearKey) "" else key.takeIf { it.isNotEmpty() }, parameters)
    fun leave() { if (state.saving) return; if (dirty) discard = true else close() }
    DisposableEffect(Unit) { onDispose { ai.models.cancelProbe() } }
    LaunchedEffect(state.revision) { if (savingRevision != null && state.revision > savingRevision!!) { key = ""; close() } }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize()) {
        ModelNavigation(stringResource(if (original == null) R.string.ai_models_add else R.string.ai_models_edit_title), ::leave) {
            TextButton(onClick = { savingRevision = state.revision; ai.models.save(draft()) }, enabled = editable && !state.loading && valid) { Text(stringResource(R.string.editor_save)) }
        }
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotEmpty()) Text(state.error, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (task.active) Text(stringResource(R.string.ai_models_task_locked), style = MaterialTheme.typography.bodySmall)
            ModelField(name, { name = it; changed() }, R.string.ai_models_name, enabled = editable)
            ModelSection(stringResource(R.string.ai_models_connection)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(stringResource(R.string.ai_models_protocol), fontSize = 13.sp, fontWeight = FontWeight.Medium); Text(stringResource(R.string.ai_models_protocol_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { templates = true }, enabled = editable) { Text(stringResource(R.string.ai_models_templates)) }
                }
                ModelField(endpoint, { endpoint = it; changed() }, R.string.ai_endpoint, enabled = editable, error = endpoint.isNotEmpty() && normalized == null, keyboard = KeyboardType.Uri)
                if (normalized != null) Text(stringResource(R.string.ai_models_request_url, "$normalized/chat/completions"), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(key, { key = it; clearKey = false; changed() }, label = { Text(stringResource(if (original?.hasKey == true) R.string.ai_key_replace else R.string.ai_key)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, enabled = editable, textStyle = LocalTextStyle.current.copy(fontSize = 13.sp), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(if (clearKey) R.string.ai_models_key_cleared else if (original?.hasKey == true && key.isEmpty()) R.string.ai_models_key_retained else R.string.ai_models_key_private), fontSize = 11.sp) })
                if (original?.hasKey == true) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearKey, { clearKey = it; key = ""; changed() }, enabled = editable); Text(stringResource(R.string.ai_clear_key), fontSize = 12.sp) }
            }
            ModelSection(stringResource(R.string.ai_models_model_section)) {
                ModelField(model, { model = it; changed() }, R.string.ai_model, enabled = editable)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_models_manual_hint), Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { picker = true; ai.models.discover(draft()) }, enabled = editable && normalized != null && parametersValid) { WorkbenchIcon(Glyph.Refresh, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.ai_models_fetch)) }
                }
            }
            ModelSection(stringResource(R.string.ai_models_parameters)) {
                Text(stringResource(R.string.ai_models_parameters_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ParameterInput(R.string.ai_models_max_tokens, maxEnabled, { maxEnabled = it; changed() }, maxTokens, { maxTokens = it; changed() }, editable, maxTokens.toIntOrNull() in 1..32768, KeyboardType.Number)
                ParameterInput(R.string.ai_models_temperature, tempEnabled, { tempEnabled = it; changed() }, temperature, { temperature = it; changed() }, editable, temperature.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..2.0 } == true)
                ParameterInput(R.string.ai_models_top_p, topEnabled, { topEnabled = it; changed() }, topP, { topP = it; changed() }, editable, topP.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 && it <= 1.0 } == true)
            }
            ModelSection(stringResource(R.string.ai_models_test_title)) {
                Text(stringResource(R.string.ai_models_test_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.probeMessage.isNotEmpty() && !picker) Text(state.probeMessage, fontSize = 12.sp, color = if (state.probe == AiProbe.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                if (state.probing) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.ai_models_testing), fontSize = 12.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = ai.models::cancelProbe) { Text(stringResource(R.string.cancel)) } }
                else OutlinedButton(onClick = { ai.models.test(draft()) }, enabled = editable && normalized != null && model.isNotBlank() && parametersValid, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ai_models_test_button)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
    if (picker) {
        var query by remember { mutableStateOf("") }
        AiFullScreen(stringResource(R.string.ai_models_picker), { picker = false; ai.models.cancelProbe() }) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelField(query, { query = it }, R.string.ai_models_search)
            if (state.probe == AiProbe.Models) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.probeMessage.isNotEmpty()) Text(state.probeMessage, fontSize = 12.sp, color = if (state.probe == AiProbe.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { ai.models.discover(draft()) }, enabled = !state.probing) { Text(stringResource(R.string.ai_models_refresh)) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.available.filter { it.contains(query, ignoreCase = true) }, key = { it }) { id ->
                    val description = stringResource(R.string.ai_models_choose_named, id)
                    Row(Modifier.fillMaxWidth().semantics { contentDescription = description }.clickable { model = id; changed(); picker = false }.heightIn(min = 48.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(id, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        if (id == model) WorkbenchIcon(Glyph.Check, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TextButton(onClick = { picker = false; ai.models.cancelProbe() }) { Text(stringResource(R.string.ai_models_keep_manual)) }
        }
        }
    }
    if (templates) WorkbenchSheet(stringResource(R.string.ai_models_templates), { templates = false }) {
        Text(stringResource(R.string.ai_models_template_hint), style = MaterialTheme.typography.bodySmall)
        listOf(R.string.ai_models_custom to "", R.string.ai_models_deepseek to "https://api.deepseek.com/v1", R.string.ai_models_siliconflow to "https://api.siliconflow.cn/v1", R.string.ai_models_openrouter to "https://openrouter.ai/api/v1").forEach { (label, address) ->
            TextButton(onClick = { if (address.isNotEmpty()) { endpoint = address; changed() }; templates = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label), Modifier.weight(1f)); WorkbenchIcon(Glyph.Next) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.ai_models_discard_title)) }, text = { Text(stringResource(R.string.ai_models_discard_hint)) },
        confirmButton = { TextButton(onClick = { key = ""; close() }) { Text(stringResource(R.string.ai_models_discard)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.ai_models_keep_editing)) } })
}

@Composable
private fun ModelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}
@Composable
private fun ModelField(value: String, update: (String) -> Unit, label: Int, enabled: Boolean = true, error: Boolean = false, keyboard: KeyboardType = KeyboardType.Text) {
    val description = stringResource(label)
    OutlinedTextField(value, update, Modifier.fillMaxWidth().semantics { contentDescription = description }, label = { Text(stringResource(label), fontSize = 12.sp) }, enabled = enabled, singleLine = true, isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
}
@Composable
private fun ParameterInput(label: Int, enabled: Boolean, toggle: (Boolean) -> Unit, value: String, update: (String) -> Unit, editable: Boolean, valid: Boolean, keyboard: KeyboardType = KeyboardType.Decimal) {
    val description = stringResource(R.string.ai_models_enable_parameter, stringResource(label))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), fontSize = 13.sp)
        Switch(enabled, toggle, enabled = editable, modifier = Modifier.semantics { contentDescription = description })
    }
    if (enabled) ModelField(value, update, label, editable, !valid, keyboard)
}

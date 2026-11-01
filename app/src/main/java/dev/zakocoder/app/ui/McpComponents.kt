package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.mcp.McpBearerCredentials
import dev.zakocoder.app.mcp.McpController
import dev.zakocoder.app.mcp.McpServer
import dev.zakocoder.app.mcp.McpTransport
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

@Composable
internal fun McpManagementPanel(controller: McpController, projectId: String?, editable: Boolean, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var importing by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Pair<McpServer, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<McpServer?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var failedServer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(projectId) { failedServer = null; viewing = null; confirmation = null }
    val canEdit = editable && !state.loading && !state.busy && connecting == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mcp_configured, state.servers.size), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { information = true }, modifier = Modifier.size(48.dp)) {
                WorkbenchIcon(Glyph.Info, description = stringResource(R.string.mcp_design_about))
            }
            TextButton(onClick = { importing = true }, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                WorkbenchIcon(Glyph.Import, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mcp_import))
            }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (state.error.isNotEmpty()) item {
                McpDesignCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        McpFormError(state.error)
                    }
                    TextButton(onClick = { controller.load() }, enabled = !state.busy && editable, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.mcp_reload))
                    }
                }
            }
            if (!state.loading && state.error.isEmpty() && state.servers.isEmpty()) item {
                McpDesignCard {
                    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(Glyph.Environment, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.mcp_design_empty_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.mcp_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.servers, key = { it.id }) { server ->
                val tested = state.probeProjectId == projectId && state.probeServerId == server.id && state.probeResult.isNotEmpty()
                McpDesignCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(shortTransportLabel(server.transport), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val switchDescription = stringResource(R.string.mcp_switch, server.name)
                        Switch(modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = switchDescription },
                            checked = server.enabled, onCheckedChange = { enabled ->
                                if (enabled) confirmation = server to false else controller.setEnabled(server.id, false)
                            }, enabled = canEdit)
                    }
                    Text(if (server.transport == McpTransport.Stdio) server.command else remoteTarget(server.url),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        !server.enabled -> R.string.mcp_status_disabled
                        connecting == server.id -> R.string.mcp_connecting
                        failedServer == server.id -> R.string.mcp_connection_failed
                        tested -> R.string.mcp_tested
                        else -> R.string.mcp_enabled
                    }
                    Text(stringResource(status), style = MaterialTheme.typography.bodySmall,
                        color = if (failedServer == server.id) MaterialTheme.colorScheme.error else if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { confirmation = server to true }, enabled = canEdit && server.enabled && projectId != null,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(stringResource(R.string.mcp_connect))
                        }
                        if (tested) TextButton(onClick = { viewing = server.id }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)) { Text(stringResource(R.string.mcp_design_tools)) }
                        IconButton(onClick = { editing = server }, enabled = canEdit, modifier = Modifier.size(48.dp)) {
                            WorkbenchIcon(Glyph.Settings, description = stringResource(R.string.mcp_edit), modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { deleting = server }, enabled = canEdit, modifier = Modifier.size(48.dp)) {
                            WorkbenchIcon(Glyph.Trash, description = stringResource(R.string.mcp_delete), modifier = Modifier.size(20.dp))
                        }
                    }
                    if (projectId == null && server.enabled) Text(stringResource(R.string.mcp_project_required),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        WorkbenchPrimaryButton(onClick = { adding = true }, enabled = canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)) {
            WorkbenchIcon(Glyph.Add, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.mcp_design_add_server))
        }
    }
    if (adding || editing != null) McpServerEditor(controller, editing, editable) { adding = false; editing = null }
    if (importing) McpImportDialog(controller, editable) { importing = false }
    if (information) WorkbenchSheet(stringResource(R.string.mcp_design_about), { information = false }) {
        Text(stringResource(R.string.mcp_scope), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.mcp_approval_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirmation?.let { (server, probe) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(if (probe) R.string.mcp_connect_confirm else R.string.mcp_enable_confirm)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (server.transport == McpTransport.Stdio) R.string.mcp_stdio_risk else R.string.mcp_remote_risk))
                if (server.transport == McpTransport.Stdio) {
                    Text(stringResource(R.string.mcp_guest_cwd, projectId?.let { "/workspace/$it" } ?: stringResource(R.string.mcp_no_project)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(server.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.mcp_argument_count, server.args.size), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.mcp_environment_keys, server.env.keys.joinToString(", ").ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
                } else Text(remoteOrigin(server.url), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.mcp_approval_boundary), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                if (probe) projectId?.let { project -> scope.launch {
                    connecting = server.id
                    failedServer = null
                    try {
                        controller.probe(project, server.id).join()
                        if (controller.state.value.error.isNotEmpty()) failedServer = server.id
                    } finally { connecting = null }
                } } else controller.setEnabled(server.id, true)
            }, enabled = canEdit && (!probe || projectId != null)) { Text(stringResource(R.string.mcp_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.mcp_cancel)) } })
    }
    deleting?.let { server -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.mcp_delete_confirm, server.name)) },
        text = { Text(stringResource(R.string.mcp_delete_effect)) },
        confirmButton = { TextButton(onClick = { controller.remove(server.id); deleting = null }, enabled = canEdit) { Text(stringResource(R.string.mcp_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.mcp_cancel)) } }) }
    viewing?.let { id -> AiFullScreen(stringResource(R.string.mcp_tools), { viewing = null }) {
        val result = state.probeResult.takeIf { state.probeProjectId == projectId && state.probeServerId == id }.orEmpty()
        val tools = remember(result) { runCatching {
            val array = JSONObject(result).getJSONArray("tools")
            (0 until array.length()).map { index ->
                val tool = array.getJSONObject(index)
                tool.optString("name") to tool.optString("description")
            }
        }.getOrNull() }
        var raw by remember { mutableStateOf(false) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.mcp_untrusted_catalog), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (result.isEmpty()) item { Text(stringResource(R.string.mcp_result_expired)) }
            else {
                if (runCatching { JSONObject(result).optBoolean("truncated") }.getOrDefault(false)) item {
                    Text(stringResource(R.string.mcp_catalog_limited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (tools?.isEmpty() == true) item { Text(stringResource(R.string.mcp_no_tools)) }
                tools?.let { values -> items(values.size) { index ->
                    val (name, description) = values[index]
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall)
                            if (description.isNotEmpty()) Text(description.take(4000), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
                item { TextButton(onClick = { raw = !raw }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (raw) R.string.mcp_hide_protocol else R.string.mcp_show_protocol)) } }
                if (raw) item { SelectionContainer { Text(result.take(32000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                if (raw && result.length > 32000) item { Text(stringResource(R.string.mcp_protocol_preview_limited), style = MaterialTheme.typography.bodySmall) }
            }
        }
    } }

}

@Composable
private fun transportLabel(transport: McpTransport): String = stringResource(when (transport) {
    McpTransport.Stdio -> R.string.mcp_transport_stdio
    McpTransport.StreamableHttp -> R.string.mcp_transport_http
    McpTransport.Sse -> R.string.mcp_transport_sse
})

private fun remoteOrigin(value: String): String = runCatching {
    val uri = URI(value)
    "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
}.getOrDefault("")

@Composable
internal fun McpServerEditor(controller: McpController, initial: McpServer?, editable: Boolean, close: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var transport by remember { mutableStateOf(initial?.transport ?: McpTransport.Stdio) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var args by remember { mutableStateOf(JSONArray(initial?.args ?: emptyList<String>()).toString()) }
    var environment by remember { mutableStateOf(JSONObject(initial?.env ?: emptyMap<String, String>()).toString()) }
    val credentialFields = remember { McpBearerCredentials.split(initial?.headers.orEmpty()) }
    var bearerToken by remember { mutableStateOf(credentialFields.token) }
    var revealToken by remember { mutableStateOf(false) }
    var headers by remember { mutableStateOf(JSONObject(credentialFields.otherHeaders).toString()) }
    var reveal by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var credentialsCleared by remember { mutableStateOf(false) }
    val enabled = editable && !state.busy && !state.loading
    var advanced by remember { mutableStateOf(false) }
    var choosingTransport by remember { mutableStateOf(false) }
    AiFullScreen(stringResource(if (initial == null) R.string.mcp_design_add_server else R.string.mcp_edit), close) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, { if (it.length <= 100) { name = it; invalid = false } }, label = { Text(stringResource(R.string.mcp_name)) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                Surface(shape = RoundedCornerShape(12.dp), border = cardBorder(), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { choosingTransport = true }.heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.mcp_design_protocol), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(shortTransportLabel(transport), style = MaterialTheme.typography.bodyMedium)
                        }
                        WorkbenchIcon(Glyph.ChevronDown, Modifier.size(18.dp))
                    }
                }
                if (transport == McpTransport.Stdio) {
                    OutlinedTextField(command, { if (it.length <= 2048) { command = it; invalid = false } }, label = { Text(stringResource(R.string.mcp_command)) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(url, { value ->
                        if (value.length <= 2048) {
                            if (remoteOrigin(value) != remoteOrigin(url) && (headers != "{}" || bearerToken.isNotEmpty())) {
                                headers = "{}"; bearerToken = ""; revealToken = false; credentialsCleared = true
                            }
                            url = value
                            invalid = false
                        }
                    }, label = { Text(stringResource(R.string.mcp_url)) }, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    if (credentialsCleared) Text(stringResource(R.string.mcp_credentials_cleared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(bearerToken, { if (it.length <= 8192) { bearerToken = it; invalid = false } },
                        label = { Text(stringResource(R.string.mcp_bearer_token)) },
                        supportingText = { Text(stringResource(R.string.mcp_bearer_hint)) },
                        trailingIcon = { TextButton(onClick = { revealToken = !revealToken }, enabled = enabled) {
                            Text(stringResource(if (revealToken) R.string.mcp_bearer_hide else R.string.mcp_bearer_show), style = MaterialTheme.typography.labelSmall)
                        } },
                        singleLine = true, enabled = enabled,
                        visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth())
                }
                Surface(shape = RoundedCornerShape(16.dp), border = cardBorder(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { advanced = !advanced; reveal = false }.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.mcp_design_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            WorkbenchIcon(Glyph.ChevronDown, Modifier.size(18.dp).rotate(if (advanced) 180f else 0f))
                        }
                        if (advanced) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.mcp_design_advanced_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (transport == McpTransport.Stdio) {
                                McpSecretField(args, { if (it.length <= 65536) { args = it; invalid = false } }, R.string.mcp_args, enabled, reveal)
                                McpSecretField(environment, { if (it.length <= 131072) { environment = it; invalid = false } }, R.string.mcp_env, enabled, reveal)
                            } else McpSecretField(headers, { if (it.length <= 131072) { headers = it; invalid = false } }, R.string.mcp_other_headers, enabled, reveal)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(reveal, { reveal = it })
                                Text(stringResource(R.string.mcp_reveal), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.mcp_save_disabled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                McpFormError(if (invalid) stringResource(R.string.mcp_invalid_fields) else state.error)
                WorkbenchPrimaryButton(onClick = {
                    val server = runCatching {
                        fun objectValues(text: String): Map<String, String> {
                            val json = JSONObject(text)
                            return json.keys().asSequence().associateWith { key -> json.get(key) as? String ?: error("string") }
                        }
                        val values = if (transport == McpTransport.Stdio) JSONArray(args) else JSONArray()
                        McpServer(id = id, name = name.trim(), transport = transport,
                            url = if (transport == McpTransport.Stdio) "" else url.trim(), command = if (transport == McpTransport.Stdio) command.trim() else "",
                            args = (0 until values.length()).map { values.get(it) as? String ?: error("string") },
                            env = if (transport == McpTransport.Stdio) objectValues(environment) else emptyMap(),
                            headers = if (transport == McpTransport.Stdio) emptyMap() else McpBearerCredentials.headers(bearerToken, objectValues(headers)), enabled = false)
                    }.getOrNull()
                    invalid = server == null
                    if (server != null) scope.launch { controller.save(server).join(); if (controller.state.value.error.isEmpty()) close() }
                }, enabled = enabled && name.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.mcp_save)) }
            }
        }
    }
    if (choosingTransport) WorkbenchSheet(stringResource(R.string.mcp_design_protocol), { choosingTransport = false }) {
        McpTransport.entries.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { transport = item; invalid = false; choosingTransport = false }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = transport == item, onClick = { transport = item; invalid = false; choosingTransport = false }, enabled = enabled)
                Text(transportLabel(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun McpImportDialog(controller: McpController, editable: Boolean, close: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var singleName by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    AiFullScreen(stringResource(R.string.mcp_import), close) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.mcp_import_formats_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(singleName, { if (it.length <= 100) singleName = it },
                    label = { Text(stringResource(R.string.mcp_import_single_name)) },
                    supportingText = { Text(stringResource(R.string.mcp_import_single_name_hint)) },
                    singleLine = true, enabled = editable && !state.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(source, { if (it.length <= 524288) source = it }, label = { Text(stringResource(R.string.mcp_json)) }, minLines = 6, maxLines = 14, enabled = editable && !state.busy, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(reveal, { reveal = it }); Text(stringResource(R.string.mcp_reveal), style = MaterialTheme.typography.bodySmall) }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                McpFormError(state.error)
                WorkbenchPrimaryButton(onClick = { scope.launch { controller.importJson(source, singleName).join(); if (controller.state.value.error.isEmpty()) close() } }, enabled = editable && !state.busy && !state.loading && source.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.mcp_import)) }
            }
        }
    }
}

@Composable
private fun McpSecretField(value: String, change: (String) -> Unit, label: Int, enabled: Boolean, reveal: Boolean) {
    val fieldLabel = stringResource(label)
    OutlinedTextField(value, change, label = { Text(fieldLabel) }, enabled = enabled, minLines = 2, maxLines = 5,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel })
}

@Composable
private fun McpFormError(message: String) {
    if (message.isEmpty()) return
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mcp_error_title), Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { copyScope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("MCP", message))) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    WorkbenchIcon(Glyph.Copy, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_error_copy), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            SelectionContainer {
                Text(message, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()).semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun remoteTarget(value: String): String = runCatching {
    val uri = URI(value)
    remoteOrigin(value) + uri.rawPath.orEmpty()
}.getOrDefault("")

@Composable
private fun McpDesignCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), border = cardBorder()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun shortTransportLabel(transport: McpTransport): String = stringResource(when (transport) {
    McpTransport.Stdio -> R.string.mcp_design_stdio
    McpTransport.StreamableHttp -> R.string.mcp_design_http
    McpTransport.Sse -> R.string.mcp_design_sse
})

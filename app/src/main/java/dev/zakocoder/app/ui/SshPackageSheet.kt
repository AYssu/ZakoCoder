package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

internal enum class SshAuthUi { Password, PrivateKey }

/** Secrets are write-only drafts. Stored credentials must never be projected into this model. */
internal data class SshPackageUiModel(
    val configured: Boolean = false,
    val configRevision: Int = 0,
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val workingDirectory: String = "/",
    val auth: SshAuthUi = SshAuthUi.Password,
    val hasSecret: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val tmuxEnabled: Boolean = false,
    val tmuxAvailable: Boolean? = null,
    val status: String? = null,
    val pendingFingerprint: String? = null,
    val pendingAlgorithm: String? = null,
    val trustedFingerprint: String? = null,
    val fingerprintChanged: Boolean = false,
    val error: String? = null,
)

internal data class SshConnectionDraft(
    val host: String,
    val port: Int,
    val user: String,
    val workingDirectory: String,
    val auth: SshAuthUi,
    val secret: String,
    val passphrase: String = "",
    val tmuxEnabled: Boolean = false,
) {
    override fun toString(): String = "SshConnectionDraft(redacted)"
}

@Composable
internal fun SshPackageSheet(model: SshPackageUiModel, onSave: (SshConnectionDraft) -> Unit,
                             onTest: () -> Unit, onDisconnect: () -> Unit,
                             onTrustHost: () -> Unit, onForgetHost: () -> Unit, onDismiss: () -> Unit) {
    WorkbenchSheet(stringResource(R.string.capability_ssh_title), onDismiss,
        subtitle = stringResource(R.string.ssh_package_subtitle)) {
        SshPackagePanel(model, onSave, onTest, onDisconnect, onTrustHost, onForgetHost)
    }
}

@Composable
internal fun SshPackagePanel(model: SshPackageUiModel, onSave: (SshConnectionDraft) -> Unit,
                             onTest: () -> Unit, onDisconnect: () -> Unit,
                             onTrustHost: () -> Unit, onForgetHost: () -> Unit) {
    // Deliberately not rememberSaveable: passwords/private keys do not enter saved state bundles.
    var host by remember(model.host) { mutableStateOf(model.host) }
    var port by remember(model.port) { mutableStateOf(model.port.toString()) }
    var user by remember(model.user) { mutableStateOf(model.user) }
    var workingDirectory by remember(model.workingDirectory) { mutableStateOf(model.workingDirectory) }
    var auth by remember(model.auth) { mutableStateOf(model.auth) }
    var secret by remember(model.host, model.port, model.user, model.auth, model.hasSecret, model.configRevision) { mutableStateOf("") }
    var passphrase by remember(model.host, model.port, model.user, model.auth, model.hasSecret, model.configRevision) { mutableStateOf("") }
    var tmux by remember(model.tmuxEnabled) { mutableStateOf(model.tmuxEnabled) }
    var visible by remember(model.configRevision) { mutableStateOf(false) }
    var invalid by remember(model.configRevision) { mutableStateOf(false) }
    val dirty = host.trim() != model.host || port.toIntOrNull() != model.port || user.trim() != model.user ||
        workingDirectory.trim() != model.workingDirectory || auth != model.auth || secret.isNotEmpty() || passphrase.isNotEmpty() || tmux != model.tmuxEnabled
    val credentialLabel = stringResource(if (auth == SshAuthUi.Password) R.string.ssh_package_password else R.string.ssh_package_private_key)
    val revealLabel = stringResource(if (visible) R.string.ssh_package_hide_secret else R.string.ssh_package_show_secret)
    SshSection(stringResource(R.string.ssh_package_connection)) {
        OutlinedTextField(value = host, onValueChange = { host = it; invalid = false },
            label = { Text(stringResource(R.string.ssh_package_host)) }, singleLine = true, enabled = !model.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = port, onValueChange = { port = it; invalid = false },
                label = { Text(stringResource(R.string.ssh_package_port)) }, singleLine = true, enabled = !model.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(112.dp))
            OutlinedTextField(value = user, onValueChange = { user = it; invalid = false },
                label = { Text(stringResource(R.string.ssh_package_user)) }, singleLine = true, enabled = !model.busy,
                modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = workingDirectory, onValueChange = { workingDirectory = it; invalid = false },
            label = { Text(stringResource(R.string.ssh_package_working_directory)) }, singleLine = true, enabled = !model.busy,
            supportingText = { Text(stringResource(R.string.ssh_package_working_directory_hint)) },
            modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.ssh_package_auth), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshAuthUi.entries.forEach { option ->
                Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = auth == option, enabled = !model.busy,
                    role = Role.RadioButton, onClick = { auth = option; secret = ""; passphrase = ""; visible = false }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = auth == option, onClick = null)
                    Text(stringResource(if (option == SshAuthUi.Password) R.string.ssh_package_password_auth else R.string.ssh_package_key_auth),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text(credentialLabel) },
            enabled = !model.busy, singleLine = auth == SshAuthUi.Password,
            minLines = if (auth == SshAuthUi.PrivateKey) 3 else 1, maxLines = if (auth == SshAuthUi.PrivateKey) 5 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { visible = !visible }, enabled = !model.busy,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(revealLabel) }
        if (auth == SshAuthUi.PrivateKey) OutlinedTextField(value = passphrase, onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.ssh_package_passphrase)) }, singleLine = true, enabled = !model.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text(stringResource(if (model.hasSecret && auth == model.auth) R.string.ssh_package_secret_saved else R.string.ssh_package_secret_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SshSection(stringResource(R.string.ssh_package_tmux)) {
        val label = stringResource(R.string.ssh_package_tmux)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ssh_package_tmux_hint), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = tmux, onCheckedChange = { tmux = it }, enabled = !model.busy,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label })
        }
        model.tmuxAvailable?.let { available -> Text(stringResource(if (available) R.string.ssh_package_tmux_ready else R.string.ssh_package_tmux_missing),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (invalid) CapabilityNotice(stringResource(R.string.ssh_package_invalid), error = true)
    model.error?.let { CapabilityNotice(it, error = true) }
    Button(onClick = {
        val selectedPort = port.toIntOrNull()
        if (host.isBlank() || user.isBlank() || !workingDirectory.startsWith("/") || selectedPort == null || selectedPort !in 1..65535) invalid = true
        else {
            onSave(SshConnectionDraft(host.trim(), selectedPort, user.trim(), workingDirectory.trim(), auth, secret, passphrase, tmux))
        }
    }, enabled = !model.busy && (!model.configured || dirty), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.ssh_package_save))
    }
    Text(stringResource(R.string.ssh_package_save_first), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SshConnectionStatus(model, canTest = model.configured && !dirty, onTest, onDisconnect, onTrustHost, onForgetHost)
}

@Composable
internal fun SshConnectionStatus(model: SshPackageUiModel, canTest: Boolean,
                                 onTest: () -> Unit, onDisconnect: () -> Unit,
                                 onTrustHost: () -> Unit, onForgetHost: () -> Unit) {
    SshSection(stringResource(if (model.connected) R.string.ssh_package_connected else R.string.ssh_package_disconnected)) {
        model.status?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedButton(onClick = onTest, enabled = canTest && !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (model.busy) R.string.ssh_package_testing else R.string.ssh_package_test))
        }
        if (model.connected || model.busy) TextButton(onClick = onDisconnect, enabled = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ssh_package_disconnect)) }
    }
    val fingerprint = model.pendingFingerprint ?: model.trustedFingerprint
    if (fingerprint != null) SshSection(stringResource(R.string.ssh_package_fingerprint)) {
        if (model.fingerprintChanged) CapabilityNotice(stringResource(R.string.ssh_package_fingerprint_changed), error = true)
        model.pendingAlgorithm?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        SelectionContainer { Text(fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        if (model.pendingFingerprint != null) {
            Text(stringResource(R.string.ssh_package_fingerprint_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onTrustHost, enabled = !model.busy && canTest && !model.fingerprintChanged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ssh_package_trust)) }
        }
        if (model.trustedFingerprint != null) TextButton(onClick = onForgetHost, enabled = !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ssh_package_forget_host)) }
    }
}

@Composable
private fun SshSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

package dev.zakocoder.app.ui

import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    var credentialTarget by remember(model.host, model.port, model.user, model.auth, model.hasSecret, model.configRevision) { mutableStateOf<Triple<String, String, String>?>(null) }
    var credentialsCleared by remember(model.host, model.port, model.user, model.auth, model.hasSecret, model.configRevision) { mutableStateOf(false) }
    var tmux by remember(model.tmuxEnabled) { mutableStateOf(model.tmuxEnabled) }
    var visible by remember(model.configRevision) { mutableStateOf(false) }
    var invalid by remember(model.configRevision) { mutableStateOf(false) }
    var credentialsInvalid by remember(model.configRevision) { mutableStateOf(false) }
    // A credential belongs to the exact host/port/user identity and is discarded when that identity changes.
    fun identity(hostValue: String = host, portValue: String = port, userValue: String = user) =
        Triple(hostValue.trim(), portValue.toIntOrNull()?.toString() ?: portValue.trim(), userValue.trim())
    fun clearCredentials(notify: Boolean) {
        if (notify && (secret.isNotEmpty() || passphrase.isNotEmpty())) credentialsCleared = true
        secret = ""
        passphrase = ""
        visible = false
        credentialTarget = null
    }
    val modelIdentity = Triple(model.host.trim(), model.port.toString(), model.user.trim())
    val targetIdentity = identity()
    val identityChanged = targetIdentity != modelIdentity
    val requiresCredential = identityChanged && model.hasSecret && auth == model.auth && secret.isEmpty() && passphrase.isEmpty()
    val credentialsNeedInput = requiresCredential || credentialsCleared && identityChanged
    val dirty = host.trim() != model.host || port.toIntOrNull() != model.port || user.trim() != model.user ||
        workingDirectory.trim() != model.workingDirectory || auth != model.auth || secret.isNotEmpty() || passphrase.isNotEmpty() || tmux != model.tmuxEnabled
    val credentialLabel = stringResource(if (auth == SshAuthUi.Password) R.string.ssh_package_password else R.string.ssh_package_private_key)
    val revealLabel = stringResource(if (visible) R.string.ssh_package_hide_secret else R.string.ssh_package_show_secret)
    SshSection(stringResource(R.string.ssh_package_connection)) {
        WorkbenchTextField(value = host, onValueChange = {
            host = it; invalid = false; credentialsInvalid = false
            if (identity(hostValue = it) != modelIdentity) clearCredentials(notify = true)
        },
            label = stringResource(R.string.ssh_package_host), singleLine = true, enabled = !model.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkbenchTextField(value = port, onValueChange = {
                port = it; invalid = false; credentialsInvalid = false
                if (identity(portValue = it) != modelIdentity) clearCredentials(notify = true)
            },
                label = stringResource(R.string.ssh_package_port), singleLine = true, enabled = !model.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(112.dp))
            WorkbenchTextField(value = user, onValueChange = {
                user = it; invalid = false; credentialsInvalid = false
                if (identity(userValue = it) != modelIdentity) clearCredentials(notify = true)
            },
                label = stringResource(R.string.ssh_package_user), singleLine = true, enabled = !model.busy,
                modifier = Modifier.weight(1f))
        }
        WorkbenchTextField(value = workingDirectory, onValueChange = { workingDirectory = it; invalid = false },
            label = stringResource(R.string.ssh_package_working_directory), singleLine = true, enabled = !model.busy,
            supportingText = { Text(stringResource(R.string.ssh_package_working_directory_hint)) },
            modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.ssh_package_auth), style = MaterialTheme.typography.labelLarge)
        Column(Modifier.fillMaxWidth()) {
            SshAuthUi.entries.forEach { option ->
                RadioButtonPreference(
                    title = stringResource(if (option == SshAuthUi.Password) R.string.ssh_package_password_auth else R.string.ssh_package_key_auth),
                    selected = auth == option, enabled = !model.busy,
                    onClick = {
                        auth = option
                        clearCredentials(notify = false)
                        credentialsCleared = false
                        credentialsInvalid = false
                    },
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }
        }
        WorkbenchTextField(value = secret, onValueChange = { value ->
            secret = value
            credentialTarget = identity().takeIf { value.isNotEmpty() || passphrase.isNotEmpty() }
            if (value.isNotEmpty()) credentialsCleared = false
            credentialsInvalid = false
        }, label = credentialLabel,
            enabled = !model.busy, singleLine = auth == SshAuthUi.Password,
            minLines = if (auth == SshAuthUi.PrivateKey) 3 else 1, maxLines = if (auth == SshAuthUi.PrivateKey) 5 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        WorkbenchTextButton(onClick = { visible = !visible }, enabled = !model.busy,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(revealLabel) }
        if (auth == SshAuthUi.PrivateKey) WorkbenchTextField(value = passphrase, onValueChange = { value ->
            passphrase = value
            credentialTarget = identity().takeIf { secret.isNotEmpty() || value.isNotEmpty() }
            if (value.isNotEmpty()) credentialsCleared = false
            credentialsInvalid = false
        },
            label = stringResource(R.string.ssh_package_passphrase), singleLine = true, enabled = !model.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text(stringResource(if (model.hasSecret && auth == model.auth && !identityChanged) R.string.ssh_package_secret_saved else R.string.ssh_package_secret_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SshSection(stringResource(R.string.ssh_package_tmux)) {
        SwitchPreference(checked = tmux, onCheckedChange = { tmux = it }, enabled = !model.busy,
            title = stringResource(R.string.ssh_package_tmux), summary = stringResource(R.string.ssh_package_tmux_hint),
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
        model.tmuxAvailable?.let { available -> Text(stringResource(if (available) R.string.ssh_package_tmux_ready else R.string.ssh_package_tmux_missing),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (invalid) CapabilityNotice(stringResource(R.string.ssh_package_invalid), error = true)
    if (credentialsNeedInput || credentialsInvalid) CapabilityNotice(stringResource(R.string.ssh_error_credential), error = true)
    model.error?.let { CapabilityNotice(it, error = true) }
    WorkbenchPrimaryButton(onClick = {
        val selectedPort = port.toIntOrNull()
        val target = identity()
        val targetChanged = target != modelIdentity
        val enteredForOtherTarget = (secret.isNotEmpty() || passphrase.isNotEmpty()) && credentialTarget != target
        if (enteredForOtherTarget) {
            clearCredentials(notify = true)
            credentialsInvalid = true
        } else if (targetChanged && model.hasSecret && auth == model.auth && secret.isEmpty() && passphrase.isEmpty()) {
            credentialsInvalid = true
        } else if (host.isBlank() || user.isBlank() || !workingDirectory.startsWith("/") || selectedPort == null || selectedPort !in 1..65535) {
            invalid = true
        } else {
            credentialsInvalid = false
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
        if (model.busy) WorkbenchLinearProgressIndicator(Modifier.fillMaxWidth())
        WorkbenchSecondaryButton(onClick = onTest, enabled = canTest && !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (model.busy) R.string.ssh_package_testing else R.string.ssh_package_test))
        }
        if (model.connected || model.busy) WorkbenchTextButton(onClick = onDisconnect, enabled = true,
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
            WorkbenchSecondaryButton(onClick = onTrustHost, enabled = !model.busy && canTest && !model.fingerprintChanged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ssh_package_trust)) }
        }
        if (model.trustedFingerprint != null) WorkbenchTextButton(onClick = onForgetHost, enabled = !model.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.ssh_package_forget_host)) }
    }
}

@Composable
private fun SshSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    WorkbenchCard(cornerRadius = 12.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

package dev.zakocoder.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.capabilities.CapabilityPackage
import dev.zakocoder.app.ssh.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** UI delegates IO, permissions and network to the native controllers. */
@Composable
internal fun CapabilityPackageBindings(workbench: Workbench, projectId: String?, projectName: String, onDismiss: () -> Unit) {
    val packages by workbench.capabilityPackages.state.collectAsStateWithLifecycle()
    val ssh by workbench.ssh.state.collectAsStateWithLifecycle()
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedText = stringResource(dev.zakocoder.app.R.string.ssh_package_saved)
    val successText = stringResource(dev.zakocoder.app.R.string.ssh_package_test_success)
    val disconnectedText = stringResource(dev.zakocoder.app.R.string.ssh_package_disconnected)
    var details by remember(projectId) { mutableStateOf<CapabilityPackage?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var status by remember(projectId) { mutableStateOf<String?>(null) }
    var revision by remember(projectId) { mutableIntStateOf(0) }
    var connected by remember(projectId) { mutableStateOf(false) }
    var tmuxAvailable by remember(projectId) { mutableStateOf<Boolean?>(null) }
    fun launchOperation(action: suspend () -> Unit) {
        if (projectId == null || ssh.busy || ai.active) return
        scope.launch {
            error = null; connected = false; status = null; tmuxAvailable = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: SshException) { connected = false; error = sshError(context, failure.code) }
            catch (_: Exception) { connected = false; error = sshError(context, "operation_failed") }
        }
    }
    val screenOn = workbench.capabilityPackages.enabled(projectId, CapabilityPackage.Screen)
    val sshOn = workbench.capabilityPackages.enabled(projectId, CapabilityPackage.Ssh)
    LaunchedEffect(screenOn, sshOn) {
        if (details == CapabilityPackage.Screen && !screenOn || details == CapabilityPackage.Ssh && !sshOn) details = null
    }
    when (details) {
        CapabilityPackage.Screen -> ScreenTestSheet(workbench.screenTests, projectId, projectName) { details = null }
        CapabilityPackage.Ssh -> {
            val profile = ssh.profiles.singleOrNull { it.id == projectId }
            val pending = ssh.pendingHostKey?.takeIf { it.profileId == projectId }
            SshPackageSheet(SshPackageUiModel(configured = profile != null, host = profile?.host.orEmpty(),
                port = profile?.port ?: 22, user = profile?.username.orEmpty(), workingDirectory = profile?.workingDirectory ?: "/",
                auth = if (profile?.auth == SshAuth.PrivateKey) SshAuthUi.PrivateKey else SshAuthUi.Password,
                hasSecret = profile?.hasCredential == true, connected = connected,
                busy = ssh.busy || ai.active, tmuxEnabled = profile?.tmuxEnabled == true, tmuxAvailable = tmuxAvailable,
                status = status, pendingFingerprint = pending?.fingerprint, pendingAlgorithm = pending?.algorithm,
                trustedFingerprint = profile?.hostFingerprint,
                fingerprintChanged = pending != null && profile?.hostFingerprint != null && pending.fingerprint != profile.hostFingerprint,
                error = error ?: sshError(context, ssh.error), configRevision = revision),
                onSave = { draft -> launchOperation {
                    val id = requireNotNull(projectId)
                    workbench.ssh.saveProfile(SshProfile(id, draft.host.trim(), draft.port, draft.user.trim(),
                        if (draft.auth == SshAuthUi.PrivateKey) SshAuth.PrivateKey else SshAuth.Password,
                        draft.workingDirectory.trim(), draft.tmuxEnabled),
                        password = draft.secret.takeIf { draft.auth == SshAuthUi.Password && it.isNotEmpty() },
                        privateKey = draft.secret.takeIf { draft.auth == SshAuthUi.PrivateKey && it.isNotEmpty() },
                        passphrase = draft.passphrase.takeIf { draft.auth == SshAuthUi.PrivateKey && it.isNotEmpty() })
                    revision++; connected = false; status = savedText
                } },
                onTest = { launchOperation {
                    val id = requireNotNull(projectId)
                    val key = workbench.ssh.probeHost(id)
                    if (key.fingerprint == profile?.hostFingerprint) {
                        val result = workbench.ssh.execute(id, JSONObject().put("action", "status"))
                        tmuxAvailable = result.optBoolean("tmux_available")
                        connected = true; status = successText
                    }
                } },
                onDisconnect = { if (ai.active && ai.projectId == projectId) workbench.ai.stop(); workbench.ssh.stop(); connected = false; tmuxAvailable = null; status = disconnectedText },
                onTrustHost = { launchOperation {
                    val id = requireNotNull(projectId)
                    workbench.ssh.trustHost(id, requireNotNull(pending).fingerprint)
                    val result = workbench.ssh.execute(id, JSONObject().put("action", "status"))
                    tmuxAvailable = result.optBoolean("tmux_available"); connected = true
                    status = successText
                } },
                onForgetHost = { launchOperation { workbench.ssh.forgetHost(requireNotNull(projectId)); connected = false } },
                onDismiss = { details = null })
        }
        null -> CapabilityPackagesSheet(CapabilityPackagesUiModel(projectName, projectId != null, screenOn, sshOn,
            !packages.loaded || packages.busy || projectId == null, packages.error),
            onScreenEnabled = { enabled -> projectId?.let { workbench.capabilityPackages.setEnabled(it, CapabilityPackage.Screen, enabled) } },
            onSshEnabled = { enabled -> projectId?.let { workbench.capabilityPackages.setEnabled(it, CapabilityPackage.Ssh, enabled) } },
            onScreenDetails = { if (screenOn) details = CapabilityPackage.Screen },
            onSshDetails = { if (sshOn) details = CapabilityPackage.Ssh }, onDismiss = onDismiss)
    }
}

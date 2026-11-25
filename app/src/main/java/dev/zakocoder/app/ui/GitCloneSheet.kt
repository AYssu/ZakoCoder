package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.git.GitCredential
import dev.zakocoder.app.git.publicCloneUrl
import java.net.URI

/** The clone draft is local UI memory only; credentials are never persisted in saved instance state. */
@Composable
internal fun GitCloneSheet(enabled: Boolean, close: () -> Unit,
    onClone: (name: String, url: String, username: String, token: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var privateRepository by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var tokenOrigin by remember { mutableStateOf<String?>(null) }
    var credentialsCleared by remember { mutableStateOf(false) }
    fun updateUrl(value: String) {
        val nextOrigin = cloneOrigin(value)
        if (token.isNotEmpty() && nextOrigin != null && tokenOrigin != nextOrigin) {
            username = ""
            token = ""
            tokenOrigin = null
            credentialsCleared = true
        }
        url = value
    }
    val validName = name.trim().length in 1..80 && name.none { it.isISOControl() || it in "/\\" }
    val validUrl = runCatching { publicCloneUrl(url) }.isSuccess
    val validCredential = !privateRepository || runCatching { GitCredential(username, token).validate() }.isSuccess
    WorkbenchSheet(stringResource(R.string.git_clone), {
        token = ""; username = ""; close()
    }, stringResource(R.string.git_clone_credentials_help)) {
        val finish = LocalWorkbenchSheetAction.current
        WorkbenchTextField(name, { if (it.length <= 80) name = it }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.project_name), singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), shape = RoundedCornerShape(12.dp))
        WorkbenchTextField(url, { if (it.length <= 1000) updateUrl(it) }, modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.git_url), singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), shape = RoundedCornerShape(12.dp),
            supportingText = { if (url.isNotBlank() && !validUrl) Text(stringResource(R.string.git_clone_url_error)) }, isError = url.isNotBlank() && !validUrl)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.git_clone_private), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            WorkbenchSwitch(privateRepository, {
                privateRepository = it
                if (!it) {
                    username = ""; token = ""; tokenOrigin = null; credentialsCleared = false
                }
            }, enabled = enabled)
        }
        if (privateRepository) {
            Text(stringResource(R.string.git_clone_private_help), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            WorkbenchTextField(username, { if (it.length <= 128) username = it }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                label = stringResource(R.string.git_credentials_username), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), shape = RoundedCornerShape(12.dp))
            WorkbenchTextField(token, {
                if (it.length <= 8192) {
                    token = it
                    tokenOrigin = if (it.isNotEmpty()) cloneOrigin(url) else null
                    if (it.isNotEmpty()) credentialsCleared = false
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                label = stringResource(R.string.git_credentials_token), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), shape = RoundedCornerShape(12.dp))
            if (credentialsCleared) Text(stringResource(R.string.git_credentials_help),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        WorkbenchPrimaryButton(onClick = {
            val user = if (privateRepository) username else ""
            val credential = if (privateRepository) token else ""
            val projectName = name.trim()
            val cloneUrl = publicCloneUrl(url)
            val currentOrigin = cloneOrigin(cloneUrl)
            if (privateRepository && (user.isNotEmpty() || credential.isNotEmpty()) && tokenOrigin != currentOrigin) {
                username = ""
                token = ""
                tokenOrigin = null
                credentialsCleared = true
            } else finish { onClone(projectName, cloneUrl, user, credential) }
        }, enabled = enabled && validName && validUrl && validCredential,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            Text(stringResource(R.string.git_clone))
        }
    }
}

/** Credentials may follow path changes on one HTTPS service, never scheme, host, or port changes. */
private fun cloneOrigin(value: String): String? = runCatching {
    val uri = URI(publicCloneUrl(value))
    val port = if (uri.port != -1) uri.port else 443
    "${uri.scheme.lowercase()}:${uri.host.lowercase()}:$port"
}.getOrNull()

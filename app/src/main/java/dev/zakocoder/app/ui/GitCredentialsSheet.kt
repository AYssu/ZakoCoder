package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.git.GitCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Secret exists only in this sheet's memory; never saved in Bundle or filled from storage. */
@Composable
internal fun GitCredentialsSheet(projectId: String, remoteUrl: String, store: GitCredentialStore,
    close: () -> Unit, onChanged: () -> Unit = {},
) {
    var username by remember(projectId, remoteUrl) { mutableStateOf("") }
    var token by remember(projectId, remoteUrl) { mutableStateOf("") }
    var saved by remember(projectId, remoteUrl) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(projectId, remoteUrl) {
        try {
            val summary = withContext(Dispatchers.IO) { store.summary(projectId, remoteUrl) }
            username = summary?.username.orEmpty(); saved = summary != null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    WorkbenchSheet(stringResource(R.string.git_credentials_title), { if (!busy) close() },
        subtitle = stringResource(R.string.git_credentials_subtitle)) {
        Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.git_credentials_repository), style = MaterialTheme.typography.labelMedium)
                    Text(remoteUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (saved) R.string.git_credentials_saved else R.string.git_credentials_not_saved),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.git_credentials_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(username, { if (it.length <= 128) username = it }, enabled = !busy,
                label = { Text(stringResource(R.string.git_credentials_username)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(token, { if (it.length <= 8192) token = it }, enabled = !busy,
                label = { Text(stringResource(if (saved) R.string.git_credentials_replace_token else R.string.git_credentials_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (failed) Text(stringResource(R.string.git_credentials_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = {
                busy = true; failed = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { store.save(projectId, remoteUrl, username, token) }
                        token = ""; onChanged(); close()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }, enabled = !busy && username.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.git_credentials_save)) }
            if (saved) TextButton(onClick = { confirmingRemoval = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.git_credentials_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmingRemoval) AlertDialog(onDismissRequest = { confirmingRemoval = false },
        title = { Text(stringResource(R.string.git_credentials_remove)) },
        text = { Text(stringResource(R.string.git_credentials_remove_help)) },
        confirmButton = { TextButton(onClick = {
            confirmingRemoval = false; busy = true; failed = false
            scope.launch {
                try { withContext(Dispatchers.IO) { store.remove(projectId, remoteUrl) }; saved = false; token = ""; onChanged() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.git_credentials_remove)) } },
        dismissButton = { TextButton(onClick = { confirmingRemoval = false }) { Text(stringResource(R.string.cancel)) } })
}

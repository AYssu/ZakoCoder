package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiEntry

@Composable
internal fun AiMessageRetryDialog(entry: AiEntry, edit: Boolean, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.originalInput) }
    val editing = edit || entry.originalInput.length > 16000
    val valid = text.length <= 16000 && (text.isNotBlank() || entry.images.isNotEmpty())
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.ai_message_edit else R.string.ai_message_retry_title)) },
        text = {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ai_message_retry_notice))
                if (editing) OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.ai_message_edit_label)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, isError = !valid,
                    supportingText = { Text(stringResource(R.string.ai_message_edit_limit)) })
            }
        }, confirmButton = { TextButton(onClick = { onConfirm(if (editing) text else null) }, enabled = valid) {
            Text(stringResource(R.string.ai_message_retry))
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

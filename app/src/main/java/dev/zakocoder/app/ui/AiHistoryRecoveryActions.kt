package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiHistoryProblem

/** Only explicit confirmation can remove a precisely identified unreadable record. */
@Composable
internal fun AiHistoryRecoveryActions(problem: AiHistoryProblem?, enabled: Boolean, onRetry: () -> Unit,
    onRemove: (AiHistoryProblem) -> Unit) {
    var confirming by remember(problem) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ai_history_read_retry), fontSize = 12.sp)
        }
        if (problem?.conversationId != null) TextButton(onClick = { confirming = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ai_history_remove_unreadable), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirming && problem?.conversationId != null) WorkbenchAlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.ai_history_remove_unreadable_title), fontSize = 16.sp) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_history_remove_unreadable_detail), fontSize = 14.sp, lineHeight = 20.sp)
            Text(stringResource(R.string.ai_history_remove_unreadable_record, problem.conversationId),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { confirming = false; onRemove(problem) }, enabled = enabled) {
            Text(stringResource(R.string.ai_history_remove_unreadable_confirm), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } })
}

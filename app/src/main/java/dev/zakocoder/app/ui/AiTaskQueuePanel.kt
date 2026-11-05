package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

/** Keep pending intent visible; no dialogs or full-screen navigation while the agent is working. */
@Composable
internal fun AiTaskQueuePanel(messages: List<AiQueuedMessage>, running: Boolean, enabled: Boolean,
    onCancel: (String) -> Unit, onStart: (String) -> Unit) {
    val pending = messages.filter { it.status == AiQueuedStatus.Pending }
    if (pending.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        pending.forEach { message ->
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (message.kind == AiQueuedKind.Steer) R.string.ai_queue_steer_pending else R.string.ai_queue_followup_pending),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(message.text, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!running) TextButton(onClick = { onStart(message.id) }, enabled = enabled) {
                    Text(stringResource(R.string.ai_queue_continue), fontSize = 12.sp)
                }
                IconButton(onClick = { onCancel(message.id) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    WorkbenchIcon(Glyph.Close, Modifier.size(16.dp), description = stringResource(R.string.ai_queue_cancel))
                }
            }
        }
    }
}

@Composable
internal fun AiRunningInputActions(enabled: Boolean, onSteer: () -> Unit, onFollowUp: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onFollowUp, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ai_queue_followup), fontSize = 12.sp)
        }
        TextButton(onClick = onSteer, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.ai_queue_steer), fontSize = 12.sp)
        }
    }
}

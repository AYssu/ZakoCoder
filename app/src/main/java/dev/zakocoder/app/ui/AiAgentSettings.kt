package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

@Composable
internal fun AiAgentSettings(state: AiState, controller: AiController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val label = stringResource(R.string.ai_auto_delegate)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            WorkbenchSwitch(state.automaticDelegation, controller::setAutomaticDelegation,
                enabled = state.projectId != null && !state.active && !state.restoring && !state.historyError && !state.permissionsLoading,
                modifier = Modifier.semantics { contentDescription = label })
        }
        Text(stringResource(R.string.ai_auto_delegate_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ai_task_limits_hint), style = MaterialTheme.typography.bodySmall)
        AiTaskUsageContent(state)
    }
}

@Composable
internal fun AiTaskUsageContent(state: AiState) {
    val usage = state.taskUsage
    if (usage.requests == 0 && usage.tools == 0) return
    Text(stringResource(R.string.ai_task_usage, usage.requests, usage.tools, usage.tokens, usage.elapsedMs), style = MaterialTheme.typography.bodySmall)
    if (usage.estimatedRequests > 0) Text(stringResource(R.string.ai_task_usage_estimated, usage.estimatedRequests),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

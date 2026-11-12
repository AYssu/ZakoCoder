package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState

@Composable
internal fun AiExtensionsPage(workbench: Workbench, workbenchState: WorkbenchState, initialTab: Int = 0, standalone: Boolean = false, close: () -> Unit) {
    val back = rememberWorkbenchBackMotion(onBack = close)
    val ai by workbench.ai.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val globalSkills by workbench.ai.globalSkills.collectAsStateWithLifecycle()
    val editable = !ai.active && !ai.restoring && !workbenchState.busy
    LaunchedEffect(Unit) { workbench.ai.refreshGlobalSkills() }
    Column(Modifier.fillMaxSize().then(back.modifier).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = back.close, modifier = Modifier.size(48.dp)) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.ai_extensions_back)) }
            Text(stringResource(if (standalone) { if (initialTab == 0) R.string.ai_skills else R.string.mcp_title } else R.string.ai_extensions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (!standalone) SecondaryTabRow(selectedTabIndex = tab) {
            listOf(stringResource(R.string.ai_skills), stringResource(R.string.mcp_title)).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        Text(stringResource(R.string.extensions_global_scope), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!editable) Text(stringResource(R.string.extensions_readonly), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        if (tab == 0) AiSkillsPanel(globalSkills.copy(active = globalSkills.active || !editable), workbench.ai,
            Modifier.weight(1f))
        else McpManagementPanel(workbench.mcp, ai.projectId, editable, Modifier.weight(1f))
    }
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R

@Composable
internal fun CopyHelp(title: String, body: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(title, body) { mutableStateOf(false) }
    val action = stringResource(if (expanded) R.string.copy_help_collapse else R.string.copy_help_expand)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = action }
            .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            WorkbenchIcon(if (expanded) Glyph.Up else Glyph.ChevronDown, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Text(body, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

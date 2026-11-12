package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import top.yukonga.miuix.kmp.basic.Switch

/** Keep the compact Miuix track inside an accessible independent touch target. */
@Composable
internal fun WorkbenchSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.minimumInteractiveComponentSize())
    }
}

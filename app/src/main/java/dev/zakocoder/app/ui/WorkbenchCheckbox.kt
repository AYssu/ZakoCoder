package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Checkbox

@Composable
internal fun WorkbenchCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center) {
        Checkbox(state = ToggleableState(checked),
            onClick = if (onCheckedChange == null) null else ({ onCheckedChange(!checked) }),
            enabled = enabled, modifier = Modifier.minimumInteractiveComponentSize())
    }
}

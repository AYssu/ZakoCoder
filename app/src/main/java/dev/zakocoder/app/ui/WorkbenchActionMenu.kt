package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup

/** Keep the popup composed through dismissal so Miuix can finish its exit transition. */
@Composable
internal fun WorkbenchActionMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    items: List<DropdownItem>, onCancelled: () -> Unit = {}) {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val entries = items.map { item ->
        val icon = item.icon
        item.copy(enabled = item.enabled && pending == null,
            // Own the icon cell here: callers must not accidentally drop Miuix's
            // spacing by ignoring the Modifier supplied to their icon lambda.
            icon = if (icon == null) null else { _ ->
                Box(Modifier.width(32.dp).height(20.dp), contentAlignment = Alignment.CenterStart) {
                    icon(Modifier.size(20.dp))
                }
            },
            onClick = { pending = item.onClick; onDismissRequest() })
    }
    WindowDropdownPopup(entries = listOf(DropdownEntry(entries)), show = expanded,
        onDismiss = onDismissRequest, onDismissFinished = {
            val action = pending
            pending = null
            if (action != null) action() else onCancelled()
        }, maxHeight = 420.dp,
        dropdownColors = DropdownDefaults.dropdownColors(), collapseOnSelection = true)
}

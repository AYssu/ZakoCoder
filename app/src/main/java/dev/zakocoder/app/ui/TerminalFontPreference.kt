package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/** A floating Miuix selector for the retained 9–28sp setting; dismissal does not save. */
@Composable
internal fun TerminalFontPreference(value: Int, enabled: Boolean, onSave: (Int) -> Unit) {
    val sizes = remember { (9..28).toList() }
    WindowDropdownPreference(
        title = stringResource(R.string.terminal_font),
        summary = stringResource(R.string.terminal_output_font),
        items = sizes.map { stringResource(R.string.font_size_compact, it) },
        selectedIndex = sizes.indexOf(value),
        onSelectedIndexChange = { index ->
            val selected = sizes[index]
            if (enabled && selected != value) onSave(selected)
        },
        enabled = enabled, maxHeight = 320.dp,
        startAction = {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_settings_text_size), contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f))
                }
            }
        },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    )
}

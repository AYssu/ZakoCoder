package dev.zakocoder.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.zakocoder.app.R
import top.yukonga.miuix.kmp.basic.DropdownItem

/** Retain the native Miuix menu until its exit transition and pending action finish. */
@Composable
internal fun MessageCopyPopup(expanded: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null, onRetry: (() -> Unit)? = null,
    onRequests: (() -> Unit)? = null, onQuote: (() -> Unit)? = null) {
    WorkbenchActionMenu(expanded = expanded && LocalWorkbenchPageInteractive.current,
        onDismissRequest = onDismiss, items = buildList {
            add(messageAction(R.string.ai_copy_user, Glyph.Copy, onCopy))
            onQuote?.let { add(messageAction(R.string.ai_quote_message, Glyph.Ai, it)) }
            onEdit?.let { add(messageAction(R.string.ai_message_edit, Glyph.File, it)) }
            onRetry?.let { add(messageAction(R.string.ai_message_retry, Glyph.Refresh, it)) }
            onRequests?.let { add(messageAction(R.string.ai_message_requests, Glyph.Info, it)) }
        })
}

@Composable
private fun messageAction(label: Int, glyph: Glyph, onClick: () -> Unit) =
    DropdownItem(text = stringResource(label), icon = { WorkbenchIcon(glyph, it) }, onClick = onClick)

/** Clipboard writes belong to the page after the focusable action window has gone away. */
@Suppress("DEPRECATION")
@Composable
internal fun rememberMessageCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val window = LocalWindowInfo.current
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending, window.isWindowFocused) {
        val text = pending
        if (text != null && window.isWindowFocused) {
            clipboard.setText(AnnotatedString(text))
            pending = null
        }
    }
    return { pending = it }
}

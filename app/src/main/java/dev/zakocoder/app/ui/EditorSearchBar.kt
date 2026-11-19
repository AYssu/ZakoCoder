package dev.zakocoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R

/** Find takes one row; replacement controls are present only when explicitly requested. */
@Composable
internal fun EditorSearchBar(
    query: String, onQueryChange: (String) -> Unit,
    replacement: String, onReplacementChange: (String) -> Unit,
    replacing: Boolean, matches: Int, enabled: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit,
    onReplace: () -> Unit, onReplaceAll: () -> Unit, onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canAct = enabled && query.isNotEmpty() && matches > 0
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("editor-search-bar")) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            val countDescription = stringResource(R.string.editor_matches, matches)
            SearchInput(query, onQueryChange, stringResource(R.string.editor_find),
                Modifier.weight(1f).focusRequester(focus), enabled, { if (canAct) onNext() }) {
                Text(matches.toString(), Modifier.padding(start = 4.dp).semantics { contentDescription = countDescription },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SearchAction(Glyph.Up, R.string.editor_previous_match, canAct, onPrevious)
            SearchAction(Glyph.Down, R.string.editor_next_match, canAct, onNext)
            SearchAction(Glyph.Close, R.string.editor_close_search, true) {
                focusManager.clearFocus(); keyboard?.hide(); onClose()
            }
        }
        if (replacing) Row(Modifier.fillMaxWidth().height(48.dp).testTag("editor-replace-row"),
            verticalAlignment = Alignment.CenterVertically) {
            SearchInput(replacement, onReplacementChange, stringResource(R.string.editor_replacement),
                Modifier.weight(1f), enabled, { if (canAct) onReplace() })
            SearchAction(Glyph.Swap, R.string.editor_replace, canAct, onReplace)
            TextButton(onClick = onReplaceAll, enabled = canAct, modifier = Modifier.height(44.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.editor_replace_all), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SearchInput(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier, enabled: Boolean, onSubmit: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onValueChange, modifier.height(44.dp).semantics { contentDescription = label },
        enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(vertical = 4.dp)
                .background(colors.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant)
                    inner()
                }
                trailing?.invoke()
            }
        })
}

@Composable
private fun SearchAction(glyph: Glyph, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp), enabled = enabled) {
        WorkbenchIcon(glyph, Modifier.size(16.dp), description = stringResource(label))
    }
}

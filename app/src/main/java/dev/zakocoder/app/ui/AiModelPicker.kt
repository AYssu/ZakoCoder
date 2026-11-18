package dev.zakocoder.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

/** Anchored to the composer. Opening only reads nonsecret, locally saved profile summaries. */
@Composable
internal fun AiModelPicker(ai: AiController, close: () -> Unit, onManage: () -> Unit) {
    val state by ai.models.state.collectAsStateWithLifecycle()
    val task by ai.state.collectAsStateWithLifecycle()
    if (!LocalWorkbenchPageInteractive.current) return
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val maxHeight = with(density) { (window.height * .4f).toDp() }
    val width = with(density) { window.width.toDp() - 32.dp }.coerceAtMost(240.dp)
    val gap = with(density) { 4.dp.roundToPx() }
    val position = remember(gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val edge = gap * 2
            val x = (anchorBounds.right - popupContentSize.width + edge).coerceIn(gap, maxOf(gap, windowSize.width - popupContentSize.width - gap))
            val above = anchorBounds.top - popupContentSize.height - gap
            val y = (if (above >= gap) above else anchorBounds.bottom + gap).coerceIn(gap, maxOf(gap, windowSize.height - popupContentSize.height - gap))
            return IntOffset(x, y)
        }
    } }
    var closing by remember { mutableStateOf(false) }
    var navigate by remember { mutableStateOf<(() -> Unit)?>(null) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        progress.animateTo(if (closing) 0f else 1f, tween(160))
        if (closing) { close(); navigate?.invoke() }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true)) {
        Box(Modifier.pointerInput(closing) {
            if (closing) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.graphicsLayer {
            alpha = progress.value; scaleX = .97f + .03f * progress.value; scaleY = scaleX
            translationY = (1 - progress.value) * 5.dp.toPx()
        }.padding(8.dp)) {
            Surface(Modifier.width(width).heightIn(max = maxHeight).testTag("ai-model-popover"),
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp) {
                AiModelPickerContent(state, task.active || task.restoring, dismissing = closing,
                    onSelect = ai.models::selectModel, onReasoning = ai.models::setReasoning,
                    onRetry = ai.models::retry, onManage = { navigate = onManage; closing = true })
            }
        }
    }
}

@Composable
internal fun AiModelPickerContent(state: AiModelsState, locked: Boolean,
    onSelect: (String, String) -> Unit, onReasoning: (String, AiThinking, AiReasoningEffort?) -> Unit,
    onRetry: () -> Unit, onManage: () -> Unit, dismissing: Boolean = false) {
    // Exit keeps the current layout and colors; only interaction stops, without a lock notice or disabled-color flash.
    val editable = !locked && !state.loading && !state.saving && !state.probing && state.error.isEmpty()
    val active = state.catalog.active
    val choices = active?.let { (it.modelChoices + it.model).distinct() }.orEmpty()
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_choose_model),
                fontSize = 12.sp, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.loading || state.saving || state.probing) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        if (locked) Text(stringResource(R.string.ai_models_task_locked), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.error.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.error, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { if (!dismissing) onRetry() }, enabled = !state.loading && !state.saving) { Text(stringResource(R.string.editor_retry)) }
        }
        key(active?.id) {
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 132.dp).testTag("ai-model-saved-list")) {
                if (active != null) {
                    items(choices, key = { "model:${active.id}:$it" }) { model ->
                        PickerRow(model, active.model == model, editable) { if (!dismissing) onSelect(active.id, model) }
                    }
                }
                if (choices.isEmpty()) item { Text(stringResource(R.string.ai_models_empty_hint), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        if (active != null) ReasoningStrengthSlider(active, editable) { thinking, effort ->
            if (!dismissing) onReasoning(active.id, thinking, effort)
        }
        TextButton(onClick = { if (!dismissing) onManage() }, enabled = !locked, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ai_manage_models), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
        .semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) WorkbenchIcon(Glyph.Check, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
    }
}

package dev.zakocoder.app.screentest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.Glyph
import dev.zakocoder.app.ui.WorkbenchColors
import dev.zakocoder.app.ui.WorkbenchDarkColors
import dev.zakocoder.app.ui.WorkbenchIcon
import dev.zakocoder.app.ui.WorkbenchTypography
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

internal data class ScreenOverlayPresentation(val packageName: String = "", val conversation: String = "",
    val progress: ScreenOverlayProgress = ScreenOverlayProgress(), val fallbackStatus: String = "")

/** A monitor by default; conversation and controls are a deliberate second level. */
@Composable
internal fun DebugOverlayCard(model: ScreenOverlayPresentation, expanded: Boolean, scroll: ScrollState, followLatest: Boolean, onFollowLatest: (Boolean) -> Unit,
    dark: Boolean, onExpand: () -> Unit, onReturn: () -> Unit, onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit, onActionBounds: (Int, Rect) -> Unit) {
    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
        val colors = MaterialTheme.colorScheme
        val progressColor = when (model.progress.phase) {
            ScreenOverlayPhase.Failed -> if (dark) Color(0xFFFF9B94) else Color(0xFFB42318)
            ScreenOverlayPhase.Cancelled -> colors.onSurfaceVariant
            ScreenOverlayPhase.Succeeded -> if (dark) Color(0xFF7DD3A5) else Color(0xFF176B45)
            else -> colors.primary
        }
        val status = model.progress.title.ifBlank { model.fallbackStatus.ifBlank { stringResource(R.string.debug_overlay_waiting) } }
        val dragCallback by rememberUpdatedState(onDrag)
        val title = stringResource(R.string.screen_overlay_handle)
        val drag = Modifier.pointerInput(Unit) {
            detectDragGestures { change, amount -> change.consume(); dragCallback(amount.x, amount.y) }
        }
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(if (expanded) 16.dp else 12.dp),
            color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 6.dp) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val shortWindow = maxHeight < 400.dp
            if (!expanded) {
                val detailsLabel = stringResource(R.string.debug_overlay_details)
                Row(Modifier.fillMaxSize().clearAndSetSemantics {
                    contentDescription = "$title，$status"
                    role = Role.Button
                    onClick(detailsLabel) { onExpand(); true }
                }.then(drag).clickable(role = Role.Button, onClickLabel = detailsLabel, onClick = onExpand)
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(Glyph.Ai, tint = colors.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1)
                            val phase = model.conversation.lineSequence().firstOrNull().orEmpty()
                                .takeUnless { it.contains(":") || it.contains("：") }.orEmpty().take(12)
                            if (phase.isNotBlank()) Text(phase, fontSize = 10.sp, lineHeight = 14.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(progressColor, CircleShape))
                            Text(status, fontSize = 12.sp, lineHeight = 16.sp, color = progressColor,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    WorkbenchIcon(Glyph.Next, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).then(drag), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            WorkbenchIcon(Glyph.Ai, tint = colors.primary)
                            Text(stringResource(R.string.screen_overlay_handle), style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = onExpand, modifier = Modifier.size(48.dp)) {
                            WorkbenchIcon(Glyph.ChevronDown, description = stringResource(R.string.debug_overlay_minimize))
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (shortWindow) 8.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.debug_overlay_target), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(model.packageName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(10.dp)).padding(if (shortWindow) 8.dp else 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            WorkbenchIcon(if (model.progress.phase == ScreenOverlayPhase.Succeeded) Glyph.Check else Glyph.Info,
                                Modifier.padding(top = 2.dp).size(18.dp), tint = progressColor)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(status, style = MaterialTheme.typography.titleSmall, color = progressColor, maxLines = if (shortWindow) 1 else 2, overflow = TextOverflow.Ellipsis)
                                if (!shortWindow) Text(model.progress.detail.ifBlank { stringResource(R.string.debug_overlay_screen_yield) },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val scope = rememberCoroutineScope()
                    val threshold = with(LocalDensity.current) { 24.dp.roundToPx() }
                    val atEnd by remember(scroll, threshold) { derivedStateOf { scroll.maxValue - scroll.value <= threshold } }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.debug_overlay_recent_chat), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        if (!atEnd) TextButton(onClick = { scope.launch { onFollowLatest(true); scroll.animateScrollTo(scroll.maxValue) } }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(stringResource(R.string.debug_overlay_latest), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OverlayConversation(model.conversation, scroll, followLatest, onFollowLatest, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = colors.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(if (shortWindow) 8.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onReturn, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .onGloballyPositioned { onActionBounds(R.string.screen_overlay_return, it.boundsInRoot()) }) {
                            WorkbenchIcon(Glyph.Ai, Modifier.size(18.dp), tint = colors.onPrimary)
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_overlay_return), maxLines = 1)
                        }
                        OutlinedButton(onClick = onStop, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (dark) Color(0xFFFF9B94) else Color(0xFFB42318)),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.heightIn(min = 48.dp).onGloballyPositioned { onActionBounds(R.string.screen_overlay_stop, it.boundsInRoot()) }) {
                            Box(Modifier.size(10.dp).background(if (dark) Color(0xFFFF9B94) else Color(0xFFB42318), RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.debug_overlay_stop), maxLines = 1)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun OverlayConversation(transcript: String, scroll: ScrollState, followLatest: Boolean, onFollowLatest: (Boolean) -> Unit, modifier: Modifier) {
    val user = stringResource(R.string.screen_overlay_user)
    val assistant = stringResource(R.string.screen_overlay_assistant)
    val messages = remember(transcript, user, assistant) { overlayMessages(transcript, user, assistant) }
    // Follow only when the user was already at the latest content. A restored reading position stays put.
    val updateFollow by rememberUpdatedState(onFollowLatest)
    val threshold = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.maxValue - scroll.value <= threshold) }
            .filter { it.first }.collectLatest { updateFollow(it.second) }
    }
    LaunchedEffect(transcript, scroll) {
        if (followLatest) {
            androidx.compose.runtime.withFrameNanos { }
            scroll.scrollTo(scroll.maxValue)
        }
    }
    Column(modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (messages.isEmpty()) Text(stringResource(R.string.debug_overlay_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        messages.forEach { message ->
            val fromUser = message.first
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (fromUser) user else assistant, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (fromUser) Surface(shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(message.second, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else Text(message.second, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Display-only bounded transcript; role labels never grant authority or trigger actions. */
internal fun overlayMessages(text: String, user: String, assistant: String): List<Pair<Boolean, String>> {
    val messages = mutableListOf<Pair<Boolean, String>>()
    var role: Boolean? = null
    var body = StringBuilder()
    fun flush() { role?.let { if (body.isNotBlank()) messages += it to body.toString().trim() }; body = StringBuilder() }
    text.lineSequence().forEach { line ->
        val nextRole = when {
            line.startsWith("$user:") || line.startsWith("$user：") -> true
            line.startsWith("$assistant:") || line.startsWith("$assistant：") -> false
            else -> null
        }
        if (nextRole != null) { flush(); role = nextRole; body.append(line.substringAfter(if (line.contains(':')) ':' else '：').trimStart()) }
        else if (role != null) { body.append('\n').append(line) }
    }
    flush()
    return messages.takeLast(3)
}

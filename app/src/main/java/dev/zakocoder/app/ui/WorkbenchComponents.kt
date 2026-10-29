package dev.zakocoder.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R

internal val LocalWorkbenchDark = staticCompositionLocalOf { false }
internal val ReadyGreen: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF3FB950) else Color(0xFF10B981)
internal val WaitingAmber: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFFD29922) else Color(0xFFF59E0B)
internal val FailureRed: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFFF85149) else Color(0xFFEF4444)
internal val TerminalBackground: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF05080C) else Color(0xFF0D1117)
internal val TertiaryText: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF9BA7B4) else Color(0xFF5B6B82)
internal val PanelBackground: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF0B1016) else Color.White
internal val BrandFill: Color @Composable get() = if (LocalWorkbenchDark.current) Color(0xFF2F6FED) else Color(0xFF2563EB)
internal val TerminalForeground = Color(0xFFC9D1D9)
internal val TerminalSecondary = Color(0xFF8B949E)

@Composable
internal fun cardBorder() = if (LocalWorkbenchDark.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

@Composable
internal fun cardElevation() = if (LocalWorkbenchDark.current) 0.dp else 1.dp

internal val WorkbenchTypography = Typography(
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).background(color.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

@Composable
internal fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    val textColor = when (color) {
        ReadyGreen -> if (LocalWorkbenchDark.current) Color(0xFF56D364) else Color(0xFF066749)
        WaitingAmber -> if (LocalWorkbenchDark.current) Color(0xFFE3B341) else Color(0xFFB45309)
        FailureRed -> if (LocalWorkbenchDark.current) Color(0xFFFF7B72) else Color(0xFFDC2626)
        else -> color
    }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = color.copy(alpha = .12f)) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

@Composable
internal fun IconTile(glyph: Glyph, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        WorkbenchIcon(glyph, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun PageHeading(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = if (LocalWorkbenchDark.current) Color(0xFFF0F4F8) else MaterialTheme.colorScheme.onBackground)
        action()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkbenchSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, darkBackdrop: Boolean = false, leading: (@Composable () -> Unit)? = null, status: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (LocalWorkbenchDark.current) Color(0xFF161D26) else Color.White,
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !LocalWorkbenchDark.current && !darkBackdrop, isAppearanceLightNavigationBars = !LocalWorkbenchDark.current),
        scrimColor = if (LocalWorkbenchDark.current) Color.Black.copy(alpha = .62f) else Color(0xFF0F172A).copy(alpha = .42f),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 12.dp).size(38.dp, 4.dp).background(TertiaryText.copy(alpha = .45f), CircleShape)) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                leading?.invoke()
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                status?.invoke()
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(Glyph.Close, modifier = Modifier.size(14.dp), description = stringResource(R.string.close))
                    }
                }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
internal fun SelectablePanel(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Compact paint within independently allocated 48dp touch targets. */
@Composable
internal fun CompactAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                           enabled: Boolean = true, glyph: Glyph? = null) {
    val foreground = if (primary) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(11.dp), color = (if (primary) BrandFill else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = if (enabled) 1f else .4f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) { WorkbenchIcon(glyph, Modifier.size(16.dp), tint = foreground); Spacer(Modifier.width(4.dp)) }
                Text(label, color = foreground, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun EmptyArtwork(glyph: Glyph) {
    Box(Modifier.size(84.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(if (LocalWorkbenchDark.current) listOf(Color(0xFF2F6FED), Color(0xFF5C9BFF)) else listOf(Color(0xFF3B7BF5), Color(0xFF7AA7FF))), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        WorkbenchIcon(glyph, Modifier.size(38.dp), tint = Color.White)
    }
}

@Composable
internal fun DashedTerminalAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val border = if (LocalWorkbenchDark.current) Color(0xFF2E3A48) else Color(0xFFB6C4D6)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).drawBehind {
        drawRoundRect(border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }.background(MaterialTheme.colorScheme.primary.copy(alpha = .03f), RoundedCornerShape(18.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(21.dp).background(if (enabled) BrandFill else TertiaryText, CircleShape), contentAlignment = Alignment.Center) {
            WorkbenchIcon(Glyph.Add, Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.primary else TertiaryText, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun SelectionCheck(selected: Boolean) {
    Box(Modifier.size(20.dp).background(if (selected) BrandFill else Color.Transparent, CircleShape)
        .then(if (selected) Modifier else Modifier.drawBehind { drawCircle(Color(0xFFB6C4D6), style = Stroke(1.dp.toPx())) }), contentAlignment = Alignment.Center) {
        if (selected) WorkbenchIcon(Glyph.Check, Modifier.size(12.dp), tint = Color.White)
    }
}

@Composable
internal fun WorkbenchPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = BrandFill, contentColor = Color.White),
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp), content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
}

@Composable
internal fun WorkbenchLoading() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading")
    val opacity = transition.animateFloatForSkeleton()
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.loading_workbench), style = MaterialTheme.typography.bodySmall, color = TertiaryText)
        repeat(3) {
            Surface(Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = opacity)) { }
        }
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.animateFloatForSkeleton(): Float {
    val value = animateFloat(initialValue = .4f, targetValue = .8f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400), androidx.compose.animation.core.RepeatMode.Reverse), label = "opacity")
    return value.value
}

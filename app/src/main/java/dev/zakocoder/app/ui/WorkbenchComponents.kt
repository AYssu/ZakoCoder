package dev.zakocoder.app.ui

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.theme.ControlSize
import dev.zakocoder.app.ui.theme.IconSize
import dev.zakocoder.app.ui.theme.LocalWorkbenchPalette
import dev.zakocoder.app.ui.theme.Radius
import dev.zakocoder.app.ui.theme.RowHeight
import dev.zakocoder.app.ui.theme.Space
import dev.zakocoder.app.ui.theme.TileSize
import dev.zakocoder.app.ui.theme.Touch
import dev.zakocoder.app.ui.theme.Type
import dev.zakocoder.app.ui.theme.statusContainer

/**
 * 全项目事实上的主题开关（16 个文件依赖）。
 *
 * 保留它，但**不再由它直接产出颜色** —— 颜色改由 `LocalWorkbenchPalette` 派生，
 * 而这个布尔值只负责选择哪一套调色板。这样"主题"只有一个事实来源，
 * 同时不会因为改一个 `Color` 的取值方式而动到 16 个文件。
 */
internal val LocalWorkbenchDark = staticCompositionLocalOf { false }

// ——————————————————————————————————————————————————————————————
// 兼容性别名：这些公开名字被几十处业务代码直接引用，一律保留，
// 内部改成读语义 token。新代码请直接用 LocalWorkbenchPalette.current.xxx。
// ——————————————————————————————————————————————————————————————

internal val ReadyGreen: Color @Composable get() = LocalWorkbenchPalette.current.success
internal val WaitingAmber: Color @Composable get() = LocalWorkbenchPalette.current.warning
internal val FailureRed: Color @Composable get() = LocalWorkbenchPalette.current.danger
internal val TerminalBackground: Color @Composable get() = LocalWorkbenchPalette.current.terminalBackground

/**
 * 注意语义变化：旧值在日间是 #5B6B82、夜间是 #9BA7B4 —— 两个都是 textSecondary。
 * 夜间三级文字和二级同色，导致"次要"和"更次要"分不开，这正是 tokens.md §4.2 要修的缺陷。
 * 现在映射到真正的 textTertiary（日 #637287 / 夜 #7A8798，分别在页面底和卡片底达到 4.5:1）。
 */
internal val TertiaryText: Color @Composable get() = LocalWorkbenchPalette.current.textTertiary

/**
 * 旧值：日间 Color.White、夜间 #0B1016。
 *
 * 这两个值本来就不是同一个语义平面（白 = surface，深 = surfaceSunken），
 * 所以无法映射到单一 token。统一归到 surface：日间完全等价，
 * 夜间从"比页面底还暗"变成"比页面底亮一档"，面板这才读成"浮在页面上的内容块"。
 */
internal val PanelBackground: Color @Composable get() = LocalWorkbenchPalette.current.surface

internal val BrandFill: Color @Composable get() = LocalWorkbenchPalette.current.primaryFill

internal val TerminalForeground: Color = dev.zakocoder.app.ui.theme.LightPalette.terminalForeground
internal val TerminalSecondary: Color = dev.zakocoder.app.ui.theme.LightPalette.terminalSecondary

/**
 * 卡片描边：日夜均使用 1dp `borderHairline`，颜色按主题取值。
 *
 * 旧实现在日间返回 null（靠 1dp 阴影分层），夜间才给描边。取消阴影之后这条路走不通了：
 * 日间 background(#F5F7FA) 与 surface(#FFFFFF) 的对比度只有 1.07:1，肉眼几乎无法区分，
 * 卡片用浅色边界和周围留白分组，避免过深的外框。
 */
@Composable
internal fun cardBorder(): BorderStroke =
    BorderStroke(1.dp, LocalWorkbenchPalette.current.borderHairline)

// 普通卡片不加投影，使用浅色细边框和留白区分层级。

/** 与悬浮窗共用同一套字阶，定义集中在 theme/Type.kt。 */
internal val WorkbenchTypography = dev.zakocoder.app.ui.theme.WorkbenchTypography

/**
 * 状态点。
 *
 * 尺寸保留 14/7（外层光环 + 内层实心），因为规范只定义了 Chip 内 6dp 圆点，
 * 没有为独立状态点定档 —— 这里不擅自发明新尺寸，留给第二批按页面语境处理。
 * 唯一改动是把 12% alpha 的容器色换成统一的 [statusContainer]。
 */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).background(statusContainer(color), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

/**
 * 状态 Chip（tokens.md §8.6）：22dp 高、8dp 圆角、主色 12% 底、11/15/500 文字。
 *
 * 文字色不是主色本身，是主色对应的 "xxxText" —— 主色 #10B981 在 12% 底上只有 2.2:1，
 * 换成 #066749 才有 5.1:1。这是 §4.5 里"状态色成组"的原因。
 */
@Composable
internal fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalWorkbenchPalette.current
    val textColor = when (color) {
        p.success -> p.successText
        p.warning -> p.warningText
        p.danger -> p.dangerText
        else -> color
    }
    Surface(modifier, shape = Radius.smShape, color = statusContainer(color)) {
        // 高度用 heightIn(min) 而不是 height()：130% 字体下 11sp 会涨到 14.3sp，
        // 内容需要 14.3 + 行高余量 ≈ 24dp，固定 22dp 会裁字（tokens.md §3.4.3）。
        Box(Modifier.heightIn(min = ControlSize.chip).padding(horizontal = Space.sm), contentAlignment = Alignment.Center) {
            Text(label, style = Type.label, color = textColor)
        }
    }
}

/**
 * 列表行首图标容器（tokens.md §1.1 / §6.2）：默认 32dp + 8dp 圆角 + 20dp 图标。
 *
 * 原 46dp + 13dp 是典型的"越界"：13 不在四档里，46 也不在任何尺寸档里。
 * 13 大概率是"想比 12 圆一点"的直觉值，而 46 是为了让 22dp 图标不显挤。
 * 图标降到 20dp 之后，32dp 容器（图标占 62.5%）比 46dp 容器（47.8%）比例更紧实。
 *
 * [size] 和 [radius] 必须由调用方一起给（`legacyTileDp` / `tileRadiusDp` 算出来的是一对）。
 * 只改尺寸不改圆角，或者只改圆角不改尺寸，都会让"圆角/边长"比例失守 ——
 * 32dp 配 12dp 会读成胶囊，64dp 配 12dp 会显方。空状态用 `size = TileSize.lg, radius = Radius.lg`。
 */
@Composable
internal fun IconTile(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = TileSize.sm,
    radius: Dp = Radius.sm,
) {
    val p = LocalWorkbenchPalette.current
    Box(modifier.size(size).background(p.surfaceAccent, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        WorkbenchIcon(glyph, tint = p.primary, size = if (size >= TileSize.lg) IconSize.lg else IconSize.md)
    }
}

/** 页面标题：20/28/500 textPrimary。每屏只能有 1 个。 */
@Composable
internal fun PageHeading(title: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = Type.title, color = LocalWorkbenchPalette.current.textPrimary)
        action()
    }
}

/**
 * 底部 Sheet（tokens.md §8.11）。
 *
 * 这里的 `24.dp` 是**全应用唯一允许出现的 24dp 圆角**：24 表示"从屏幕底部升起的系统级浮层"，
 * 16 表示"页面内的应用级浮层"。两者必须在视觉上可区分，否则 Sheet 和 Dialog 会读成同一种东西。
 * 出现第二处 24dp 就是错的。
 *
 * 层级只由 scrim 表达，不加阴影（tokens.md §7.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkbenchSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, darkBackdrop: Boolean = false, leading: (@Composable () -> Unit)? = null, status: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalWorkbenchPalette.current
    val dark = LocalWorkbenchDark.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surfaceRaised, tonalElevation = 0.dp,
        properties = ModalBottomSheetProperties(isAppearanceLightStatusBars = !dark && !darkBackdrop, isAppearanceLightNavigationBars = !dark),
        scrimColor = p.scrim,
        // 拖拽条 32 × 4 dp 胶囊，textTertiary @40%，上 8dp 下 12dp 留白（§8.11 的 10dp 不在六档里，归 8）。
        dragHandle = { Box(Modifier.padding(top = Space.sm, bottom = Space.md).size(32.dp, 4.dp).background(p.textTertiary.copy(alpha = .4f), CircleShape)) },
        shape = Radius.sheetShape) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = Space.lg, end = Space.lg, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                leading?.invoke()
                Text(title, Modifier.weight(1f), style = Type.section, color = p.textPrimary)
                status?.invoke()
                IconButton(onClick = onDismiss, modifier = Modifier.size(Touch48)) {
                    Box(Modifier.size(TileSize.sm).background(p.surfaceSunken, Radius.pill), contentAlignment = Alignment.Center) {
                        WorkbenchIcon(Glyph.Close, description = stringResource(R.string.close), size = IconSize.sm)
                    }
                }
            }
            subtitle?.let { Text(it, style = Type.meta, color = p.textSecondary) }
            content()
        }
    }
}

/**
 * 可选面板（单选项 / 可点卡片）。
 *
 * 选中态用 `surfaceAccent` 底 + `borderFocus` 描边 —— 只换颜色，不改线宽，
 * 避免选中时布局跳动 1dp（tokens.md §4.3）。
 */
@Composable
internal fun SelectablePanel(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val p = LocalWorkbenchPalette.current
    Surface(onClick = onClick, shape = Radius.mdShape,
        color = if (selected) p.surfaceAccent else p.surface,
        border = BorderStroke(1.dp, if (selected) p.borderFocus else p.borderHairline)) {
        Row(Modifier.fillMaxWidth().padding(Space.md), horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * 紧凑操作（次按钮）。视觉 40dp 高，外面套 48dp 触区。
 *
 * 触区不缩放、不压缩（tokens.md §3.4 R4）：48dp 是下限，不是比例。
 */
@Composable
internal fun CompactAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                           enabled: Boolean = true, glyph: Glyph? = null) {
    val p = LocalWorkbenchPalette.current
    val foreground = if (primary) p.onPrimary else p.textPrimary
    Box(modifier.sizeIn(minWidth = ControlSize.button, minHeight = ControlSize.button).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Surface(shape = Radius.mdShape, color = (if (primary) p.primaryFill else p.surfaceSunken).copy(alpha = if (enabled) 1f else .4f)) {
            Row(Modifier.heightIn(min = ControlSize.buttonSecondary).padding(horizontal = Space.md, vertical = Space.sm), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (glyph != null) { WorkbenchIcon(glyph, description = null, tint = foreground, size = IconSize.sm); Spacer(Modifier.width(Space.xs)) }
                Text(label, color = foreground, style = Type.bodyStrong)
            }
        }
    }
}

/**
 * 空状态图标容器（tokens.md §8.8）：64dp + 16dp 圆角 + surfaceAccent 平面底 + 24dp primary 图标。
 *
 * 原来是一个 84dp 方块 + 蓝色线性渐变 + 白色图标。三条都要改：
 * ① 渐变是全项目唯一的**装饰性**色彩，与"颜色只表达状态"直接冲突；
 * ② 渐变在日夜两套主题下要维护两套端点，必然漂移；
 * ③ 去掉渐变后 84dp 会更显空，所以必须同时缩到 64dp。
 *
 * 16dp 圆角在这里是**尺寸驱动**的例外（tokens.md §2.1.1）：64dp 配 12dp 只有 18.75%，
 * 会显方；16dp 是 25%，视觉更整。判定依据是"圆角/边长"比例，不是"这是不是卡片"。
 */
@Composable
internal fun EmptyArtwork(glyph: Glyph) {
    val p = LocalWorkbenchPalette.current
    Box(Modifier.size(TileSize.lg).background(p.surfaceAccent, Radius.lgShape), contentAlignment = Alignment.Center) {
        WorkbenchIcon(glyph, description = null, tint = p.primary, size = IconSize.lg)
    }
}

/**
 * 终端"空位"操作行。
 *
 * 名字保留（调用点不动），但视觉从虚线框改成实线框：
 * 虚线在手机上会糊，空位用 `borderHairline` 实线 + `surfaceSunken` 底表达更稳
 * （tokens.md §5）。同时删掉原来那层 3% 品牌蓝底 —— 那是装饰性用色，不是状态。
 */
@Composable
internal fun DashedTerminalAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalWorkbenchPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = RowHeight.standard)
        .background(p.surfaceSunken, Radius.mdShape)
        .border(1.dp, p.borderHairline, Radius.mdShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.size(IconSize.lg).background(if (enabled) p.primaryFill else p.textDisabled, Radius.pill), contentAlignment = Alignment.Center) {
            WorkbenchIcon(Glyph.Add, description = null, tint = p.onPrimary, size = IconSize.sm)
        }
        Spacer(Modifier.width(Space.sm))
        Text(label, style = Type.bodyStrong, color = if (enabled) p.primary else p.textTertiary, textAlign = TextAlign.Center)
    }
}

/**
 * 单选勾。
 *
 * 勾从 12dp 提到 16dp（tokens.md §6.3）：12dp 在 1.8 单位线宽下视觉只有 0.9dp，太细。
 * 未选中描边从 #B6C4D6 换成 `borderControl` —— 后者是"可交互容器未选中边界"的语义 token，
 * 对比度 3.0:1，是能看见的下限。
 */
@Composable
internal fun SelectionCheck(selected: Boolean) {
    val p = LocalWorkbenchPalette.current
    Box(Modifier.size(20.dp).background(if (selected) p.primaryFill else Color.Transparent, CircleShape)
        .then(if (selected) Modifier else Modifier.drawBehind { drawCircle(p.borderControl, style = Stroke(1.dp.toPx())) }), contentAlignment = Alignment.Center) {
        if (selected) WorkbenchIcon(Glyph.Check, description = null, tint = p.onPrimary, size = IconSize.sm)
    }
}

/**
 * 主按钮（tokens.md §8.1）：48dp 最小高度、12dp 圆角、`primaryFill` 底、14/500 `onPrimary` 文字。
 *
 * 高度写成 `heightIn(min = …)` 而不是 `height(…)`：130% 字体下按钮文字需要 50dp，
 * 固定 48dp 会裁字或让文字换行（tokens.md §3.4.3）。
 */
@Composable
internal fun WorkbenchPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = Radius.mdShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = LocalWorkbenchPalette.current.primaryFill, contentColor = LocalWorkbenchPalette.current.onPrimary),
    contentPadding: PaddingValues = PaddingValues(horizontal = Space.xl, vertical = Space.md), content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = ControlSize.button), enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
}

/**
 * 骨架屏。
 *
 * 呼吸动画保留（不属于本批范围），但底色从 `secondaryContainer` 换成 `surfaceSunken` ——
 * 骨架是"还没到的内容"，属于下沉区，不该用容器色。
 */
@Composable
internal fun WorkbenchLoading() {
    val p = LocalWorkbenchPalette.current
    val transition = rememberInfiniteTransition(label = "loading")
    val opacity = transition.animateFloatForSkeleton()
    Column(Modifier.fillMaxWidth().padding(Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(stringResource(R.string.loading_workbench), style = Type.meta, color = p.textTertiary)
        repeat(3) {
            Surface(Modifier.fillMaxWidth().heightIn(min = RowHeight.twoLine), shape = Radius.mdShape, color = p.surfaceSunken.copy(alpha = opacity)) { }
        }
    }
}

@Composable
private fun InfiniteTransition.animateFloatForSkeleton(): Float {
    val value = animateFloat(initialValue = .4f, targetValue = .8f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "opacity")
    return value.value
}

/** 48dp 触区，来自 `Touch.target`；字体放大到 200% 也不缩放。 */
private val Touch48 = Touch.target

package dev.zakocoder.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.zakocoder.app.ui.LocalWorkbenchDark

/**
 * 28 个语义 token（tokens.md §4）。
 *
 * 硬编码色值只允许出现在本文件。`grep -r "Color(0x" ui/ | grep -v "/theme/"` 必须为 0。
 *
 * 四条表面层级不是"选哪个好看"，而是四个物理平面：
 * [background] 页面底 · [surface] 内容块 · [surfaceSunken] 下沉区（代码/输入框/终端外壳）·
 * [surfaceRaised] 上浮区（Sheet/Dialog/Menu）。
 *
 * **日间 [background] 与 [surface] 对比度只有 1.07:1，肉眼几乎无法区分。**
 * 取消阴影后，用 1dp [borderHairline] 加留白区分卡片；日间 #E2E8F0，避免深色外框。
 *
 * **夜间所有表面明度差最大只有 1.21:1**，所以夜间不能指望"换个更亮的表面"来分层，
 * 必须靠描边 + 三档明度同时作用。
 */
@Immutable
data class WorkbenchPalette(
    // —— 表面层级 5 级 ——
    val background: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val surfaceRaised: Color,
    val surfaceAccent: Color,
    // —— 文字层级 4 级 ——
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    // —— 描边 4 级 ——
    val divider: Color,
    val borderHairline: Color,
    val borderControl: Color,
    val borderFocus: Color,
    // —— 品牌与主操作 4 个 ——
    val primary: Color,
    val primaryFill: Color,
    val onPrimary: Color,
    val onPrimaryContainer: Color,
    // —— 状态 3 组 × 2 + 危险填充 ——
    val success: Color,
    val successText: Color,
    val warning: Color,
    val warningText: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerFill: Color,
    // —— 终端 3 个（与主题无关，始终深色，保证 ANSI 16 色可读） ——
    val terminalBackground: Color,
    val terminalForeground: Color,
    val terminalSecondary: Color,
    // —— 遮罩 ——
    val scrim: Color,
)

val LightPalette = WorkbenchPalette(
    background = Color(0xFFF5F7FA), surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF0F3F8), surfaceRaised = Color(0xFFFFFFFF),
    surfaceAccent = Color(0xFFEEF3FF),
    textPrimary = Color(0xFF0F172A), textSecondary = Color(0xFF5B6B82),
    textTertiary = Color(0xFF637287), textDisabled = Color(0xFFAEB8C6),
    divider = Color(0xFFE8EDF4), borderHairline = Color(0xFFE2E8F0),
    borderControl = Color(0xFF8995A8), borderFocus = Color(0xFF2563EB),
    primary = Color(0xFF2563EB), primaryFill = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF), onPrimaryContainer = Color(0xFF1D4ED8),
    success = Color(0xFF10B981), successText = Color(0xFF066749),
    warning = Color(0xFFF59E0B), warningText = Color(0xFFB45309),
    danger = Color(0xFFEF4444), dangerText = Color(0xFFDC2626), dangerFill = Color(0xFFDC2626),
    terminalBackground = Color(0xFF0D1117), terminalForeground = Color(0xFFC9D1D9),
    terminalSecondary = Color(0xFF8B949E),
    scrim = Color(0xFF0F172A).copy(alpha = 0.42f),
)

val DarkPalette = WorkbenchPalette(
    background = Color(0xFF0B0F14), surface = Color(0xFF151B23),
    surfaceSunken = Color(0xFF0B1016), surfaceRaised = Color(0xFF1B232D),
    surfaceAccent = Color(0xFF142137),
    textPrimary = Color(0xFFE6EDF3), textSecondary = Color(0xFF9BA7B4),
    textTertiary = Color(0xFF7A8798), textDisabled = Color(0xFF4A5563),
    divider = Color(0xFF232B36), borderHairline = Color(0xFF28323E),
    borderControl = Color(0xFF4A5563), borderFocus = Color(0xFF4C8DFF),
    primary = Color(0xFF4C8DFF), primaryFill = Color(0xFF2F6FED),
    onPrimary = Color(0xFFFFFFFF), onPrimaryContainer = Color(0xFF8FBAFF),
    success = Color(0xFF3FB950), successText = Color(0xFF56D364),
    warning = Color(0xFFD29922), warningText = Color(0xFFE3B341),
    danger = Color(0xFFF85149), dangerText = Color(0xFFFF7B72), dangerFill = Color(0xFFF85149),
    terminalBackground = Color(0xFF05080C), terminalForeground = Color(0xFFC9D1D9),
    terminalSecondary = Color(0xFF8B949E),
    scrim = Color(0xFF000000).copy(alpha = 0.62f),
)

/**
 * 显式覆盖通道。默认不提供（读出来是 null），此时调色板由 [LocalWorkbenchDark] 派生。
 *
 * 这样做的原因：[LocalWorkbenchDark] 已经被 16 个文件依赖，是全项目事实上的主题开关。
 * 如果调色板必须靠一个新的 `CompositionLocalProvider` 才能生效，那么任何忘记提供它的
 * 入口（`ScreenTestOverlayContent` 就是现成的例子）都会静默退化成日间色。
 * 派生式读取让"主题开关"保持单一事实来源，同时保留覆盖能力。
 */
private val LocalPaletteOverride = staticCompositionLocalOf<WorkbenchPalette?> { null }

/**
 * 当前调色板。用法与规范里的 `LocalWorkbenchPalette.current` 完全一致。
 */
internal object LocalWorkbenchPalette {
    val current: WorkbenchPalette
        @Composable get() = LocalPaletteOverride.current
            ?: if (LocalWorkbenchDark.current) DarkPalette else LightPalette
}

/** 需要强制某个调色板时（预览、截图、局部对比）用它包一层。 */
@Composable
internal fun ProvideWorkbenchPalette(palette: WorkbenchPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPaletteOverride provides palette, content = content)
}

/**
 * 状态容器色统一 helper（tokens.md §4.5）。
 *
 * 容器色**不新增 authored 值**，一律由状态主色 12% 叠在 surface 上算出来。
 * 规范表格里那些 `#E2F7F0` / `#FEF3E2` / `#FDE9E9` 只是设计稿预览，不要硬编码进代码 ——
 * 一旦硬编码，日/夜两套就得各维护一份，必然漂移。
 */
fun statusContainer(color: Color): Color = color.copy(alpha = 0.12f)

// ——————————————————————————————————————————————————————————————
// M3 ColorScheme 映射（tokens.md §9）
//
// 存量代码里有大量 `MaterialTheme.colorScheme.xxx`。把槽位映射到语义 token 之后，
// 这些调用不需要逐个改，而且会自动跟着 token 走。
//
// **所有槽位都必须显式赋值。** 漏掉任何一个，那个槽位就会泄漏 M3 默认值
// （M3 默认主色是紫色 #6750A4），在界面上表现为"某处莫名出现一块紫"。
//
// **一处对规范的偏离，记录在此**：tokens.md §9 把 `surfaceContainerHigh` /
// `surfaceContainerHighest` 映射到 `surfaceRaised`。实际核对存量代码后发现这个映射是错的：
// - `AiMarkdown` 用 `surfaceContainerHigh` 当**行内代码块的底色**，映射到 surfaceRaised 后
//   日间变成 #FFFFFF 压在 #FFFFFF 的正文上，代码块直接消失；
// - `ToolBundleList` 的**分段控件**把 `surfaceContainerHighest` 当轨道底色，
//   映射后轨道与选中项都是白色，控件读不出来。
//
// 这两个槽位在 M3 语义里是"比 surface 更突出的容器"，而 `surfaceRaised` 在日间刻意等于
// `surface`（浮层靠 scrim 区分，不靠明度）。所以 High/Highest 统一归 `surfaceSunken`：
// 行内代码是下沉区、分段控件轨道是下沉区，两处用法都对上了。
// `surfaceRaised` 仍然通过 `LocalWorkbenchPalette.current.surfaceRaised` 直接取用，
// 不经 M3 槽位。
// ——————————————————————————————————————————————————————————————

val LightColorScheme = lightColorScheme(
    primary = LightPalette.primary,
    onPrimary = LightPalette.onPrimary,
    primaryContainer = LightPalette.surfaceAccent,
    onPrimaryContainer = LightPalette.onPrimaryContainer,
    inversePrimary = LightPalette.onPrimaryContainer,

    secondary = LightPalette.textSecondary,
    onSecondary = LightPalette.surface,
    secondaryContainer = LightPalette.surfaceSunken,
    onSecondaryContainer = LightPalette.textPrimary,

    tertiary = LightPalette.primary,
    onTertiary = LightPalette.onPrimary,
    tertiaryContainer = LightPalette.surfaceAccent,
    onTertiaryContainer = LightPalette.onPrimaryContainer,

    background = LightPalette.background,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.surface,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.surfaceSunken,
    onSurfaceVariant = LightPalette.textSecondary,
    surfaceTint = LightPalette.primary,

    inverseSurface = LightPalette.textPrimary,
    inverseOnSurface = LightPalette.surface,

    error = LightPalette.danger,
    onError = LightPalette.onPrimary,
    errorContainer = statusContainer(LightPalette.danger),
    onErrorContainer = LightPalette.dangerText,

    outline = LightPalette.borderControl,
    outlineVariant = LightPalette.divider,

    surfaceContainerLowest = LightPalette.surface,
    surfaceContainerLow = LightPalette.surfaceSunken,
    surfaceContainer = LightPalette.surfaceSunken,
    surfaceContainerHigh = LightPalette.surfaceSunken,
    surfaceContainerHighest = LightPalette.surfaceSunken,

    scrim = LightPalette.scrim,
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.primary,
    onPrimary = DarkPalette.onPrimary,
    primaryContainer = DarkPalette.surfaceAccent,
    onPrimaryContainer = DarkPalette.onPrimaryContainer,
    inversePrimary = DarkPalette.onPrimaryContainer,

    secondary = DarkPalette.textSecondary,
    onSecondary = DarkPalette.surface,
    secondaryContainer = DarkPalette.surfaceSunken,
    onSecondaryContainer = DarkPalette.textPrimary,

    tertiary = DarkPalette.primary,
    onTertiary = DarkPalette.onPrimary,
    tertiaryContainer = DarkPalette.surfaceAccent,
    onTertiaryContainer = DarkPalette.onPrimaryContainer,

    background = DarkPalette.background,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.surface,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.surfaceSunken,
    onSurfaceVariant = DarkPalette.textSecondary,
    surfaceTint = DarkPalette.primary,

    inverseSurface = DarkPalette.textPrimary,
    inverseOnSurface = DarkPalette.surface,

    error = DarkPalette.danger,
    onError = DarkPalette.background,
    errorContainer = statusContainer(DarkPalette.danger),
    onErrorContainer = DarkPalette.dangerText,

    outline = DarkPalette.borderControl,
    outlineVariant = DarkPalette.divider,

    surfaceContainerLowest = DarkPalette.surface,
    surfaceContainerLow = DarkPalette.surfaceSunken,
    surfaceContainer = DarkPalette.surfaceSunken,
    surfaceContainerHigh = DarkPalette.surfaceSunken,
    surfaceContainerHighest = DarkPalette.surfaceSunken,

    scrim = DarkPalette.scrim,
)

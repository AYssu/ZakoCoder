package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.theme.DarkColorScheme
import dev.zakocoder.app.ui.theme.IconSize
import dev.zakocoder.app.ui.theme.LightColorScheme
import dev.zakocoder.app.ui.theme.LocalWorkbenchPalette

/**
 * M3 ColorScheme 现在完全由 `ui/theme/Palette.kt` 的语义 token 派生。
 *
 * 保留这两个名字而不是让调用方直接引用 `LightColorScheme` / `DarkColorScheme`，
 * 是因为 `ZakoCoderApp.kt` 和 `ScreenTestOverlayContent.kt` 已经在用它们；
 * 换名字换不来任何收益，只会多两处 diff。
 */
internal val WorkbenchColors = LightColorScheme
internal val WorkbenchDarkColors = DarkColorScheme

/** Small, locally drawn line icons; no product assets imported from references. */
internal enum class Glyph(private val drawing: String) {
    Menu("M4 6H20 M4 12H20 M4 18H20"),
    Copy("M10 7H19Q21 7 21 9V19Q21 21 19 21H10Q8 21 8 19V9Q8 7 10 7Z M4 17Q3 17 3 16V5Q3 3 5 3H16Q17 3 17 4"),
    History("M6 5H18Q20 5 20 7V19Q20 21 18 21H6Q4 21 4 19V7Q4 5 6 5Z M8 9H16 M8 13H16 M8 17H13 M8 2V5"),
    Projects("M3 9V6Q3 4 5 4H9Q9.8 4 10.4 4.6L12.2 6.4Q12.8 7 13.6 7H19Q21 7 21 9V18Q21 20 19 20H5Q3 20 3 18Z M3 9H21"),
    ProjectAdd("M3 9V6Q3 4 5 4H9Q9.8 4 10.4 4.6L12.2 6.4Q12.8 7 13.6 7H19Q21 7 21 9V18Q21 20 19 20H5Q3 20 3 18Z M12 11V17 M9 14H15"),
    Terminal("M6 4H18Q21 4 21 7V17Q21 20 18 20H6Q3 20 3 17V7Q3 4 6 4Z M7 9L10 12L7 15 M13 15H17"),
    Extensions("M4.2 3H7.8Q9 3 9 4.2V7.8Q9 9 7.8 9H4.2Q3 9 3 7.8V4.2Q3 3 4.2 3Z M16.2 3H19.8Q21 3 21 4.2V7.8Q21 9 19.8 9H16.2Q15 9 15 7.8V4.2Q15 3 16.2 3Z M4.2 15H7.8Q9 15 9 16.2V19.8Q9 21 7.8 21H4.2Q3 21 3 19.8V16.2Q3 15 4.2 15Z M15 18H21 M18 15V21"),
    ChevronDown("M6 9L12 15L18 9"),
    Environment("M7 9Q7 7 9 7H15Q17 7 17 9V15Q17 17 15 17H9Q7 17 7 15Z M9 3V5 M15 3V5 M9 19V21 M15 19V21 M3 9H5 M3 15H5 M19 9H21 M19 15H21"),
    Settings("M8.90 5.30Q9.17 5.16 9.46 5.07L9.92 4.91Q10.21 4.82 10.24 4.52L10.45 2.72Q10.48 2.42 10.78 2.42L13.22 2.42Q13.52 2.42 13.55 2.72L13.76 4.52Q13.79 4.82 14.08 4.91L14.54 5.07Q14.83 5.16 15.10 5.30L15.54 5.52Q15.81 5.66 16.04 5.47L17.47 4.34Q17.70 4.15 17.91 4.36L19.64 6.09Q19.85 6.30 19.66 6.53L18.53 7.96Q18.34 8.19 18.48 8.46L18.70 8.90Q18.84 9.17 18.93 9.46L19.09 9.92Q19.18 10.21 19.48 10.24L21.28 10.45Q21.58 10.48 21.58 10.78L21.58 13.22Q21.58 13.52 21.28 13.55L19.48 13.76Q19.18 13.79 19.09 14.08L18.93 14.54Q18.84 14.83 18.70 15.10L18.48 15.54Q18.34 15.81 18.53 16.04L19.66 17.47Q19.85 17.70 19.64 17.91L17.91 19.64Q17.70 19.85 17.47 19.66L16.04 18.53Q15.81 18.34 15.54 18.48L15.10 18.70Q14.83 18.84 14.54 18.93L14.08 19.09Q13.79 19.18 13.76 19.48L13.55 21.28Q13.52 21.58 13.22 21.58L10.78 21.58Q10.48 21.58 10.45 21.28L10.24 19.48Q10.21 19.18 9.92 19.09L9.46 18.93Q9.17 18.84 8.90 18.70L8.46 18.48Q8.19 18.34 7.96 18.53L6.53 19.66Q6.30 19.85 6.09 19.64L4.36 17.91Q4.15 17.70 4.34 17.47L5.47 16.04Q5.66 15.81 5.52 15.54L5.30 15.10Q5.16 14.83 5.07 14.54L4.91 14.08Q4.82 13.79 4.52 13.76L2.72 13.55Q2.42 13.52 2.42 13.22L2.42 10.78Q2.42 10.48 2.72 10.45L4.52 10.24Q4.82 10.21 4.91 9.92L5.07 9.46Q5.16 9.17 5.30 8.90L5.52 8.46Q5.66 8.19 5.47 7.96L4.34 6.53Q4.15 6.30 4.36 6.09L6.09 4.36Q6.30 4.15 6.53 4.34L7.96 5.47Q8.19 5.66 8.46 5.52Z M15.3 12A3.3 3.3 0 1 1 8.7 12A3.3 3.3 0 1 1 15.3 12"),
    Bell("M5 17H19L17.5 14V9A5.5 5.5 0 0 0 6.5 9V14Z M10 20Q12 22 14 20 M12 3V2"),
    Globe("M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12 M2 12H22 M12 2C6 8 6 16 12 22C18 16 18 8 12 2"),
    Storage("M7 3H17Q19 3 19 5V19Q19 21 17 21H7Q5 21 5 19V5Q5 3 7 3Z M5 9H19 M5 15H19 M9 6H9.1 M9 12H9.1 M9 18H9.1"),
    Info("M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2 M12 11V17 M12 7V7.2"),
    Send("M3 11L21 3L13 21L11 13Z M11 13L21 3"),
    Trash("M4 6H20 M9 6V4Q9 3 10 3H14Q15 3 15 4V6 M6 6L6.9 19.1Q7 21 8.9 21H15.1Q17 21 17.1 19.1L18 6 M10 10V17 M14 10V17"),
    Shield("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M8 12L11 15L16 9"),
    ShieldAsk("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M10 10A2 2 0 1 1 13 11.7Q12 12.2 12 14 M12 17V17.1"),
    ShieldAuto("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M13 7L9 13H13L11 18L16 11H12Z"),
    Unlocked("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M10 11V9A2 2 0 0 1 14 9 M9 11H15V16H9Z M12 13V14"),
    ReadOnly("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M6.5 12Q9 8.5 12 8.5Q15 8.5 17.5 12Q15 15.5 12 15.5Q9 15.5 6.5 12Z M13.5 12A1.5 1.5 0 1 1 10.5 12A1.5 1.5 0 1 1 13.5 12"),
    Ai("M6 5H18Q20 5 20 7V15Q20 17 18 17H10L6.6 20.4Q5 22 5 20V17Q4 17 4 16V7Q4 5 6 5Z M8 9H16 M8 13H13"),
    Add("M12 5V19 M5 12H19"),
    Minus("M5 12H19"),
    Import("M12 3V14 M8 10L12 14L16 10 M4 14V18Q4 20 6 20H18Q20 20 20 18V14"),
    Export("M12 15V4 M8 8L12 4L16 8 M4 14V18Q4 20 6 20H18Q20 20 20 18V14"),
    Undo("M9 5L4 10L9 15 M4 10H14Q20 10 20 16V19"),
    Redo("M15 5L20 10L15 15 M20 10H10Q4 10 4 16V19"),
    Search("M10 3A7 7 0 1 0 10 17A7 7 0 1 0 10 3 M15 15L21 21"),
    Back("M14 5L7 12L14 19"),
    Next("M9 5L16 12L9 19"),
    More("M5 11V13 M12 11V13 M19 11V13"),
    Close("M6 6L18 18 M18 6L6 18"),
    Swap("M3 8H21L17 4 M21 16H3L7 20"),
    Keyboard("M4 5H20Q22 5 22 7V17Q22 19 20 19H4Q2 19 2 17V7Q2 5 4 5Z M6 9H7 M11 9H12 M16 9H17 M6 12H7 M11 12H12 M16 12H17 M7 16H17"),
    File("M7 3H13.2Q14 3 14.6 3.6L18.4 7.4Q19 8 19 8.8V19Q19 21 17 21H7Q5 21 5 19V5Q5 3 7 3Z M14 3.5V6.5Q14 8 15.5 8H18.5 M8 12H16 M8 16H14"),
    Refresh("M20 10A8 8 0 1 0 19 17 M20 4V10H14"),
    Check("M5 12L10 17L20 7"),
    Up("M12 20V4 M6 10L12 4L18 10"),
    Down("M12 4V20 M6 14L12 20L18 14"),
    Left("M19 12H5 M11 6L5 12L11 18"),
    Right("M5 12H19 M13 6L19 12L13 18"),
    ;
    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(drawing).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

/**
 * 默认 [IconSize.md] 20dp（原 22dp，tokens.md §6.3）。
 *
 * 尺寸由 `modifier` 决定：Compose 里 `Modifier.size(17.dp).size(20.dp)` 取外层的 17dp，
 * 所以调用方传进来的尺寸永远优先，这里的 20dp 只是"没指定时"的默认值。
 * 这也是为什么降 2dp 不会影响任何显式指定了尺寸的调用点。
 *
 * 图标尺寸用 dp 固定、不参与字体缩放（tokens.md §3.4 R3）：图标是识别符号不是阅读内容。
 */
@Composable
internal fun WorkbenchIcon(glyph: Glyph, modifier: Modifier = Modifier, description: String? = null,
                           tint: Color = LocalWorkbenchPalette.current.textSecondary,
                           size: Dp = IconSize.md) {
    Icon(glyph.vector, description, modifier.size(size), tint = tint)
}

/** Use the same full-color artwork as the launcher throughout the app. */
@Composable
internal fun WorkbenchBrandMark(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_launcher_art), contentDescription = null, modifier = modifier.size(IconSize.lg), tint = Color.Unspecified)
}

package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal val WorkbenchColors = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF3FF), onPrimaryContainer = Color(0xFF1D4ED8),
    secondary = Color(0xFF5B6B82), secondaryContainer = Color(0xFFF1F5FB), onSecondaryContainer = Color(0xFF5B6B82),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF0F6), onSurfaceVariant = Color(0xFF5B6B82),
    outline = Color(0xFF8995A8), outlineVariant = Color(0xFFE8EDF4),
    surfaceContainerLow = Color(0xFFF8F9FC), surfaceContainer = Color(0xFFF0F3F8),
    surfaceContainerHigh = Color(0xFFF1F5FB), surfaceContainerHighest = Color(0xFFE7EBF2),
)

internal val WorkbenchDarkColors = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color.White,
    primaryContainer = Color(0xFF142137), onPrimaryContainer = Color(0xFF8FBAFF),
    secondary = Color(0xFF9BA7B4), secondaryContainer = Color(0xFF1B232D), onSecondaryContainer = Color(0xFFB7C2CE),
    background = Color(0xFF0B0F14), onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF151B23), onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1E2733), onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF4A5563), outlineVariant = Color(0xFF232B36),
    surfaceContainerLow = Color(0xFF161D26), surfaceContainer = Color(0xFF0B1016),
    surfaceContainerHigh = Color(0xFF1B232D), surfaceContainerHighest = Color(0xFF1E2733),
    error = Color(0xFFFF7B72), onError = Color(0xFF0B0F14),
)

/** Small, locally drawn line icons; no product assets imported from references. */
internal enum class Glyph(private val drawing: String) {
    Copy("M8 7H21V21H8Z M4 17H3V3H17V4"),
    History("M4 5H20V21H4Z M8 9H16 M8 13H16 M8 17H13 M8 2V5"),
    Projects("M3 9Q3 7 5 7V5H10L12 7H19Q21 7 21 9V18Q21 20 19 20H5Q3 20 3 18Z M3 9H21"),
    ProjectAdd("M3 9Q3 7 5 7V5H10L12 7H19Q21 7 21 9V18Q21 20 19 20H5Q3 20 3 18Z M12 11V17 M9 14H15"),
    Terminal("M6 4H18Q21 4 21 7V17Q21 20 18 20H6Q3 20 3 17V7Q3 4 6 4Z M7 9L10 12L7 15 M13 15H17"),
    Environment("M7 9Q7 7 9 7H15Q17 7 17 9V15Q17 17 15 17H9Q7 17 7 15Z M9 3V5 M15 3V5 M9 19V21 M15 19V21 M3 9H5 M3 15H5 M19 9H21 M19 15H21"),
    Settings("M9 3H15L16 6L19 7L22 10V14L19 17L16 18L15 21H9L8 18L5 17L2 14V10L5 7L8 6Z M15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12"),
    Info("M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2 M12 11V17 M12 7V7.2"),
    Send("M3 11L21 3L13 21L11 13Z M11 13L21 3"),
    Trash("M4 6H20 M9 6V3H15V6 M6 6L7 21H17L18 6 M10 10V17 M14 10V17"),
    Shield("M12 3L21 7V12Q20 18 12 22Q4 18 3 12V7Z M8 12L11 15L16 9"),
    Ai("M4 5H20V17H10L5 21V17H4Z M8 9H16 M8 13H13"),
    Add("M12 5V19 M5 12H19"),
    Minus("M5 12H19"),
    Import("M12 3V14 M8 10L12 14L16 10 M4 14V20H20V14"),
    Export("M12 15V4 M8 8L12 4L16 8 M4 14V20H20V14"),
    Undo("M9 5L4 10L9 15 M4 10H14Q20 10 20 16V19"),
    Redo("M15 5L20 10L15 15 M20 10H10Q4 10 4 16V19"),
    Search("M10 3A7 7 0 1 0 10 17A7 7 0 1 0 10 3 M15 15L21 21"),
    Back("M14 5L7 12L14 19"),
    Next("M9 5L16 12L9 19"),
    More("M5 11V13 M12 11V13 M19 11V13"),
    Close("M6 6L18 18 M18 6L6 18"),
    Swap("M3 8H21L17 4 M21 16H3L7 20"),
    Keyboard("M2 5H22V19H2Z M6 9H7 M11 9H12 M16 9H17 M6 12H7 M11 12H12 M16 12H17 M7 16H17"),
    File("M5 3H14L19 8V21H5Z M14 3V8H19 M8 12H16 M8 16H14"),
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

@Composable
internal fun WorkbenchIcon(glyph: Glyph, modifier: Modifier = Modifier, description: String? = null,
                           tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(glyph.vector, description, modifier.size(22.dp), tint = tint)
}

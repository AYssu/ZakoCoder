package dev.zakocoder.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.zakocoder.app.ui.theme.DarkPalette
import dev.zakocoder.app.ui.theme.LightPalette
import dev.zakocoder.app.ui.theme.ProvideWorkbenchPalette
import dev.zakocoder.app.ui.theme.Type
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** One Miuix palette, also mapped to retained Material/editor surfaces during migration. */
@Composable
internal fun WorkbenchMiuixTheme(dark: Boolean, content: @Composable () -> Unit) {
    val base = if (dark) DarkPalette else LightPalette
    val secondaryText = androidx.compose.ui.graphics.Color(if (dark) 0xFFBDBDBD else 0xFF606060)
    val colors = if (dark) darkColorScheme(primary = base.primaryFill, onPrimary = base.onPrimary,
        onSurfaceVariantSummary = secondaryText)
        else lightColorScheme(primary = base.primaryFill, onPrimary = base.onPrimary,
            onSurfaceVariantSummary = secondaryText)
    val palette = base.copy(
        background = colors.surface, surface = colors.surfaceVariant,
        surfaceSunken = colors.secondaryVariant, surfaceRaised = colors.surfaceVariant,
        textPrimary = colors.onSurface, textSecondary = colors.onSurfaceVariantSummary,
        textTertiary = secondaryText,
        divider = colors.dividerLine, borderHairline = colors.dividerLine,
    )
    val material = (if (dark) WorkbenchDarkColors else WorkbenchColors).copy(
        background = palette.background, onBackground = palette.textPrimary,
        surface = palette.surface, onSurface = palette.textPrimary,
        surfaceContainerLowest = palette.surface, surfaceContainerLow = palette.surfaceSunken,
        surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceSunken,
        surfaceContainerHighest = palette.surfaceSunken, onSurfaceVariant = palette.textSecondary,
        outlineVariant = palette.divider,
    )
    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
    MaterialTheme(colorScheme = material, typography = WorkbenchTypography,
        shapes = dev.zakocoder.app.ui.theme.WorkbenchShapes) {
        ProvideWorkbenchPalette(palette) {
            MiuixTheme(colors = colors, textStyles = defaultTextStyles(
                main = Type.body, paragraph = Type.body, body1 = Type.body, body2 = Type.meta,
                button = Type.bodyStrong, footnote1 = Type.meta, footnote2 = Type.label,
                title1 = Type.title, title2 = Type.section, title3 = Type.item, title4 = Type.body,
            ), content = content)
        }
    }
    }
}

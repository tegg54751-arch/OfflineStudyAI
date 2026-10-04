package com.offlinestudy.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.offlinestudy.ai.data.AppTheme

object Palette {
    val Indigo = Color(0xFF5C54F2)
    val Violet = Color(0xFF8C5CFF)
    val Teal = Color(0xFF00B8D4)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9500)
    val Red = Color(0xFFFF3B30)

    val brand = Brush.linearGradient(listOf(Indigo, Violet, Teal))
}

/** Оформление акцентных элементов: градиент в обычных темах, белый в «Минимал». */
data class AppStyle(
    val brand: Brush,
    val onBrand: Color,
    val accent: Color,
    val minimal: Boolean
)

val LocalIsDark = staticCompositionLocalOf { false }
val LocalStyle = staticCompositionLocalOf { AppStyle(Palette.brand, Color.White, Palette.Violet, false) }

@Composable
fun OfflineStudyTheme(theme: AppTheme, textScale: Float, content: @Composable () -> Unit) {
    val minimal = theme == AppTheme.MINIMAL
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.MINIMAL -> true
    }
    val colors = when {
        minimal -> darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            secondary = Color(0xFFB0B0B0),
            background = Color.Black,
            surface = Color(0xFF0E0E0E),
            surfaceContainerLow = Color(0xFF0E0E0E),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF161616),
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFB0B0B0)
        )
        dark -> darkColorScheme(
            primary = Color(0xFF8C8CFF),
            secondary = Palette.Teal,
            background = Color(0xFF0A0A17),
            surface = Color(0xFF14142A),
            onBackground = Color.White,
            onSurface = Color.White
        )
        else -> lightColorScheme(
            primary = Palette.Indigo,
            secondary = Palette.Teal,
            background = Color(0xFFF2F2FC),
            surface = Color.White,
            onBackground = Color(0xFF111122),
            onSurface = Color(0xFF111122)
        )
    }
    val style = if (minimal) AppStyle(SolidColor(Color.White), Color.Black, Color.White, true)
                else AppStyle(Palette.brand, Color.White, Palette.Violet, false)
    val density = LocalDensity.current
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalIsDark provides dark,
            LocalStyle provides style,
            // Цвет текста по умолчанию — из темы (раньше был чёрным и сливался с тёмным фоном).
            LocalContentColor provides colors.onBackground,
            LocalDensity provides Density(density.density, density.fontScale * textScale),
            content = content
        )
    }
}

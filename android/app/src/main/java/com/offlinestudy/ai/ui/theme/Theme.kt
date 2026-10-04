package com.offlinestudy.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** Признак тёмной темы для «стеклянных» элементов. */
val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun OfflineStudyTheme(theme: AppTheme, textScale: Float, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8C8CFF),
            secondary = Palette.Teal,
            background = Color(0xFF0A0A17),
            surface = Color(0xFF14142A),
            onBackground = Color.White,
            onSurface = Color.White
        )
    } else {
        lightColorScheme(
            primary = Palette.Indigo,
            secondary = Palette.Teal,
            background = Color(0xFFF2F2FC),
            surface = Color.White,
            onBackground = Color(0xFF111122),
            onSurface = Color(0xFF111122)
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalIsDark provides dark,
        LocalDensity provides Density(density.density, density.fontScale * textScale)
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

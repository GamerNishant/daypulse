package com.nishant.daypulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette shared with the web app (web/src/styles.css tokens). */
data class Pal(
    val bg: Color, val surface: Color, val surface2: Color, val border: Color, val text: Color, val muted: Color,
    val faint: Color, val accent: Color, val accentSoft: Color, val onAccent: Color, val good: Color, val goodSoft: Color,
    val warn: Color, val warnSoft: Color, val bad: Color, val track: Color,
)

val LightPal = Pal(
    bg = Color(0xFFF6F5F2), surface = Color.White, surface2 = Color(0xFFEFEEE9), border = Color(0xFFE3E1DA),
    text = Color(0xFF1B1D22), muted = Color(0xFF6B6F78), faint = Color(0xFF9A9DA5), accent = Color(0xFF4F6BDF),
    accentSoft = Color(0xFFE4E9FB), onAccent = Color.White, good = Color(0xFF2F8F5B), goodSoft = Color(0xFFDFF2E7),
    warn = Color(0xFFB7791F), warnSoft = Color(0xFFFBEFD9), bad = Color(0xFFC2463D), track = Color(0xFFE8E6E0),
)

val DarkPal = Pal(
    bg = Color(0xFF0F1115), surface = Color(0xFF171A20), surface2 = Color(0xFF1E222A), border = Color(0xFF262A33),
    text = Color(0xFFE8EAF0), muted = Color(0xFF9AA0AB), faint = Color(0xFF6A707B), accent = Color(0xFF7C9CFF),
    accentSoft = Color(0xFF1F2740), onAccent = Color(0xFF0F1115), good = Color(0xFF5CC98D), goodSoft = Color(0xFF17301F),
    warn = Color(0xFFE2B155), warnSoft = Color(0xFF33281A), bad = Color(0xFFEF7B72), track = Color(0xFF262A33),
)

val LocalPal = staticCompositionLocalOf { DarkPal }

@Composable
fun DayPulseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2,
            onSurfaceVariant = p.muted, outline = p.border, outlineVariant = p.border, error = p.bad,
            surfaceContainer = p.surface, surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
            secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2,
            onSurfaceVariant = p.muted, outline = p.border, outlineVariant = p.border, error = p.bad,
            surfaceContainer = p.surface, surfaceContainerLow = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
            secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        )
    }
    val type = Typography(
        headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
    )
    CompositionLocalProvider(LocalPal provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

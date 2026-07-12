package com.resourcefork.rccontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary          = Color(0xFF4FC3F7),   // cyan-ish
    onPrimary        = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    secondary        = Color(0xFFB0BEC5),
    background       = Color(0xFF0A0A0A),
    surface          = Color(0xFF1A1A1A),
    onBackground     = Color(0xFFE0E0E0),
    onSurface        = Color(0xFFCFD8DC),
    error            = Color(0xFFEF5350),
)

private val LightColors = lightColorScheme(
    primary          = Color(0xFF0277BD),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFB3E5FC),
    secondary        = Color(0xFF546E7A),
    background       = Color(0xFFF5F5F5),
    surface          = Color(0xFFFFFFFF),
    onBackground     = Color(0xFF212121),
    onSurface        = Color(0xFF424242),
    error            = Color(0xFFD32F2F),
)

@Composable
fun StrixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

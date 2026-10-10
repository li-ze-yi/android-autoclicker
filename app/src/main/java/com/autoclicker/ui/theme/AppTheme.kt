package com.autoclicker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF2F6BFF)
private val BlueDark = Color(0xFF9DBBFF)
private val Green = Color(0xFF12B76A)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Color(0xFF4A5568),
    tertiary = Green,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    secondary = Color(0xFFA0AEC0),
    tertiary = Green,
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
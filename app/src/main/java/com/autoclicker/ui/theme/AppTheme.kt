package com.autoclicker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌主色（与悬浮控件一致） */
private val Primary = Color(0xFF2962FF)
private val PrimaryDark = Color(0xFF82B1FF)
private val Error = Color(0xFFE53935)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    secondary = Color(0xFF448AFF),
    error = Error,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF002171),
    secondary = Color(0xFF82B1FF),
    error = Color(0xFFEF9A9A),
)

/**
 * 应用统一主题：品牌蓝配色，跟随系统深色模式。
 */
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

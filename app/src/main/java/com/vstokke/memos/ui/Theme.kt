package com.vstokke.memos.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// sRGB conversions of web/src/themes/default.css and default-dark.css OKLCH tokens.
private val LightColors = lightColorScheme(
    primary = Color(0xFF305880), onPrimary = Color(0xFFFAF9F5),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF242011),
    surface = Color.White, onSurface = Color(0xFF242011),
    surfaceVariant = Color(0xFFEDE9DE), onSurfaceVariant = Color(0xFF66635B),
    secondary = Color(0xFF305880), outline = Color(0xFFDAD9D4), outlineVariant = Color(0xFFDAD9D4),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B97D3), onPrimary = Color(0xFF1D1F23),
    background = Color(0xFF1D1F23), onBackground = Color(0xFFDBDEE2),
    surface = Color(0xFF25282C), onSurface = Color(0xFFDBDEE2),
    surfaceVariant = Color(0xFF373B40), onSurfaceVariant = Color(0xFFA3A7AC),
    secondary = Color(0xFF5B97D3), outline = Color(0xFF3F4348), outlineVariant = Color(0xFF3F4348),
)
private val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
)
@Composable
fun MemosPocketTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = AppTypography, content = content)
}

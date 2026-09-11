package com.vstokke.memos.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vstokke.memos.R

/* Hallmark · pre-emit critique: P5 H4 E4 S5 R5 V4
 * Hallmark · genre: modern-minimal · macrostructure: Workbench
 * design-system: design.md · designed-as-app
 */

@OptIn(ExperimentalTextApi::class)
private val Manrope = FontFamily(
    Font(
        R.font.manrope_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.manrope_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

// The dark values are sampled from the supplied Memos web-app reference.
private val LightColors = lightColorScheme(
    primary = Color(0xFF305880),
    onPrimary = Color(0xFFFAF9F5),
    primaryContainer = Color(0xFFDCEAF7),
    onPrimaryContainer = Color(0xFF173A5B),
    secondary = Color(0xFF4E6478),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5EAF0),
    onSecondaryContainer = Color(0xFF303E4B),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF242011),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF242011),
    surfaceContainerLowest = Color(0xFFF1F0EB),
    surfaceVariant = Color(0xFFEDE9DE),
    onSurfaceVariant = Color(0xFF66635B),
    outline = Color(0xFFC7C5BF),
    outlineVariant = Color(0xFFDAD9D4),
    error = Color(0xFFA33B36),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF5F0EC),
    inversePrimary = Color(0xFFA4C9EE),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82B0DF),
    onPrimary = Color(0xFF102F4C),
    primaryContainer = Color(0xFF3A6BA4),
    onPrimaryContainer = Color(0xFFF0F6FC),
    secondary = Color(0xFFAFC4D8),
    onSecondary = Color(0xFF1C3347),
    secondaryContainer = Color(0xFF2B3138),
    onSecondaryContainer = Color(0xFFDBDEE2),
    background = Color(0xFF1D1F23),
    onBackground = Color(0xFFDBDEE2),
    surface = Color(0xFF25282C),
    onSurface = Color(0xFFDBDEE2),
    surfaceContainerLowest = Color(0xFF16191C),
    surfaceVariant = Color(0xFF32363B),
    onSurfaceVariant = Color(0xFFA2A5A9),
    outline = Color(0xFF555A60),
    outlineVariant = Color(0xFF3F4348),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5F2523),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFDBDEE2),
    inverseOnSurface = Color(0xFF2E3034),
    inversePrimary = Color(0xFF305880),
    scrim = Color(0xFF000000),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MemosPocketTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

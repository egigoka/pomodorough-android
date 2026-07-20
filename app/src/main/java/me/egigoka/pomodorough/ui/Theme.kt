@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package me.egigoka.pomodorough.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF211A2F)
val Violet = Color(0xFF6750E6)
val Tomato = Color(0xFFFF6846)
val Butter = Color(0xFFFFDE76)
val Mint = Color(0xFF8BE0C2)
val Lavender = Color(0xFFE9E3FF)
val Cloud = Color(0xFFF9F7FF)
val MutedInk = Color(0xFF625B6D)
val Outline = Color(0xFF7B7387)
val Danger = Color(0xFFB3261E)

private val colors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Lavender,
    onPrimaryContainer = Ink,
    secondary = Tomato,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFFFDBD2),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF007A60),
    onTertiary = Color.White,
    tertiaryContainer = Mint,
    onTertiaryContainer = Ink,
    background = Cloud,
    onBackground = Ink,
    surface = Cloud,
    onSurface = Ink,
    surfaceVariant = Lavender,
    onSurfaceVariant = MutedInk,
    outline = Outline,
    outlineVariant = Color(0xFFD0C8D8),
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 58.sp,
        lineHeight = 56.sp,
        letterSpacing = (-2.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 40.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(42.dp),
    largeIncreased = RoundedCornerShape(36.dp),
    extraLargeIncreased = RoundedCornerShape(50.dp),
    extraExtraLarge = RoundedCornerShape(64.dp),
)

@Composable
fun PomodoroughTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

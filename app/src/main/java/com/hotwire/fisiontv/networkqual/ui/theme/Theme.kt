package com.hotwire.fisiontv.networkqual.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val FisionPink = Color(0xFFEE3D5B)
val FisionPinkSoft = Color(0xFFFF7B9A)
val FisionNavy = Color(0xFF0E1B2D)
val FisionNavyLight = Color(0xFF16263D)
val FisionSuccessGreen = Color(0xFF22C55E)
val FisionHealthExcellent = Color(0xFF22C55E)
val FisionHealthStrong = Color(0xFF84CC16)
val FisionHealthGood = Color(0xFFEAB308)
val FisionHealthMarginal = Color(0xFFF97316)
val FisionHealthFailed = Color(0xFFEF4444)

private val FisionColors = darkColorScheme(
    primary = FisionPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1421),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFF8FA0BD),
    onSecondary = Color(0xFF0E1B2D),
    background = FisionNavy,
    onBackground = Color(0xFFE5E9F0),
    surface = FisionNavyLight,
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1F3252),
    onSurfaceVariant = Color(0xFFB0BCD0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF5C7390)
)

private val TvTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 22.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun FisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FisionColors,
        typography = TvTypography,
        content = content
    )
}

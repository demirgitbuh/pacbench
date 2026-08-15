package com.demirarch.pacbench.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.demirarch.pacbench.data.settings.ThemeMode

val PacOrange = Color(0xFFFF7A1A)
val PacOrangeBright = Color(0xFFFFA052)
val PacNearBlack = Color(0xFF11100F)
val PacPanel = Color(0xFF1A1816)
val PacGrid = Color(0xFF3A332E)
val PacGood = Color(0xFF73D39A)
val PacWarn = Color(0xFFFFC46B)
val PacBad = Color(0xFFFF776D)

private val DarkColors = darkColorScheme(
    primary = PacOrangeBright,
    onPrimary = Color(0xFF2B1300),
    primaryContainer = Color(0xFF522600),
    onPrimaryContainer = Color(0xFFFFDCC4),
    secondary = Color(0xFFD9BBA5),
    onSecondary = Color(0xFF35271E),
    secondaryContainer = Color(0xFF4D3A2E),
    onSecondaryContainer = Color(0xFFF6DCC9),
    background = PacNearBlack,
    onBackground = Color(0xFFF1EAE4),
    surface = PacPanel,
    onSurface = Color(0xFFF1EAE4),
    surfaceVariant = Color(0xFF28231F),
    onSurfaceVariant = Color(0xFFD2C6BD),
    outline = Color(0xFF8D7E73),
    error = PacBad,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF9B4300),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC5),
    onPrimaryContainer = Color(0xFF321200),
    secondary = Color(0xFF745844),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC5),
    onSecondaryContainer = Color(0xFF2A170B),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF211A16),
    surface = Color(0xFFFFF8F3),
    onSurface = Color(0xFF211A16),
    surfaceVariant = Color(0xFFF3E3D8),
    onSurfaceVariant = Color(0xFF51443B),
    outline = Color(0xFF83746A),
    error = Color(0xFFBA1A1A),
)

private val PacTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
)

@Composable
fun PacBenchTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PacTypography,
        content = content,
    )
}

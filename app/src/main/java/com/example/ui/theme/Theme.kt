package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF135028),
    onPrimaryContainer = Color(0xFFA5F4BF),
    secondary = StudioElevatedSurface,
    onSecondary = TextPrimary,
    secondaryContainer = StudioCardSurface,
    onSecondaryContainer = TextSecondary,
    tertiary = AmberGold,
    onTertiary = Color.Black,
    background = StudioDarkBackground,
    onBackground = TextPrimary,
    surface = StudioDarkBackground,
    onSurface = TextPrimary,
    surfaceVariant = StudioCardSurface,
    onSurfaceVariant = TextSecondary,
    outline = StudioBorder,
    error = SignalRed,
    onError = Color.White
)

private val OrangeColorScheme = darkColorScheme(
    primary = OrangePrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF472100),
    onPrimaryContainer = Color(0xFFFFD2A6),
    secondary = OrangeElevatedSurface,
    onSecondary = TextPrimary,
    secondaryContainer = OrangeSurface,
    onSecondaryContainer = Color(0xFFFFB366),
    tertiary = OrangePrimaryBright,
    onTertiary = Color.Black,
    background = OrangeBackground,
    onBackground = TextPrimary,
    surface = OrangeBackground,
    onSurface = TextPrimary,
    surfaceVariant = OrangeSurface,
    onSurfaceVariant = Color(0xFFE0C4B0),
    outline = OrangeBorder,
    error = SignalRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    isTravelMode: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (isTravelMode) OrangeColorScheme else StudioColorScheme,
        typography = Typography,
        content = content
    )
}

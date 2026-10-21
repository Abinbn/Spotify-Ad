package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF08182F),
    primaryContainer = Color(0xFF1C2C48),
    onPrimaryContainer = Color(0xFFD3E1FF),
    secondary = Ink300,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Ink100,
    tertiary = KeepAmber,
    onTertiary = Color(0xFF231A00),
    background = Ink950,
    onBackground = Ink100,
    surface = Ink900,
    onSurface = Ink100,
    surfaceVariant = Ink850,
    onSurfaceVariant = Ink300,
    outline = Ink700,
    outlineVariant = Color(0xFF20272C),
    error = RecordRed,
    onError = Color(0xFF2A0909)
)

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2559),
    secondary = Color(0xFF49545F),
    onSecondary = Color.White,
    secondaryContainer = PaperSunken,
    onSecondaryContainer = Color(0xFF232A30),
    tertiary = Color(0xFF8A6400),
    onTertiary = Color.White,
    background = Paper,
    onBackground = Color(0xFF12171B),
    surface = PaperCard,
    onSurface = Color(0xFF12171B),
    surfaceVariant = PaperSunken,
    onSurfaceVariant = Color(0xFF566270),
    outline = PaperLine,
    outlineVariant = Color(0xFFE9ECEF),
    error = RecordRedDeep,
    onError = Color.White
)

private val SpotAdShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun SpotAdTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SpotAdTypography,
        shapes = SpotAdShapes,
        content = content
    )
}

package com.rahul.imager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.rahul.imager.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = EmberContainer,
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = Color(0xFF16272D),
    tertiary = Color(0xFF6B5B3E),
    background = PaperWhite,
    onBackground = InkBlack,
    surface = PaperWhite,
    onSurface = InkBlack,
    surfaceVariant = PaperSurface,
    onSurfaceVariant = InkMuted,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceHigh,
    outline = InkOutline,
    outlineVariant = Color(0xFFE2DACD),
)

private val DarkScheme = darkColorScheme(
    primary = EmberLight,
    onPrimary = Color(0xFF411800),
    primaryContainer = EmberContainerDark,
    onPrimaryContainer = EmberContainer,
    secondary = SlateLight,
    onSecondary = Color(0xFF12262D),
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = SlateContainer,
    tertiary = Color(0xFFD8C4A0),
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkMuted,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    outline = NightOutline,
    outlineVariant = Color(0xFF2E2A26),
)

/**
 * Colours the printer state dot uses.
 *
 * They are not part of `ColorScheme` because Material has no slot for "this thing is healthy", and
 * inventing one out of `primary` would make a connected printer change colour under dynamic
 * theming — which is exactly the sort of state signal that must stay recognisable.
 */
data class StatusColors(
    val connected: Color,
    val warning: Color,
    val error: Color,
    val idle: Color,
)

/** Access to [StatusColors] from anywhere inside [ImagerTheme]. */
val LocalStatusColors: ProvidableCompositionLocal<StatusColors> = staticCompositionLocalOf {
    StatusColors(StatusConnected, StatusWarning, Color.Red, Color.Gray)
}

/**
 * The app theme.
 *
 * Dynamic colour is used on Android 12+ because a utility the user opens ten times a day should
 * feel like part of their phone. The hand-tuned ink-and-paper scheme is the fallback, and is what
 * gives the app its own identity everywhere else.
 */
@Composable
fun ImagerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val statusColors = StatusColors(
        connected = if (darkTheme) StatusConnectedDark else StatusConnected,
        warning = if (darkTheme) StatusWarningDark else StatusWarning,
        error = colorScheme.error,
        idle = colorScheme.outline,
    )

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ImagerShapes,
            content = content,
        )
    }
}

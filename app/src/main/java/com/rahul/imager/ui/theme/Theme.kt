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
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandTint,
    onPrimaryContainer = BrandInk,
    secondary = TextMutedLight,
    onSecondary = Color.White,
    secondaryContainer = SurfaceMutedLight,
    onSecondaryContainer = TextLight,
    tertiary = BrandBluePressed,
    background = PageLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = SurfaceMutedLight,
    onSurfaceVariant = TextMutedLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceMutedLight,
    surfaceContainerHigh = SurfaceSunkenLight,
    surfaceContainerHighest = SurfaceSunkenLight,
    outline = BorderStrongLight,
    outlineVariant = BorderLight,
    error = StatusError,
    onError = Color.White,
    errorContainer = StatusErrorTint,
    onErrorContainer = Color(0xFF6B1111),
    scrim = Color(0xFF0A1020),
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF04204F),
    primaryContainer = BrandTintDark,
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = TextMutedDark,
    onSecondary = Color(0xFF0A1020),
    secondaryContainer = SurfaceMutedDark,
    onSecondaryContainer = TextDark,
    tertiary = BrandBlueLight,
    background = PageDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = TextMutedDark,
    surfaceContainerLowest = PageDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceMutedDark,
    surfaceContainerHigh = SurfaceSunkenDark,
    surfaceContainerHighest = SurfaceSunkenDark,
    outline = BorderStrongDark,
    outlineVariant = BorderDark,
    error = StatusErrorDark,
    onError = Color(0xFF3B0A0A),
    errorContainer = StatusErrorTintDark,
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

/**
 * Colours with no Material slot.
 *
 * Printer state is the main one: Material has nowhere to say "this thing is healthy", and building
 * it out of `primary` would make a connected printer change colour with the theme — exactly the
 * sort of signal that has to stay recognisable at a glance.
 */
data class StatusColors(
    val connected: Color,
    val warning: Color,
    val error: Color,
    val idle: Color,
    val connectedTint: Color,
    val warningTint: Color,
    val errorTint: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
)

/** Access to [StatusColors] from anywhere inside [ImagerTheme]. */
val LocalStatusColors: ProvidableCompositionLocal<StatusColors> = staticCompositionLocalOf {
    StatusColors(
        connected = StatusConnected,
        warning = StatusWarning,
        error = StatusError,
        idle = BorderStrongLight,
        connectedTint = StatusConnectedTint,
        warningTint = StatusWarningTint,
        errorTint = StatusErrorTint,
        gradientStart = BrandGradientStart,
        gradientEnd = BrandGradientEnd,
    )
}

/**
 * The app theme.
 *
 * Dynamic colour is OFF by default. It is a lovely default for a system utility, but this app has
 * an identity of its own — one blue, everywhere, doing all the work — and letting the wallpaper
 * repaint it turns every button a different colour on every phone.
 */
@Composable
fun ImagerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
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
        connectedTint = if (darkTheme) StatusConnectedTintDark else StatusConnectedTint,
        warningTint = if (darkTheme) StatusWarningTintDark else StatusWarningTint,
        errorTint = if (darkTheme) StatusErrorTintDark else StatusErrorTint,
        gradientStart = if (darkTheme) BrandBlue else BrandGradientStart,
        gradientEnd = if (darkTheme) BrandGradientEnd else BrandGradientEnd,
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

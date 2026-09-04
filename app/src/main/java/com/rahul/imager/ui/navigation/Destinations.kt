package com.rahul.imager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.rahul.imager.R
import kotlinx.serialization.Serializable

/**
 * The app's routes, as type-safe Navigation Compose destinations.
 *
 * Serializable classes rather than string routes: an argument that changes type is then a compile
 * error instead of a crash the first time somebody opens that screen.
 */

/** The photo-to-print home screen. */
@Serializable
data object HomeRoute

/** The preview and options screen for one picked photo. */
@Serializable
data class PreviewRoute(val imageUri: String)

/** The in-app album browser, an alternative to the system photo picker. */
@Serializable
data object GalleryRoute

/** The list of saved printers. */
@Serializable
data object PrintersRoute

/** The add-printer flow. */
@Serializable
data object AddPrinterRoute

/** App settings. */
@Serializable
data object SettingsRoute

/** The hidden diagnostics screen, reached by long-pressing the version in Settings. */
@Serializable
data object DiagnosticsRoute

/**
 * The three top-level destinations that appear in the bottom bar or navigation rail.
 *
 * Everything else — preview, the add flow, diagnostics — is pushed on top and hides the bar,
 * because those are tasks the user is in the middle of rather than places they are.
 */
enum class TopLevelDestination(
    val route: Any,
    val icon: ImageVector,
    val labelRes: Int,
) {
    HOME(HomeRoute, Icons.Default.Photo, R.string.home_title),
    PRINTERS(PrintersRoute, Icons.Default.Print, R.string.printers_title),
    SETTINGS(SettingsRoute, Icons.Default.Settings, R.string.settings_title),
}

/** True when [destination] is (or is nested under) this top-level destination. */
fun NavDestination?.isTopLevel(target: TopLevelDestination): Boolean = when (target) {
    TopLevelDestination.HOME -> this?.hasRoute(HomeRoute::class) == true
    TopLevelDestination.PRINTERS -> this?.hasRoute(PrintersRoute::class) == true
    TopLevelDestination.SETTINGS -> this?.hasRoute(SettingsRoute::class) == true
}

/** True when the current destination should show the app-level navigation bar or rail. */
fun NavDestination?.showsNavigation(): Boolean =
    TopLevelDestination.entries.any { isTopLevel(it) }

package com.rahul.imager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rahul.imager.ui.navigation.AddPrinterRoute
import com.rahul.imager.ui.navigation.DiagnosticsRoute
import com.rahul.imager.ui.navigation.GalleryRoute
import com.rahul.imager.ui.navigation.HomeRoute
import com.rahul.imager.ui.navigation.PreviewRoute
import com.rahul.imager.ui.navigation.PrintersRoute
import com.rahul.imager.ui.navigation.SettingsRoute
import com.rahul.imager.ui.navigation.TopLevelDestination
import com.rahul.imager.ui.navigation.isTopLevel
import com.rahul.imager.ui.navigation.showsNavigation
import com.rahul.imager.ui.screens.diagnostics.DiagnosticsScreen
import com.rahul.imager.ui.screens.gallery.GalleryScreen
import com.rahul.imager.ui.screens.home.HomeScreen
import com.rahul.imager.ui.screens.preview.PreviewScreen
import com.rahul.imager.ui.screens.printers.AddPrinterScreen
import com.rahul.imager.ui.screens.printers.PrintersScreen
import com.rahul.imager.ui.screens.settings.SettingsScreen

/**
 * The app shell and its navigation graph.
 *
 * The top-level navigation ADAPTS rather than scales: a bottom bar on a compact screen, a
 * navigation rail everywhere wider. Task screens — preview, the add flow, diagnostics — take the
 * whole window and hide the bar, because they are somewhere the user is passing through rather
 * than somewhere they are.
 */
@Composable
fun ImagerApp(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination: NavDestination? = backStackEntry?.destination
    val showNavigation = currentDestination.showsNavigation()
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // The system photo picker, hoisted here so the gallery screen can hand off to it.
    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { navController.navigate(PreviewRoute(it.toString())) } }

    Row(modifier = modifier.fillMaxSize()) {
        if (showNavigation && useRail) {
            NavigationRail {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = currentDestination.isTopLevel(destination),
                        onClick = { navController.navigateTopLevel(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (showNavigation && !useRail) {
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentDestination.isTopLevel(destination),
                                onClick = { navController.navigateTopLevel(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            onPhotoPicked = { uri ->
                                navController.navigate(PreviewRoute(uri.toString()))
                            },
                            onBrowseGallery = { navController.navigate(GalleryRoute) },
                            onAddPrinter = { navController.navigate(AddPrinterRoute) },
                            onReprint = { uri ->
                                navController.navigate(PreviewRoute(uri.toString()))
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }

                    composable<GalleryRoute> {
                        GalleryScreen(
                            onPhotoPicked = { uri ->
                                navController.navigate(PreviewRoute(uri.toString()))
                            },
                            onUseSystemPicker = {
                                pickPhoto.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable<PreviewRoute> {
                        PreviewScreen(
                            windowSizeClass = windowSizeClass,
                            onBack = { navController.popBackStack() },
                            onAddPrinter = { navController.navigate(AddPrinterRoute) },
                        )
                    }

                    composable<PrintersRoute> {
                        PrintersScreen(
                            onAddPrinter = { navController.navigate(AddPrinterRoute) },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }

                    composable<AddPrinterRoute> {
                        AddPrinterScreen(
                            onFinished = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }

                    composable<DiagnosticsRoute> {
                        DiagnosticsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

/**
 * Switches top-level tab.
 *
 * `launchSingleTop` plus `restoreState` gives each tab its own back stack, so returning to Home
 * from Settings puts the user back where they were rather than at a fresh screen.
 */
private fun NavHostController.navigateTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

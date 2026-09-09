package com.rahul.imager

import android.app.Application
import com.rahul.imager.data.PrinterConnectionManager
import com.rahul.imager.launcher.LauncherIconManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Beyond wiring Hilt it does two things: it starts [PrinterConnectionManager], which keeps the
 * default printer connected in the background and makes the photo-to-print path one tap, and it
 * lets [LauncherIconManager] keep the launcher icon in step with the weekday.
 */
@HiltAndroidApp
class ImagerApplication : Application() {

    @Inject
    lateinit var connectionManager: PrinterConnectionManager

    @Inject
    lateinit var launcherIconManager: LauncherIconManager

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
        launcherIconManager.startSwappingOnBackground(this)
    }
}

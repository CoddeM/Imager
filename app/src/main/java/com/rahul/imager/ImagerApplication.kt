package com.rahul.imager

import android.app.Application
import com.rahul.imager.data.PrinterConnectionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application entry point.
 *
 * Its one job beyond wiring Hilt is to start [PrinterConnectionManager], which is what keeps the
 * default printer connected in the background and makes the photo-to-print path one tap.
 */
@HiltAndroidApp
class ImagerApplication : Application() {

    @Inject
    lateinit var connectionManager: PrinterConnectionManager

    override fun onCreate() {
        super.onCreate()
        connectionManager.start()
    }
}

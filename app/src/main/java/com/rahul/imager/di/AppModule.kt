package com.rahul.imager.di

import android.content.Context
import com.rahul.imager.printer.engine.PrintEngine
import com.rahul.imager.printer.engine.PrinterCircuitBreaker
import com.rahul.imager.printer.raster.RasterPipeline
import com.rahul.imager.printer.transport.UsbAttachMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for the types that live in the `:printer` module.
 *
 * `:printer` deliberately has no Hilt dependency of its own — it is a plain library that could be
 * used from anywhere — so the app supplies its singletons here.
 *
 * Note that every singleton takes the APPLICATION context. Holding an Activity context in a
 * process-scoped object leaks the whole activity, and these objects outlive every screen.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The print engine, shared app-wide.
     *
     * One instance matters: the engine owns the per-printer mutexes and the circuit breaker, and a
     * second instance would have its own copies, so two screens could print to one printer at
     * once and neither would fast-fail when it broke.
     */
    @Provides
    @Singleton
    fun providePrintEngine(): PrintEngine = PrintEngine(breaker = PrinterCircuitBreaker())

    /** The rasterizer. Stateless, so one shared instance is enough. */
    @Provides
    @Singleton
    fun provideRasterPipeline(): RasterPipeline = RasterPipeline()

    /** Watches USB printers being plugged in and out, for the whole process. */
    @Provides
    @Singleton
    fun provideUsbAttachMonitor(@ApplicationContext context: Context): UsbAttachMonitor =
        UsbAttachMonitor(context)
}

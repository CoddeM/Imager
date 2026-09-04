package com.rahul.imager.printer.driver.epson

import android.content.Context
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverFactory

/**
 * Registers the Epson family with [com.rahul.imager.printer.driver.registry.PrinterDriverRegistry].
 *
 * The registry finds this class by its fully-qualified NAME through reflection, so renaming or
 * moving it silently disables Epson support. The name is listed in
 * `PrinterDriverRegistry.OPTIONAL_FACTORY_CLASSES` and the two must stay in step.
 */
object EpsonDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.EPSON

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override val requiredSdkArtifact: String = "ePOS2.jar"

    override fun isSupported(context: Context): Boolean = true

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (saved.transport !in supportedTransports) return null
        return EpsonThermalPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
            model = saved.model,
        )
    }

    override fun discovery(): PrinterDiscovery = EpsonDiscovery
}

package com.rahul.imager.printer.driver.volcora

import android.content.Context
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverFactory

/**
 * Registers the Volcora / Xprinter-OEM family.
 *
 * Found by fully-qualified name from `PrinterDriverRegistry.OPTIONAL_FACTORY_CLASSES`; renaming
 * this class without updating that list silently disables the family.
 */
object VolcoraDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.VOLCORA

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override val requiredSdkArtifact: String = "printer-lib-3.2.0.aar"

    override fun isSupported(context: Context): Boolean = true

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (saved.transport !in supportedTransports) return null
        return VolcoraThermalPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
            port = saved.port,
        )
    }

    override fun discovery(): PrinterDiscovery = VolcoraDiscovery
}

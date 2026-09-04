package com.rahul.imager.printer.driver.registry

import android.content.Context
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.generic.GenericDiscovery
import com.rahul.imager.printer.driver.generic.GenericEscPosPrinter
import com.rahul.imager.printer.driver.star.StarDiscovery
import com.rahul.imager.printer.driver.star.StarPrinterManager
import com.rahul.imager.printer.driver.star.StarThermalPrinter
import com.rahul.imager.printer.driver.sunmi.SunmiCloudThermalPrinter
import com.rahul.imager.printer.driver.sunmi.SunmiDiscovery
import com.rahul.imager.printer.driver.sunmi.SunmiExternalBtPrinter
import com.rahul.imager.printer.driver.sunmi.SunmiInnerManager
import com.rahul.imager.printer.driver.sunmi.SunmiInnerPrinter

/**
 * The generic ESC/POS family: LAN, Bluetooth SPP and USB, no vendor SDK, always available.
 */
object GenericDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.GENERIC_ESCPOS

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (saved.transport !in supportedTransports) return null
        return GenericEscPosPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
            port = saved.port,
            brand = saved.brand.takeIf { it != PrinterBrand.UNKNOWN }
                ?: PrinterBrand.GENERIC_ESCPOS,
        )
    }

    override fun discovery(): PrinterDiscovery = GenericDiscovery
}

/**
 * Star Micronics. The SDK ships from Maven, so the family is always compiled in, but it needs
 * API 26 — below that the family reports itself unsupported rather than crashing on class load.
 */
object StarDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.STAR

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = StarPrinterManager.isSupported

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (!StarPrinterManager.isSupported) return null
        if (saved.transport !in supportedTransports) return null
        return StarThermalPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
        )
    }

    override fun discovery(): PrinterDiscovery = StarDiscovery

    override fun unavailableReason(context: Context): String =
        "Star printers need Android 8.0 or newer"
}

/**
 * Sunmi, covering three quite different SDKs behind one family:
 *
 *  * the built-in head, through the AIDL print service (INNER);
 *  * external Bluetooth units, through `external-printerlibrary`;
 *  * external LAN and USB units, through `external-printerlibrary2` (CloudPrinter).
 *
 * The transport picks which one, which is why Sunmi is NOT a connection-independent family even
 * though its built-in variant is.
 */
object SunmiDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.SUNMI

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.INNER, TransportType.BLUETOOTH, TransportType.LAN, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun create(saved: SavedPrinter): ThermalPrinter? = when (saved.transport) {
        TransportType.INNER ->
            if (SunmiInnerManager.isSunmiDevice) SunmiInnerPrinter(saved.displayName) else null

        TransportType.BLUETOOTH -> SunmiExternalBtPrinter(saved.displayName, saved.identifier)

        TransportType.LAN, TransportType.USB -> SunmiCloudThermalPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
            port = saved.port,
        )
    }

    override fun discovery(): PrinterDiscovery = SunmiDiscovery
}

package com.rahul.imager.printer.driver.volcorav2

import android.content.Context
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverFactory
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.UsbHostPermission
import com.rahul.imager.printer.transport.UsbVendorId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Discovery for the Volcora V2 / SPRT family.
 *
 * This SDK ships no network search of its own, so LAN printers are added by manual entry (IP and
 * port) in the add-printer flow. Bluetooth and USB enumerate from the OS, which is authoritative
 * anyway.
 */
object VolcoraV2Discovery : PrinterDiscovery {

    override val brand: PrinterBrand = PrinterBrand.VOLCORA_V2

    override val transports: Set<TransportType> =
        setOf(TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        flow {
            when (transport) {
                TransportType.BLUETOOTH -> BluetoothLink.bondedDevices(context).forEach { device ->
                    val name = runCatching { device.name }.getOrNull() ?: device.address
                    emit(
                        DiscoveredPrinter(
                            brand = PrinterBrand.VOLCORA_V2,
                            transport = TransportType.BLUETOOTH,
                            identifier = device.address,
                            name = name,
                            model = name,
                            detail = device.address,
                        )
                    )
                }

                TransportType.USB -> UsbHostPermission.attachedDevices(context)
                    .filter { it.vendorId !in UsbVendorId.KNOWN_PRINTER_VENDORS }
                    .forEach { device ->
                        emit(
                            DiscoveredPrinter(
                                brand = PrinterBrand.VOLCORA_V2,
                                transport = TransportType.USB,
                                identifier = device.deviceName,
                                name = runCatching { device.productName }.getOrNull()
                                    ?: device.deviceName,
                                model = runCatching { device.productName }.getOrNull(),
                                detail = "VID 0x%04X · PID 0x%04X".format(
                                    device.vendorId,
                                    device.productId,
                                ),
                            )
                        )
                    }

                else -> Unit
            }
        }
}

/**
 * Registers the Volcora V2 / SPRT family.
 *
 * Found by fully-qualified name from `PrinterDriverRegistry.OPTIONAL_FACTORY_CLASSES`.
 */
object VolcoraV2DriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.VOLCORA_V2

    override val supportedTransports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override val requiredSdkArtifact: String = "printersdkv5.7.2.jar"

    override fun isSupported(context: Context): Boolean = true

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (saved.transport !in supportedTransports) return null
        return VolcoraV2ThermalPrinter(
            displayName = saved.displayName,
            transport = saved.transport,
            identifier = saved.identifier,
            port = saved.port,
        )
    }

    override fun discovery(): PrinterDiscovery = VolcoraV2Discovery
}

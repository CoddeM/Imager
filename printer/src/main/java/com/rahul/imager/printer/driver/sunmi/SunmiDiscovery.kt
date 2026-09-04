package com.rahul.imager.printer.driver.sunmi

import android.content.Context
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.DEFAULT_LAN_PORT
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.BluetoothLink
import com.sunmi.externalprinterlibrary2.SearchMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Discovery for Sunmi's external printers, on top of the CloudPrinter search.
 *
 * The SDK's search is a fire-and-forget callback with no completion signal, so
 * [SunmiCloudManager.search] closes the window itself and this simply emits what it collected.
 */
object SunmiDiscovery : PrinterDiscovery {

    override val brand: PrinterBrand = PrinterBrand.SUNMI

    override val transports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.USB, TransportType.BLUETOOTH)

    override fun isSupported(context: Context): Boolean = true

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        flow {
            val method = when (transport) {
                TransportType.LAN -> SearchMethod.LAN
                TransportType.USB -> SearchMethod.USB
                TransportType.BLUETOOTH -> SearchMethod.BT
                TransportType.INNER -> return@flow
            }
            if (transport == TransportType.BLUETOOTH &&
                !BluetoothLink.hasScanPermission(context)
            ) {
                return@flow
            }

            val seen = mutableSetOf<String>()
            SunmiCloudManager.search(context, method).forEach { printer ->
                val info = printer.cloudPrinterInfo
                val identifier = with(SunmiCloudManager) { printer.identity() }
                if (identifier.isBlank() || !seen.add(identifier)) return@forEach
                emit(
                    DiscoveredPrinter(
                        brand = PrinterBrand.SUNMI,
                        transport = transport,
                        identifier = identifier,
                        name = info?.name?.takeIf { it.isNotBlank() } ?: "Sunmi printer",
                        model = info?.name,
                        port = info?.port?.takeIf { it > 0 } ?: DEFAULT_LAN_PORT,
                        detail = buildString {
                            info?.mac?.takeIf { it.isNotBlank() }?.let { append(it) }
                            if (info != null && info.vid != 0) {
                                if (isNotEmpty()) append(" · ")
                                append("VID 0x%04X".format(info.vid))
                            }
                        }.takeIf { it.isNotEmpty() },
                    )
                )
            }
        }

    /**
     * True when the app is running on Sunmi hardware, i.e. when the built-in printer family is
     * worth offering at all.
     */
    fun hasBuiltInPrinter(): Boolean = SunmiInnerManager.isSunmiDevice
}

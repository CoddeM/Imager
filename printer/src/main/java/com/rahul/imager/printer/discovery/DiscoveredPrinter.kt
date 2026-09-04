package com.rahul.imager.printer.discovery

import android.content.Context
import com.rahul.imager.printer.domain.DEFAULT_LAN_PORT
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import kotlinx.coroutines.flow.Flow

/**
 * A printer a scan turned up, before the user has decided to keep it.
 *
 * @param identifier the address to save: IP, MAC, or USB device path.
 * @param model raw model / product string, when the transport reveals one. This is the evidence
 *   [com.rahul.imager.printer.domain.PaperWidthResolver] uses, and it is frozen on the saved
 *   printer, so it must be the DEVICE's own string and never a user-facing label.
 */
data class DiscoveredPrinter(
    val brand: PrinterBrand,
    val transport: TransportType,
    val identifier: String,
    val name: String,
    val model: String? = null,
    val port: Int = DEFAULT_LAN_PORT,
    /** Free-form extra detail shown under the name in the scan list (MAC, VID/PID, ...). */
    val detail: String? = null,
) {
    /** De-duplication key: one physical unit must appear once, however many times it answers. */
    val key: String get() = "${brand.name}:${transport.name}:${identifier.lowercase()}"
}

/**
 * A scan for one printer family on one transport.
 *
 * Implementations emit results as they arrive and complete when the discovery window closes, so
 * the UI can show a live-updating list with a real end.
 */
interface PrinterDiscovery {

    /** The family this discovery finds. */
    val brand: PrinterBrand

    /** The transports it can scan. */
    val transports: Set<TransportType>

    /** True when this discovery can run on this device right now. */
    fun isSupported(context: Context): Boolean

    /**
     * Runs a scan.
     *
     * The flow completes when the scan window closes. Cancelling the collector must stop the
     * underlying scan.
     */
    fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter>
}

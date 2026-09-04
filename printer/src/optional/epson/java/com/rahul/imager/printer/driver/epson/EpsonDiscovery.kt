package com.rahul.imager.printer.driver.epson

import android.content.Context
import android.util.Log
import com.epson.epos2.discovery.DeviceInfo
import com.epson.epos2.discovery.Discovery
import com.epson.epos2.discovery.FilterOption
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Epson device discovery, on top of `com.epson.epos2.discovery.Discovery`.
 *
 * The awkward part is stopping: `Discovery.stop()` throws `ERR_PROCESSING` while a scan is still
 * winding down, so it has to be retried in a loop until it takes (or fails for another reason).
 * [EpsonPrinterManager.stopDiscoveryBlocking] owns that loop.
 */
object EpsonDiscovery : PrinterDiscovery {

    private const val TAG = "EpsonDiscovery"

    /** How long a scan is allowed to run before the caller closes the window. */
    const val DISCOVERY_WINDOW_MS = 10_000L

    override val brand: PrinterBrand = PrinterBrand.EPSON

    override val transports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        callbackFlow {
            val seenIps = mutableSetOf<String>()

            val filter = FilterOption().apply {
                deviceType = Discovery.TYPE_PRINTER
                epsonFilter = Discovery.FILTER_NAME
            }

            val listener = com.epson.epos2.discovery.DiscoveryListener { info: DeviceInfo? ->
                info ?: return@DiscoveryListener
                val discovered = info.toDiscovered(transport) ?: return@DiscoveryListener
                // The SDK re-reports the same unit as it refines what it knows about it.
                if (seenIps.add(discovered.identifier)) trySend(discovered)
            }

            runCatching { Discovery.start(context.applicationContext, filter, listener) }
                .onFailure {
                    Log.w(TAG, "Discovery.start failed", it)
                    close(it)
                    return@callbackFlow
                }

            awaitClose {
                EpsonPrinterManager.stopDiscoveryBlocking { Discovery.stop() }
            }
        }

    /**
     * Resolves the live `USB:` target for a saved USB printer.
     *
     * USB targets are not constructible from a saved address, so the only honest way to get one is
     * to run a scan and take what the SDK reports.
     */
    suspend fun findUsbTarget(context: Context, identifier: String): String? =
        withTimeoutOrNull(DISCOVERY_WINDOW_MS) {
            val candidates = discover(context, TransportType.USB).toList()
            candidates.firstOrNull { it.identifier == identifier }?.identifier
                ?: candidates.singleOrNull()?.identifier
        }

    private fun DeviceInfo.toDiscovered(transport: TransportType): DiscoveredPrinter? {
        val target = runCatching { target }.getOrNull().orEmpty()
        val name = runCatching { deviceName }.getOrNull().orEmpty()
        val ip = runCatching { ipAddress }.getOrNull().orEmpty()

        val identifier = when (transport) {
            TransportType.LAN -> ip.takeIf { it.isNotBlank() }
            TransportType.USB -> target.takeIf { it.startsWith("USB:") }
            TransportType.BLUETOOTH -> runCatching { macAddress }.getOrNull()
                ?.takeIf { it.isNotBlank() }

            TransportType.INNER -> null
        } ?: return null

        return DiscoveredPrinter(
            brand = PrinterBrand.EPSON,
            transport = transport,
            identifier = identifier,
            name = name.ifBlank { "Epson printer" },
            model = name.ifBlank { null },
            detail = listOfNotNull(ip.takeIf { it.isNotBlank() }, target.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .takeIf { it.isNotEmpty() },
        )
    }
}

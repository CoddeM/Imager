package com.rahul.imager.printer.driver.volcora

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.DEFAULT_LAN_PORT
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.BluetoothLink
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import net.posprinter.posprinterface.UdpCallback
import net.posprinter.utils.PosUdpNet

/**
 * Discovery for the Volcora / Xprinter-OEM family.
 *
 * The UDP LAN search has NO completion callback at all — it keeps reporting devices until the
 * socket is closed — so the caller owns the window and closes the socket itself.
 */
object VolcoraDiscovery : PrinterDiscovery {

    private const val TAG = "VolcoraDiscovery"

    /** How long the UDP search is allowed to run. */
    const val LAN_SEARCH_WINDOW_MS = 6_000L

    override val brand: PrinterBrand = PrinterBrand.VOLCORA

    override val transports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        when (transport) {
            TransportType.LAN -> lanSearch(context)
            TransportType.USB -> usbDevices(context)
            TransportType.BLUETOOTH -> bondedDevices(context)
            TransportType.INNER -> flow { }
        }

    private fun lanSearch(context: Context): Flow<DiscoveredPrinter> = callbackFlow {
        VolcoraManager.ensureInitialized(context)
        val udp = PosUdpNet()
        val seen = mutableSetOf<String>()

        val callback = UdpCallback { device ->
            val ip = runCatching { device?.ipStr }.getOrNull().orEmpty()
            if (ip.isBlank() || !seen.add(ip)) return@UdpCallback
            val mac = runCatching { device?.macStr }.getOrNull().orEmpty()
            trySend(
                DiscoveredPrinter(
                    brand = PrinterBrand.VOLCORA,
                    transport = TransportType.LAN,
                    identifier = ip,
                    name = "Volcora printer",
                    model = null,
                    port = DEFAULT_LAN_PORT,
                    detail = listOf(ip, mac).filter { it.isNotBlank() }.joinToString(" · "),
                )
            )
        }

        runCatching { udp.searchNetDevice(callback) }.onFailure {
            Log.w(TAG, "searchNetDevice threw", it)
            close(it)
            return@callbackFlow
        }

        // No completion callback exists, so the window is ours to close.
        val timer = launch {
            delay(LAN_SEARCH_WINDOW_MS)
            close()
        }

        awaitClose {
            timer.cancel()
            runCatching { udp.closeNetSocket() }
        }
    }

    private fun usbDevices(context: Context): Flow<DiscoveredPrinter> = flow {
        VolcoraManager.usbDevicePaths(context).forEach { path ->
            emit(
                DiscoveredPrinter(
                    brand = PrinterBrand.VOLCORA,
                    transport = TransportType.USB,
                    identifier = path,
                    name = "USB printer",
                    model = null,
                    detail = path,
                )
            )
        }
    }

    private fun bondedDevices(context: Context): Flow<DiscoveredPrinter> = flow {
        BluetoothLink.bondedDevices(context).forEach { device ->
            val name = runCatching { device.name }.getOrNull() ?: device.address
            emit(
                DiscoveredPrinter(
                    brand = PrinterBrand.VOLCORA,
                    transport = TransportType.BLUETOOTH,
                    identifier = device.address,
                    name = name,
                    model = name,
                    detail = device.address,
                )
            )
        }
    }
}

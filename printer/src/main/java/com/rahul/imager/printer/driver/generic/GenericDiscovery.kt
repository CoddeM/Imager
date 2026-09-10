package com.rahul.imager.printer.driver.generic

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.DEFAULT_LAN_PORT
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.UsbHostPermission
import com.rahul.imager.printer.driver.registry.PrinterDriverRegistry
import com.rahul.imager.printer.driver.registry.PrinterRouter
import com.rahul.imager.printer.transport.UsbVendorId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Discovery for printers that need no vendor SDK.
 *
 * Each transport is discovered the only way it honestly can be:
 *
 *  * BLUETOOTH — the OS bonded-device list. Classic Bluetooth can only reach bonded devices
 *    anyway, so scanning for unbonded ones would offer the user printers that cannot be printed
 *    to without pairing first.
 *  * USB — the attached device list, minus every VID that belongs to a family with its own native
 *    driver. Without that exclusion a real Epson would be added as a generic printer and every
 *    subsequent job would try to reach it the wrong way.
 *  * LAN — a bounded TCP sweep of the local /24 on the RAW printing port. This is the same probe
 *    a print would make, so anything it finds is genuinely printable.
 */
object GenericDiscovery : PrinterDiscovery {

    private const val TAG = "GenericDiscovery"

    /** How long a single host is given to accept a connection during the sweep. */
    const val LAN_PROBE_TIMEOUT_MS = 350

    /** How many hosts are probed at once. */
    private const val LAN_PARALLELISM = 32

    override val brand: PrinterBrand = PrinterBrand.GENERIC_ESCPOS

    override val transports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    override fun isSupported(context: Context): Boolean = true

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        when (transport) {
            TransportType.BLUETOOTH -> bondedDevices(context)
            TransportType.USB -> usbDevices(context)
            TransportType.LAN -> lanSweep(context)
            TransportType.INNER -> flow { }
        }

    // -------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun bondedDevices(context: Context): Flow<DiscoveredPrinter> = flow {
        BluetoothLink.bondedDevices(context).forEach { device ->
            val name = runCatching { device.name }.getOrNull() ?: device.address
            emit(
                DiscoveredPrinter(
                    brand = PrinterBrand.GENERIC_ESCPOS,
                    transport = TransportType.BLUETOOTH,
                    identifier = device.address,
                    name = name,
                    model = name,
                    detail = device.address,
                )
            )
        }
    }

    private fun usbDevices(context: Context): Flow<DiscoveredPrinter> = flow {
        val nativeFamilies = PrinterDriverRegistry.availableFamilies(context)
        UsbHostPermission.attachedDevices(context).forEach { device ->
            val offerAs = usbBrandFor(device.vendorId, nativeFamilies) ?: return@forEach
            val name = runCatching { device.productName }.getOrNull()
                ?: runCatching { device.manufacturerName }.getOrNull()
                ?: device.deviceName
            emit(
                DiscoveredPrinter(
                    brand = offerAs,
                    transport = TransportType.USB,
                    identifier = device.deviceName,
                    name = name,
                    model = runCatching { device.productName }.getOrNull(),
                    detail = "VID 0x%04X · PID 0x%04X".format(device.vendorId, device.productId),
                )
            )
        }
    }

    /**
     * Which brand, if any, this scan should offer a USB device under.
     *
     * Three outcomes, and the middle one is the reason this function exists:
     *
     *  * an unclaimed VID is a third-party ESC/POS printer — offer it as generic;
     *  * a VID whose family has a driver in this build belongs to THAT family's scan, not this one;
     *  * a VID whose family has no driver here, but which speaks ESC/POS over the wire, is offered
     *    under its real brand so the user can still add it. Before this, such a device was hidden
     *    by both scans at once and could not be added at all.
     *
     * Anything else — a family that is unavailable for a reason ESC/POS cannot fix, such as Star
     * below API 26 — is left alone rather than driven with a command set it may not accept.
     */
    internal fun usbBrandFor(vendorId: Int, nativeFamilies: Set<PrinterBrand>): PrinterBrand? {
        val family = UsbVendorId.familyForVid(vendorId) ?: return PrinterBrand.GENERIC_ESCPOS
        return when {
            family in nativeFamilies -> null
            family in PrinterRouter.ESCPOS_COMPATIBLE_FAMILIES -> family
            else -> null
        }
    }

    /**
     * Sweeps the local /24 for hosts that accept a connection on port 9100.
     *
     * Bounded on every axis: one subnet, a short per-host timeout, and a fixed parallelism, so the
     * scan finishes in a couple of seconds and cannot hammer the network.
     */
    private fun lanSweep(context: Context): Flow<DiscoveredPrinter> = callbackFlow {
        val prefix = localSubnetPrefix(context)
        if (prefix == null) {
            Log.i(TAG, "No IPv4 address on this device; skipping the LAN sweep")
            close()
            return@callbackFlow
        }

        val job = launch(Dispatchers.IO) {
            coroutineScope {
                val hosts = (1..254).toList()
                hosts.chunked(hosts.size / LAN_PARALLELISM + 1).forEach { chunk ->
                    launch {
                        chunk.forEach { host ->
                            val address = "$prefix.$host"
                            if (probe(address)) {
                                trySend(
                                    DiscoveredPrinter(
                                        brand = PrinterBrand.GENERIC_ESCPOS,
                                        transport = TransportType.LAN,
                                        identifier = address,
                                        name = hostName(address) ?: address,
                                        model = null,
                                        port = DEFAULT_LAN_PORT,
                                        detail = "$address:$DEFAULT_LAN_PORT",
                                    )
                                )
                            }
                        }
                    }
                }
            }
            close()
        }

        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    /** True when [address] accepts a TCP connection on the RAW printing port. */
    private fun probe(address: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, DEFAULT_LAN_PORT), LAN_PROBE_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun hostName(address: String): String? = runCatching {
        InetAddress.getByName(address).canonicalHostName.takeIf { it != address }
    }.getOrNull()

    /**
     * The first three octets of this device's IPv4 address, e.g. `192.168.1`.
     *
     * Read from the network interfaces rather than from `WifiManager`, so the sweep also works on
     * Ethernet-attached terminals, which is exactly the hardware that has LAN printers.
     */
    private fun localSubnetPrefix(context: Context): String? {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { networkInterface ->
                if (networkInterface.isLoopback || !networkInterface.isUp) return@forEach
                networkInterface.inetAddresses.toList().forEach { address ->
                    val host = address.hostAddress ?: return@forEach
                    if (!address.isLoopbackAddress && host.count { it == '.' } == 3) {
                        return host.substringBeforeLast('.')
                    }
                }
            }
        }.onFailure { Log.d(TAG, "Cannot enumerate network interfaces: ${it.message}") }

        // Fall back to the Wi-Fi service on devices that hide their interfaces.
        return runCatching {
            @Suppress("DEPRECATION")
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) {
                null
            } else {
                "%d.%d.%d".format(ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF)
            }
        }.getOrNull()
    }
}

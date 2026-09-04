package com.rahul.imager.printer.driver.volcora

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.UsbVendorId
import net.posprinter.IConnectListener
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import net.posprinter.POSConst
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared state for the Volcora / Xprinter-OEM family (`net.posprinter`).
 *
 * Two SDK behaviours shape everything here:
 *
 *  * `POSConnect.init` must be called exactly once, before ANY connect or discovery;
 *  * the connect listener OUTLIVES the connect. It keeps firing for the life of the connection,
 *    which is how a mid-print disconnect becomes observable at all, so the listener is kept and
 *    used to evict the cached connection on interrupt or detach.
 */
object VolcoraManager {

    private const val TAG = "VolcoraManager"

    const val CONNECT_TIMEOUT_MS = 10_000L

    /** A head that has not answered a status probe in three seconds does not answer them. */
    const val STATUS_TIMEOUT_MS = 3_000L

    @Volatile
    private var initialized = false

    /** Initializes the SDK exactly once, whatever thread races in first. */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching { POSConnect.init(context.applicationContext) }
                .onFailure { Log.w(TAG, "POSConnect.init failed", it) }
            initialized = true
        }
    }

    private val connections = ConcurrentHashMap<String, IDeviceConnection>()

    /** Set by the connect listener when the SDK reports a failed write. */
    private val sendFailures = ConcurrentHashMap<String, AtomicBoolean>()

    fun cached(key: String): IDeviceConnection? = connections[key]

    fun put(key: String, connection: IDeviceConnection) {
        connections[key] = connection
        sendFailures[key] = AtomicBoolean(false)
    }

    /** True when the SDK has reported a `SEND_FAIL` on [key] since [clearSendFailure]. */
    fun sendFailed(key: String): Boolean = sendFailures[key]?.get() == true

    fun clearSendFailure(key: String) {
        sendFailures[key]?.set(false)
    }

    /** Drops and closes a cached connection. Safe from any thread, never suspends. */
    fun forceClose(key: String) {
        connections.remove(key)?.let { connection ->
            runCatching { connection.close() }
                .onFailure { Log.d(TAG, "close($key) threw: ${it.message}") }
        }
        sendFailures.remove(key)
    }

    /** Maps the app's transport onto the SDK's device-type constant. */
    fun deviceType(transport: TransportType): Int? = when (transport) {
        TransportType.LAN -> POSConnect.DEVICE_TYPE_ETHERNET
        TransportType.BLUETOOTH -> POSConnect.DEVICE_TYPE_BLUETOOTH
        TransportType.USB -> POSConnect.DEVICE_TYPE_USB
        TransportType.INNER -> null
    }

    /**
     * Builds the long-lived connect listener for one connection.
     *
     * Every terminal code evicts the cached connection: a connection the SDK has told us is gone
     * must never be handed to the next job, because it would report connected and then quietly
     * discard everything written to it.
     */
    fun listenerFor(key: String, onStatus: (Int, String?) -> Unit): IConnectListener =
        IConnectListener { code, connInfo, message ->
            when (code) {
                POSConnect.CONNECT_SUCCESS -> Log.i(TAG, "Connected: $connInfo")

                POSConnect.CONNECT_FAIL,
                POSConnect.CONNECT_INTERRUPT,
                POSConnect.BLUETOOTH_INTERRUPT,
                POSConnect.USB_DETACHED,
                -> {
                    Log.w(TAG, "Connection lost ($code) on $key: $message")
                    forceClose(key)
                }

                POSConnect.SEND_FAIL -> {
                    Log.w(TAG, "SEND_FAIL on $key: $message")
                    sendFailures[key]?.set(true)
                }
            }
            onStatus(code, message)
        }

    /** Maps a `POSConst.STS_*` status token onto the app's taxonomy. */
    fun categoryForStatus(status: Int): PrintCategory? = when (status) {
        POSConst.STS_NORMAL -> null
        POSConst.STS_COVEROPEN -> PrintCategory.COVER_OPEN
        POSConst.STS_PAPEREMPTY -> PrintCategory.PAPER_OUT
        POSConst.STS_PRESS_FEED -> null
        POSConst.STS_PRINTER_ERR -> PrintCategory.SEND_FAILED
        else -> null
    }

    /** Human-readable status token for diagnostics. */
    fun statusName(status: Int): String = when (status) {
        POSConst.STS_NORMAL -> "normal"
        POSConst.STS_COVEROPEN -> "cover_open"
        POSConst.STS_PAPEREMPTY -> "out_of_paper"
        POSConst.STS_PRESS_FEED -> "feed_pressed"
        POSConst.STS_PRINTER_ERR -> "printer_error"
        else -> "status_$status"
    }

    /**
     * Enumerates the USB devices this family may claim.
     *
     * Devices whose VID belongs to a family with a NATIVE driver are excluded, and that exclusion
     * is mandatory: without it a genuine Epson gets added under the Volcora brand, and every
     * subsequent job dials TCP to a USB device path and fails in a way nothing explains.
     */
    fun usbDevicePaths(context: Context): List<String> {
        ensureInitialized(context)
        val claimed = claimedUsbDevicePaths(context)
        return runCatching { POSConnect.getUsbDevice(context.applicationContext).orEmpty() }
            .getOrElse {
                Log.w(TAG, "getUsbDevice failed", it)
                emptyList()
            }
            .filterNotNull()
            .filter { it !in claimed }
    }

    /** Device paths belonging to VIDs another driver family owns. */
    private fun claimedUsbDevicePaths(context: Context): Set<String> {
        val manager = context.getSystemService(Context.USB_SERVICE)
            as? android.hardware.usb.UsbManager ?: return emptySet()
        return manager.deviceList.values
            .filter { it.vendorId in UsbVendorId.KNOWN_PRINTER_VENDORS }
            .map { it.deviceName }
            .toSet()
    }
}

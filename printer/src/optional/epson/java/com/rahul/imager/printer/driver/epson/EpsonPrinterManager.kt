package com.rahul.imager.printer.driver.epson

import android.content.Context
import android.util.Log
import com.epson.epos2.Epos2Exception
import com.epson.epos2.printer.Printer
import com.rahul.imager.printer.domain.TransportType
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared state for the Epson ePOS2 family.
 *
 * The SDK serializes connect and disconnect PROCESS-WIDE, so this app does too: one global mutex
 * rather than one per printer. Trying to be cleverer only produces `ERR_PROCESSING` storms.
 */
object EpsonPrinterManager {

    private const val TAG = "EpsonPrinterManager"

    const val CONNECT_TIMEOUT_MS = 15_000
    const val SEND_TIMEOUT_MS = 30_000

    /** The brightness the reference receipts print at, used as the neutral Darkness setting. */
    const val NEUTRAL_BRIGHTNESS = 0.10

    /** Global lock: the SDK's own connect/disconnect path is process-wide. */
    val globalMutex = Mutex()

    /**
     * The last USB target string that actually worked, per saved identity.
     *
     * A USB target (`USB:...`) is only valid while the device stays plugged into the same port, so
     * a cached one is dropped as soon as a connect fails and re-resolved through discovery.
     */
    private val usbTargets = ConcurrentHashMap<String, String>()

    fun cachedUsbTarget(identity: String): String? = usbTargets[identity]

    fun rememberUsbTarget(identity: String, target: String) {
        usbTargets[identity] = target
    }

    fun dropUsbTarget(identity: String) {
        usbTargets.remove(identity)
    }

    /**
     * Builds the connect target string.
     *
     * ePOS2 targets are PREFIXED by transport, and getting the prefix wrong is silently fatal:
     * the SDK reports "not found" rather than "wrong kind of address".
     */
    fun target(transport: TransportType, identifier: String, cachedUsb: String?): String? =
        when (transport) {
            TransportType.LAN -> "TCP:${identifier.trim()}"
            TransportType.BLUETOOTH -> "BT:${identifier.trim().uppercase()}"
            // USB targets cannot be constructed from a saved address; they are resolved live.
            TransportType.USB -> cachedUsb
            TransportType.INNER -> null
        }

    /** Printer series constant for a model string, defaulting to the ubiquitous TM-T88. */
    fun seriesFor(model: String?): Int {
        val name = model.orEmpty().lowercase()
        return when {
            name.contains("t88") -> Printer.TM_T88
            name.contains("t20") -> Printer.TM_T20
            name.contains("t70") -> Printer.TM_T70
            name.contains("t82") -> Printer.TM_T82
            name.contains("m10") -> Printer.TM_M10
            name.contains("m30") -> Printer.TM_M30
            name.contains("m50") -> Printer.TM_M50
            name.contains("p20") -> Printer.TM_P20
            name.contains("p60") -> Printer.TM_P60
            name.contains("p80") -> Printer.TM_P80
            name.contains("l90") -> Printer.TM_L90
            name.contains("u220") -> Printer.TM_U220
            else -> Printer.TM_T88
        }
    }

    /**
     * Stops discovery, retrying while the SDK says it is still busy.
     *
     * `Discovery.stop()` throws `ERR_PROCESSING` when a scan is still winding down. Any other
     * error means it is genuinely stopped (or unstoppable) and ends the loop.
     */
    fun stopDiscoveryBlocking(stop: () -> Unit) {
        var attempts = 0
        while (attempts < MAX_STOP_ATTEMPTS) {
            attempts++
            try {
                stop()
                return
            } catch (e: Epos2Exception) {
                if (e.errorStatus != Epos2Exception.ERR_PROCESSING) {
                    Log.d(TAG, "Discovery.stop() ended with ${e.errorStatus}")
                    return
                }
                Thread.sleep(STOP_RETRY_DELAY_MS)
            } catch (e: Exception) {
                Log.d(TAG, "Discovery.stop() threw: ${e.message}")
                return
            }
        }
        Log.w(TAG, "Discovery.stop() never settled after $attempts attempts")
    }

    private const val MAX_STOP_ATTEMPTS = 50
    private const val STOP_RETRY_DELAY_MS = 100L
}

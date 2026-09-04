package com.rahul.imager.printer.driver.star

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.rahul.imager.printer.domain.TransportType
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarPrinter
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the StarIO10 handles.
 *
 * Two things about this SDK drive the design:
 *
 *  * `StarPrinter` is NOT safe for concurrent operations against one physical printer. A second
 *    overlapping operation throws `StarIO10InUseException`, so every job for a given identifier is
 *    serialized on a per-identifier [Mutex].
 *  * The SDK's minimum API level is 26, above this app's 24. The whole family is therefore gated
 *    behind [isSupported] and reports itself unavailable on older devices rather than crashing
 *    with a `NoClassDefFoundError`.
 */
object StarPrinterManager {

    private const val TAG = "StarPrinterManager"

    /** StarIO10 declares minSdk 26; the app supports 24. */
    const val MIN_SDK: Int = Build.VERSION_CODES.O

    const val OPEN_TIMEOUT_MS = 15_000
    const val PRINT_TIMEOUT_MS = 30_000

    /** True when this device can load the Star SDK at all. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK

    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * The mutex guarding one physical printer.
     *
     * Keyed by the normalized identifier so a printer saved twice (say once by IP and once by
     * hostname) still serializes onto one lock.
     */
    fun mutexFor(identifier: String): Mutex =
        mutexes.getOrPut(normalizeIdentifier(identifier)) { Mutex() }

    /**
     * Star Bluetooth identifiers are sometimes handed back with a `BT:` prefix by the SDK's own
     * discovery. It is not part of the address and must be stripped before use.
     */
    fun normalizeIdentifier(identifier: String): String =
        identifier.removePrefix("BT:").trim()

    /** Maps the app's transport onto the SDK's interface enum. */
    @RequiresApi(MIN_SDK)
    fun interfaceType(transport: TransportType): InterfaceType = when (transport) {
        TransportType.BLUETOOTH -> InterfaceType.Bluetooth
        TransportType.LAN -> InterfaceType.Lan
        TransportType.USB -> InterfaceType.Usb
        TransportType.INNER -> InterfaceType.Unknown
    }

    /**
     * Builds a printer handle with this app's timeouts applied.
     *
     * A handle is created per job rather than cached: the SDK ties its internal state to the open
     * connection, and a stale cached handle is far harder to recover from than a fresh open.
     */
    @RequiresApi(MIN_SDK)
    fun createPrinter(
        context: Context,
        transport: TransportType,
        identifier: String,
    ): StarPrinter {
        val settings = StarConnectionSettings(
            interfaceType(transport),
            normalizeIdentifier(identifier),
        )
        return StarPrinter(settings, context.applicationContext).apply {
            openTimeout = OPEN_TIMEOUT_MS
            printTimeout = PRINT_TIMEOUT_MS
            getStatusTimeout = OPEN_TIMEOUT_MS
        }
    }

    /** Records a handle so [forceClose] can reach it from outside the print coroutine. */
    private val open = ConcurrentHashMap<String, StarPrinter>()

    @RequiresApi(MIN_SDK)
    fun register(identifier: String, printer: StarPrinter) {
        open[normalizeIdentifier(identifier)] = printer
    }

    fun unregister(identifier: String) {
        open.remove(normalizeIdentifier(identifier))
    }

    /**
     * Synchronously abandons the handle for [identifier].
     *
     * `closeAsync` is a suspending SDK call and cannot be awaited here, so this fires it and
     * forgets: the point is only to stop the app holding a printer the engine has given up on.
     */
    fun forceClose(identifier: String) {
        val printer = open.remove(normalizeIdentifier(identifier)) ?: return
        runCatching { printer.closeAsync() }
            .onFailure { Log.d(TAG, "forceClose($identifier) threw: ${it.message}") }
    }
}

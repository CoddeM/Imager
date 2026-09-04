package com.rahul.imager.printer.driver.star

import android.content.Context
import android.os.Build
import android.util.Log
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.BluetoothLink
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarPrinter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Star device discovery.
 *
 * The SDK owns the scan, so this is a thin `callbackFlow` bridge. Two SDK details shape it:
 *
 *  * only ONE discovery manager may be alive at a time, and it must be stopped before another is
 *    created — hence the single instance held here and the stop in `awaitClose`;
 *  * `discoveryTime` is rejected below one second, so the default window is a full ten.
 */
object StarDiscovery : PrinterDiscovery {

    private const val TAG = "StarDiscovery"

    /** SDK default discovery window. Values under 1000 ms are rejected outright. */
    const val DISCOVERY_TIME_MS = 10_000

    override val brand: PrinterBrand = PrinterBrand.STAR

    override val transports: Set<TransportType> =
        setOf(TransportType.LAN, TransportType.BLUETOOTH, TransportType.USB)

    /** The one live manager. Held so a restart can stop the previous scan first. */
    @Volatile
    private var manager: StarDeviceDiscoveryManager? = null

    override fun isSupported(context: Context): Boolean = StarPrinterManager.isSupported

    override fun discover(context: Context, transport: TransportType): Flow<DiscoveredPrinter> =
        callbackFlow {
            if (Build.VERSION.SDK_INT < StarPrinterManager.MIN_SDK) {
                close()
                return@callbackFlow
            }
            if (transport == TransportType.BLUETOOTH && !BluetoothLink.hasScanPermission(context)) {
                close()
                return@callbackFlow
            }

            // Stop whatever was running: two live managers make the SDK drop results silently.
            runCatching { manager?.stopDiscovery() }
            manager = null

            val created = runCatching {
                StarDeviceDiscoveryManagerFactory.create(
                    listOf(StarPrinterManager.interfaceType(transport)),
                    context.applicationContext,
                )
            }.getOrElse {
                Log.w(TAG, "Could not create a Star discovery manager", it)
                close()
                return@callbackFlow
            }

            created.discoveryTime = DISCOVERY_TIME_MS
            created.callback = object : StarDeviceDiscoveryManager.Callback {
                override fun onPrinterFound(printer: StarPrinter) {
                    trySend(printer.toDiscovered(transport))
                }

                override fun onDiscoveryFinished() {
                    close()
                }
            }
            manager = created

            runCatching { created.startDiscovery() }.onFailure {
                Log.w(TAG, "startDiscovery failed", it)
                close(it)
            }

            awaitClose {
                runCatching { created.stopDiscovery() }
                if (manager === created) manager = null
            }
        }

    private fun StarPrinter.toDiscovered(transport: TransportType): DiscoveredPrinter {
        val identifier = StarPrinterManager.normalizeIdentifier(connectionSettings.identifier)
        val model = runCatching { information?.model?.name }.getOrNull()
        return DiscoveredPrinter(
            brand = PrinterBrand.STAR,
            transport = transport,
            identifier = identifier,
            name = model ?: "Star printer",
            model = model,
            detail = identifier,
        )
    }

    /**
     * Recognises a Star device from a name alone, for the transports where that is all we get.
     *
     * Also used by the add-printer flow to pre-select the Star family when the user types or
     * scans a name that is unmistakably Star hardware.
     */
    val MODEL_PATTERN: Regex = Regex(
        "(?i:STAR)|TSP\\d|mC-|mCP\\d|MCL\\d|SM-[SLT]\\d|SP\\d{3}|BSC\\d|mPOP",
    )

    /** True when [name] identifies Star hardware. */
    fun looksLikeStar(name: String?): Boolean =
        !name.isNullOrBlank() && MODEL_PATTERN.containsMatchIn(name)
}

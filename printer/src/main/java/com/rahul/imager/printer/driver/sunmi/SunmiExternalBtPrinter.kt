package com.rahul.imager.printer.driver.sunmi

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.escpos.EscPos
import com.rahul.imager.printer.raster.Alignment
import com.rahul.imager.printer.raster.RasterJob
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.BtReadiness
import com.sunmi.externalprinterlibrary.api.ConnectCallback
import com.sunmi.externalprinterlibrary.api.SunmiPrinter
import com.sunmi.externalprinterlibrary.api.SunmiPrinterApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Serializes access to `SunmiPrinterApi`.
 *
 * The API is a PROCESS-WIDE SINGLETON: `setPrinter` mutates global state, so two printers cannot
 * be driven at once and even switching between them needs a settling delay. One global mutex is
 * therefore the honest model, not one per MAC.
 */
object SunmiExternalBtManager {

    private const val TAG = "SunmiExternalBt"

    const val CONNECT_TIMEOUT_MS = 15_000L

    /** The API needs a moment to let go of the previous printer before it accepts a new one. */
    const val PRINTER_SWITCH_DELAY_MS = 300L

    /** After a completed print the firmware needs a moment before the link is touched again. */
    const val PRINT_SETTLE_DELAY_MS = 400L

    /** The buffer has to drain before a cut, or the cut lands in the middle of the image. */
    const val BUFFER_FLUSH_BEFORE_CUT_MS = 150L

    private val mutex = Mutex()

    @Volatile
    private var currentMac: String? = null

    /** Runs [block] with exclusive access to the singleton API. */
    suspend fun <T> withApi(mac: String, block: suspend (SunmiPrinterApi) -> T): T =
        mutex.withLock {
            val api = SunmiPrinterApi.getInstance()
            if (currentMac != null && !currentMac.equals(mac, ignoreCase = true)) {
                runCatching { api.disconnectPrinter(null) }
                delay(PRINTER_SWITCH_DELAY_MS)
            }
            currentMac = mac
            block(api)
        }

    /** Drops the remembered selection so the next job re-selects from scratch. */
    fun forget(mac: String) {
        if (currentMac.equals(mac, ignoreCase = true)) currentMac = null
    }

    /** Synchronously abandons the connection. Safe from any thread. */
    fun forceClose(context: Context?) {
        runCatching { SunmiPrinterApi.getInstance().disconnectPrinter(context) }
            .onFailure { Log.d(TAG, "disconnectPrinter threw: ${it.message}") }
        currentMac = null
    }
}

/**
 * Sunmi's external BLUETOOTH printers, driven through `com.sunmi:external-printerlibrary`.
 *
 * Sunmi's own Bluetooth units speak a proprietary flavour of ESC/POS that the generic driver
 * cannot fully address, which is why they get their own family rather than being folded into
 * [com.rahul.imager.printer.driver.generic.GenericEscPosPrinter].
 */
class SunmiExternalBtPrinter(
    override val displayName: String,
    private val macAddress: String,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.SUNMI
    override val transport: TransportType = TransportType.BLUETOOTH

    private val mac: String get() = BluetoothLink.normalizeAddress(macAddress)

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, mac)

    override suspend fun isAvailable(context: Context?): Boolean {
        val ctx = context ?: return false
        return macAddress.isNotBlank() &&
            BluetoothLink.hasConnectPermission(ctx) &&
            BluetoothLink.isEnabled(ctx)
    }

    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        when (BluetoothLink.ensureReady(ctx, macAddress)) {
            is BtReadiness.NotReady -> false
            BtReadiness.Ready -> SunmiExternalBtManager.withApi(mac) { api -> open(ctx, api) }
        }
    }

    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext PrintError(
            PrintCategory.CONNECT_FAILED,
            printerContext,
            detail = "No Context available for the Sunmi Bluetooth SDK.",
        )

        // The bond gate first: an unpaired printer connects and then silently prints nothing.
        when (val readiness = BluetoothLink.ensureReady(ctx, macAddress)) {
            is BtReadiness.NotReady -> return@withContext PrintError(
                readiness.category,
                printerContext,
                detail = readiness.detail,
            )

            BtReadiness.Ready -> Unit
        }

        SunmiExternalBtManager.withApi(mac) { api ->
            if (!open(ctx, api)) {
                return@withApi PrintError(
                    PrintCategory.BT_UNREACHABLE,
                    printerContext,
                    detail = "The Sunmi SDK did not connect to $mac within " +
                        "${SunmiExternalBtManager.CONNECT_TIMEOUT_MS / 1000}s.",
                )
            }

            var committedToPaper = false
            try {
                api.printerInit()
                api.setAlignMode(job.options.alignment.toSunmi())

                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()
                    api.printBitmap(job.bandBitmap(band), 0)
                    committedToPaper = true
                    onProgress((index + 1).toFloat() / bands.size)
                }

                api.lineWrap(job.options.feedLinesAfter)
                if (job.options.cutAfter && job.paper.supportsCutter) {
                    // Let the image drain before cutting, or the cut lands mid-picture.
                    delay(SunmiExternalBtManager.BUFFER_FLUSH_BEFORE_CUT_MS)
                    runCatching { api.sendRawData(EscPos.PARTIAL_CUT) }
                }
                delay(SunmiExternalBtManager.PRINT_SETTLE_DELAY_MS)
                null
            } catch (e: CancellationException) {
                SunmiExternalBtManager.forceClose(ctx)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sunmi Bluetooth print failed for $mac", e)
                val status = runCatching { api.printerStatus }.getOrNull()
                val refined = status?.let { SunmiErrorMapper.fromExternalStatus(it) }
                PrintError(
                    category = refined
                        ?: if (committedToPaper) {
                            PrintCategory.PARTIAL_PRINT
                        } else {
                            PrintCategory.SEND_FAILED
                        },
                    context = printerContext.copy(
                        vendorErrorCode = status?.let { SunmiErrorMapper.externalStatusName(it) },
                    ),
                    cause = e,
                    detail = e.message,
                )
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        runCatching { SunmiPrinterApi.getInstance().disconnectPrinter(context) }
        SunmiExternalBtManager.forget(mac)
        true
    }

    override fun forceClose() {
        SunmiExternalBtManager.forceClose(null)
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val status = runCatching { SunmiPrinterApi.getInstance().printerStatus }.getOrNull()
                ?: return@withContext null
            PrinterStatus(
                online = SunmiErrorMapper.fromExternalStatus(status) == null,
                paperOut = status == com.sunmi.externalprinterlibrary.api.Status.OUTPAPER,
                paperLow = status == com.sunmi.externalprinterlibrary.api.Status.NEAROUTPAPER,
                coverOpen = status == com.sunmi.externalprinterlibrary.api.Status.COVER,
                overheat = status == com.sunmi.externalprinterlibrary.api.Status.OVERHOT,
                busy = status == com.sunmi.externalprinterlibrary.api.Status.RUNNING,
                rawVendorCode = SunmiErrorMapper.externalStatusName(status),
            )
        }

    /** Selects and connects the printer, bounded by [SunmiExternalBtManager.CONNECT_TIMEOUT_MS]. */
    private suspend fun open(context: Context, api: SunmiPrinterApi): Boolean {
        if (runCatching { api.isConnected }.getOrDefault(false)) return true
        return withTimeoutOrNull(SunmiExternalBtManager.CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                fun finish(success: Boolean) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(success)
                    }
                }

                val callback = object : ConnectCallback {
                    override fun onFound() = Unit
                    override fun onUnfound() = finish(false)
                    override fun onConnect() = finish(true)
                    override fun onDisconnect() = finish(false)
                }

                runCatching {
                    api.setPrinter(SunmiPrinter.SunmiBlueToothPrinter, mac)
                    api.connectPrinter(context.applicationContext, callback)
                }.onFailure {
                    Log.w(TAG, "Sunmi connectPrinter threw for $mac", it)
                    finish(false)
                }
            }
        } ?: false
    }

    private fun Alignment.toSunmi(): Int = when (this) {
        Alignment.LEFT -> 0
        Alignment.CENTER -> 1
        Alignment.RIGHT -> 2
    }

    private companion object {
        const val TAG = "SunmiExternalBtPrinter"
    }
}

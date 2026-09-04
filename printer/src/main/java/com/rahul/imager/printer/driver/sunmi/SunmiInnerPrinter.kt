package com.rahul.imager.printer.driver.sunmi

import android.content.Context
import android.os.Build
import android.os.RemoteException
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.Alignment
import com.rahul.imager.printer.raster.RasterJob
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Holds the binding to the Sunmi built-in printer service.
 *
 * The service is bound once and kept: rebinding per job costs a second and, on some firmware,
 * intermittently fails. The binding is torn down only when the app explicitly disconnects.
 */
object SunmiInnerManager {

    private const val TAG = "SunmiInner"

    /** A device without the print service never calls back, so binding must be bounded. */
    const val BIND_TIMEOUT_MS = 15_000L

    @Volatile
    private var service: SunmiPrinterService? = null

    @Volatile
    private var callback: InnerPrinterCallback? = null

    /** True when the app runs on Sunmi hardware, the only place this family exists. */
    val isSunmiDevice: Boolean
        get() = Build.MANUFACTURER?.contains("sunmi", ignoreCase = true) == true ||
            Build.BRAND?.contains("sunmi", ignoreCase = true) == true

    /** The bound service, or null. */
    fun boundService(): SunmiPrinterService? = service

    /**
     * Binds the printer service, or returns null.
     *
     * Wrapped in a timeout because `bindService` on a device WITHOUT the Sunmi print service
     * simply never fires either callback — the coroutine would otherwise hang for ever.
     */
    suspend fun bind(context: Context): SunmiPrinterService? {
        service?.let { return it }
        val appContext = context.applicationContext
        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val innerCallback = object : InnerPrinterCallback() {
                    override fun onConnected(printerService: SunmiPrinterService) {
                        service = printerService
                        if (resumed.compareAndSet(false, true)) {
                            continuation.resume(printerService)
                        }
                    }

                    override fun onDisconnected() {
                        service = null
                        if (resumed.compareAndSet(false, true)) continuation.resume(null)
                    }
                }
                callback = innerCallback

                val started = runCatching {
                    InnerPrinterManager.getInstance().bindService(appContext, innerCallback)
                }.getOrElse {
                    Log.w(TAG, "bindService threw", it)
                    false
                }
                if (!started && resumed.compareAndSet(false, true)) continuation.resume(null)
            }
        }
    }

    /** Releases the binding. */
    fun unbind(context: Context) {
        val cb = callback ?: return
        runCatching { InnerPrinterManager.getInstance().unBindService(context.applicationContext, cb) }
            .onFailure { Log.d(TAG, "unBindService threw: ${it.message}") }
        callback = null
        service = null
    }

    /** Reads the printer state, or `null` when the service cannot answer. */
    fun printerState(): Int? =
        runCatching { service?.updatePrinterState() }.getOrNull()
}

/**
 * The Sunmi built-in (INNER) printer, driven through the AIDL print service.
 *
 * There is no address and no socket: the head is wired into the terminal and reached through a
 * bound system service, so [forceClose] has nothing to do and the default no-op is correct.
 */
class SunmiInnerPrinter(
    override val displayName: String,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.SUNMI
    override val transport: TransportType = TransportType.INNER

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, "inner")

    override suspend fun isAvailable(context: Context?): Boolean {
        if (!SunmiInnerManager.isSunmiDevice) return false
        val ctx = context ?: return false
        return SunmiInnerManager.bind(ctx) != null
    }

    override suspend fun connect(context: Context?): Boolean {
        val ctx = context ?: return false
        return SunmiInnerManager.bind(ctx) != null
    }

    /**
     * Prints through the service's transaction buffer.
     *
     * Two hard-won details live in here.
     *
     *  1. WHICH callback reports the commit varies by device and firmware. Some units fire
     *     `onRunResult(isSuccess)`, others report ONLY through `onPrintResult(code, msg)` with
     *     code 0 for success. Listening to `onRunResult` alone leaves the coroutine suspended for
     *     ever on the second kind of device, so this resumes on WHICHEVER fires first — and on
     *     `onRaiseException` as a failure.
     *  2. On any abort, the buffer is discarded with `exitPrinterBuffer(false)`. Committing it
     *     (`true`) would push a half-built job onto paper.
     */
    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext PrintError(
            PrintCategory.SERVICE_NOT_BOUND,
            printerContext,
            detail = "No Context available to bind the Sunmi print service.",
        )
        val service = SunmiInnerManager.bind(ctx) ?: return@withContext PrintError(
            PrintCategory.SERVICE_NOT_BOUND,
            printerContext,
            detail = "The Sunmi print service did not bind within " +
                "${SunmiInnerManager.BIND_TIMEOUT_MS / 1000}s.",
        )

        try {
            service.enterPrinterBuffer(true)

            val bands = job.bands
            bands.forEachIndexed { index, band ->
                currentCoroutineContext().ensureActive()
                service.setAlignment(job.options.alignment.toSunmi(), null)
                service.printBitmap(job.bandBitmap(band), null)
                onProgress((index + 1).toFloat() / bands.size)
            }

            val committed = commitBuffer(service)
            if (committed) {
                null
            } else {
                refine(
                    PrintError(
                        PrintCategory.SEND_FAILED,
                        printerContext,
                        detail = "The print service reported the commit as failed.",
                    )
                )
            }
        } catch (e: CancellationException) {
            discardBuffer(service)
            throw e
        } catch (e: RemoteException) {
            discardBuffer(service)
            Log.w(TAG, "Sunmi inner printer binder died mid-print", e)
            refine(
                PrintError(
                    PrintCategory.DISCONNECTED_MID_PRINT,
                    printerContext,
                    cause = e,
                    detail = e.message,
                )
            )
        } catch (e: Exception) {
            discardBuffer(service)
            Log.w(TAG, "Sunmi inner print failed", e)
            refine(
                PrintError(PrintCategory.SEND_FAILED, printerContext, cause = e, detail = e.message)
            )
        }
    }

    override suspend fun disconnect(context: Context?): Boolean {
        context?.let { SunmiInnerManager.unbind(it) }
        return true
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? {
        val ctx = context ?: return null
        SunmiInnerManager.bind(ctx) ?: return null
        val state = SunmiInnerManager.printerState() ?: return null
        return PrinterStatus(
            online = state == SunmiErrorMapper.INNER_STATE_NORMAL,
            paperOut = state == SunmiErrorMapper.INNER_STATE_OUT_OF_PAPER,
            coverOpen = state == SunmiErrorMapper.INNER_STATE_COVER_OPEN,
            overheat = state == SunmiErrorMapper.INNER_STATE_OVERHEATED,
            busy = state == SunmiErrorMapper.INNER_STATE_PREPARING,
            rawVendorCode = "${SunmiErrorMapper.innerStateName(state)}($state)",
        )
    }

    /**
     * Commits the transaction buffer and waits for the device to confirm.
     *
     * @return true when the device reported success.
     */
    private suspend fun commitBuffer(service: SunmiPrinterService): Boolean =
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(success: Boolean) {
                if (resumed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(success)
                }
            }

            val callback = object : InnerResultCallback() {
                override fun onRunResult(isSuccess: Boolean) = finish(isSuccess)

                override fun onReturnString(result: String?) = Unit

                override fun onRaiseException(code: Int, msg: String?) {
                    Log.w(TAG, "Sunmi inner exception $code: $msg")
                    finish(false)
                }

                // Some firmware reports the commit ONLY here; code 0 means success.
                override fun onPrintResult(code: Int, msg: String?) = finish(code == 0)
            }

            runCatching { service.exitPrinterBufferWithCallback(true, callback) }
                .onFailure {
                    Log.w(TAG, "exitPrinterBufferWithCallback threw", it)
                    finish(false)
                }
        }

    /**
     * Throws the buffered job away.
     *
     * `false` DISCARDS the buffer; passing `true` here would commit whatever had been built up so
     * far and emit a partial slip. Guarded so a dead binder cannot mask the original exception.
     */
    private fun discardBuffer(service: SunmiPrinterService) {
        runCatching { service.exitPrinterBuffer(false) }
            .onFailure { Log.d(TAG, "exitPrinterBuffer(false) threw: ${it.message}") }
    }

    /**
     * Sharpens a generic failure using the printer's own state.
     *
     * Only queried ON FAILURE — polling the state during a healthy print costs a binder round trip
     * per band for no benefit.
     */
    private fun refine(error: PrintError): PrintError {
        val state = SunmiInnerManager.printerState() ?: return error
        val refined = SunmiErrorMapper.fromInnerState(state)
        val vendorCode = "${SunmiErrorMapper.innerStateName(state)}($state)"
        return error.copy(
            category = refined ?: error.category,
            context = error.context.copy(vendorErrorCode = vendorCode),
        )
    }

    private fun Alignment.toSunmi(): Int = when (this) {
        Alignment.LEFT -> 0
        Alignment.CENTER -> 1
        Alignment.RIGHT -> 2
    }

    private companion object {
        const val TAG = "SunmiInnerPrinter"
    }
}

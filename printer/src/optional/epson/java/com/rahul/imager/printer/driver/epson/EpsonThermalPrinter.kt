package com.rahul.imager.printer.driver.epson

import android.content.Context
import android.util.Log
import com.epson.epos2.printer.Printer
import com.epson.epos2.printer.PrinterStatusInfo
import com.epson.epos2.printer.ReceiveListener
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.Alignment
import com.rahul.imager.printer.raster.RasterJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Epson TM-series driver, built on ePOS2 (`ePOS2.jar` plus `libepos2.so`).
 *
 * This file only exists in builds where the SDK was dropped into `printer/libs/`; see
 * `printer/build.gradle.kts`. Nothing under `src/main` references `com.epson.*`.
 *
 * Epson exposes a REAL brightness parameter on `addImage`, so this is one of the two families
 * where the app's Darkness control maps onto a genuine hardware/SDK setting rather than onto the
 * tone curve.
 */
class EpsonThermalPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
    private val model: String?,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.EPSON

    /** Stable identity for the USB target cache; the saved address never changes. */
    private val identity: String = "${transport.name}:$identifier"

    @Volatile
    private var printer: Printer? = null

    /** Set from the SDK's callback thread, read from the print coroutine. */
    @Volatile
    private var lastStatus: PrinterStatusInfo? = null

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, identifier)

    override suspend fun isAvailable(context: Context?): Boolean =
        context != null && (transport == TransportType.USB || identifier.isNotBlank())

    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        EpsonPrinterManager.globalMutex.withLock {
            val handle = openPrinter(ctx) ?: return@withLock false
            runCatching { handle.disconnect() }
            true
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
            detail = "No Context available for the Epson SDK.",
        )

        EpsonPrinterManager.globalMutex.withLock {
            val handle = openPrinter(ctx) ?: return@withLock PrintError(
                category = if (transport == TransportType.USB) {
                    PrintCategory.USB_NOT_ENUMERATED
                } else {
                    PrintCategory.CONNECT_FAILED
                },
                context = printerContext,
                detail = "The Epson SDK could not connect to \"$identifier\".",
            )

            var committedToPaper = false
            var inTransaction = false
            try {
                handle.beginTransaction()
                inTransaction = true

                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()
                    val bandBitmap = job.bandBitmap(band)
                    handle.addTextAlign(job.options.alignment.toEpson())
                    handle.addImage(
                        bandBitmap,
                        0,
                        0,
                        bandBitmap.width,
                        bandBitmap.height,
                        Printer.COLOR_1,
                        Printer.MODE_MONO,
                        Printer.HALFTONE_DITHER,
                        brightnessFor(job),
                        Printer.COMPRESS_AUTO,
                    )
                    if (index == bands.lastIndex) {
                        handle.addFeedLine(job.options.feedLinesAfter)
                        if (job.options.cutAfter && job.paper.supportsCutter) {
                            runCatching { handle.addCut(Printer.CUT_FEED) }
                        }
                    } else {
                        handle.addFeedLine(1)
                    }

                    val sent = sendAndAwait(handle)
                    committedToPaper = true
                    if (sent != null) return@withLock sent
                    onProgress((index + 1).toFloat() / bands.size)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Epson print failed for $identifier", e)
                // A USB target goes stale across a replug; drop it so the next attempt re-resolves.
                if (transport == TransportType.USB) EpsonPrinterManager.dropUsbTarget(identity)
                val fromStatus = EpsonErrorMapper.fromStatus(lastStatus ?: currentStatus(handle))
                PrintError(
                    category = fromStatus
                        ?: if (committedToPaper) {
                            PrintCategory.PARTIAL_PRINT
                        } else {
                            EpsonErrorMapper.fromException(e, transport)
                        },
                    context = printerContext.copy(
                        vendorErrorCode = EpsonErrorMapper.vendorCode(e),
                    ),
                    cause = e,
                    detail = e.message,
                )
            } finally {
                if (inTransaction) runCatching { handle.endTransaction() }
                runCatching { handle.setReceiveEventListener(null) }
                runCatching { handle.disconnect() }
                printer = null
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        runCatching { printer?.disconnect() }
        printer = null
        true
    }

    override fun forceClose() {
        // The SDK has no synchronous abort; dropping the connection is the closest equivalent and
        // is what frees a wedged send.
        runCatching { printer?.disconnect() }
        printer = null
    }

    override suspend fun resolvedLockIdentity(context: Context?): String? {
        if (transport != TransportType.USB) return null
        return EpsonPrinterManager.cachedUsbTarget(identity)
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            EpsonPrinterManager.globalMutex.withLock {
                val handle = openPrinter(ctx) ?: return@withLock null
                try {
                    EpsonErrorMapper.toDomain(handle.status)
                } finally {
                    runCatching { handle.disconnect() }
                    printer = null
                }
            }
        }

    // -------------------------------------------------------------------------------------------

    /** Creates a printer object, resolves the target, and connects. */
    private suspend fun openPrinter(context: Context): Printer? {
        val target = resolveTarget(context) ?: return null
        val handle = runCatching {
            Printer(
                EpsonPrinterManager.seriesFor(model ?: displayName),
                Printer.MODEL_ANK,
                context.applicationContext,
            )
        }.getOrElse {
            Log.w(TAG, "Could not construct an Epson Printer", it)
            return null
        }

        handle.setReceiveEventListener(receiveListener)
        return try {
            handle.connect(target, Printer.PARAM_DEFAULT)
            if (transport == TransportType.USB) {
                EpsonPrinterManager.rememberUsbTarget(identity, target)
            }
            printer = handle
            handle
        } catch (e: Exception) {
            Log.w(TAG, "Epson connect failed for target $target", e)
            if (transport == TransportType.USB) EpsonPrinterManager.dropUsbTarget(identity)
            runCatching { handle.setReceiveEventListener(null) }
            null
        }
    }

    /** Builds the `TCP:` / `BT:` / `USB:` target, resolving USB live when it is not cached. */
    private suspend fun resolveTarget(context: Context): String? {
        val cached = if (transport == TransportType.USB) {
            EpsonPrinterManager.cachedUsbTarget(identity)
        } else {
            null
        }
        EpsonPrinterManager.target(transport, identifier, cached)?.let { return it }

        // USB with no cached target: ask discovery what is actually plugged in right now.
        if (transport == TransportType.USB) {
            val found = EpsonDiscovery.findUsbTarget(context, identifier)
            if (found != null) EpsonPrinterManager.rememberUsbTarget(identity, found)
            return found
        }
        return null
    }

    /**
     * Sends the buffered commands and waits for the printer's own verdict.
     *
     * `sendData` returns as soon as the job is handed off; the result only arrives on
     * `onPtrReceive`, so success is the callback, not the call.
     *
     * @return `null` on success, or the failure.
     */
    private suspend fun sendAndAwait(handle: Printer): PrintError? {
        lastStatus = null
        val outcome = withTimeoutOrNull(EpsonPrinterManager.SEND_TIMEOUT_MS.toLong()) {
            suspendCancellableCoroutine<SendOutcome> { continuation ->
                val resumed = AtomicBoolean(false)
                pendingSend = { code, status ->
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(SendOutcome(code, status))
                    }
                }
                runCatching { handle.sendData(EpsonPrinterManager.SEND_TIMEOUT_MS) }
                    .onFailure { throwable ->
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(SendOutcome(null, null, throwable))
                        }
                    }
            }
        }
        pendingSend = null

        return when {
            outcome == null -> PrintError(
                PrintCategory.SEND_TIMEOUT,
                printerContext,
                detail = "The printer did not report a result within " +
                    "${EpsonPrinterManager.SEND_TIMEOUT_MS}ms.",
            )

            outcome.throwable != null -> PrintError(
                category = EpsonErrorMapper.fromException(outcome.throwable, transport),
                context = printerContext.copy(
                    vendorErrorCode = EpsonErrorMapper.vendorCode(outcome.throwable),
                ),
                cause = outcome.throwable,
                detail = outcome.throwable.message,
            )

            outcome.code == Printer.CODE_SUCCESS -> null

            else -> PrintError(
                category = EpsonErrorMapper.fromStatus(outcome.status) ?: PrintCategory.SEND_FAILED,
                context = printerContext.copy(vendorErrorCode = "code=${outcome.code}"),
                detail = "The printer rejected the job (code ${outcome.code}).",
            )
        }
    }

    private class SendOutcome(
        val code: Int?,
        val status: PrinterStatusInfo?,
        val throwable: Throwable? = null,
    )

    /** Set from the print coroutine, invoked from the SDK's callback thread. */
    @Volatile
    private var pendingSend: ((Int, PrinterStatusInfo?) -> Unit)? = null

    private val receiveListener = ReceiveListener { _, code, status, _ ->
        lastStatus = status
        pendingSend?.invoke(code, status)
    }

    private fun currentStatus(handle: Printer): PrinterStatusInfo? =
        runCatching { handle.status }.getOrNull()

    /**
     * Maps the app's Darkness control onto Epson's own image brightness parameter.
     *
     * Gamma 1.0 is neutral and maps to the value the reference receipts print at. The app's
     * gamma curve lightens as gamma rises, so Epson's brightness rises with it and the two agree
     * on which direction is darker.
     */
    private fun brightnessFor(job: RasterJob): Double {
        val gamma = job.options.gamma.toDouble().coerceIn(0.4, 2.5)
        return (EpsonPrinterManager.NEUTRAL_BRIGHTNESS * gamma).coerceIn(0.01, 2.0)
    }

    private fun Alignment.toEpson(): Int = when (this) {
        Alignment.LEFT -> Printer.ALIGN_LEFT
        Alignment.CENTER -> Printer.ALIGN_CENTER
        Alignment.RIGHT -> Printer.ALIGN_RIGHT
    }

    private companion object {
        const val TAG = "EpsonThermalPrinter"
    }
}

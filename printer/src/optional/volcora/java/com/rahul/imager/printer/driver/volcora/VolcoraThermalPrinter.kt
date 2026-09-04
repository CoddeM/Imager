package com.rahul.imager.printer.driver.volcora

import android.content.Context
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
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.BtReadiness
import com.rahul.imager.printer.transport.UsbHostPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.posprinter.IDeviceConnection
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Volcora / Xprinter-OEM driver (`net.posprinter`, from `printer-lib-3.2.0.aar`).
 *
 * The defining problem with this SDK is that its print calls are FIRE AND FORGET: `printBitmap`
 * returns immediately and a failure only ever surfaces later as a `SEND_FAIL` on the connection
 * listener. Printing blind would mean happily reporting success while the printer was unplugged.
 *
 * The reliability strategy is therefore:
 *
 *  1. a PRE-PRINT status probe. It fails fast on cover-open or paper-out, and — more importantly —
 *     it exposes a stale socket, because the probe's own write raises `SEND_FAIL` on a dead
 *     handle. Nothing has been printed at that point, so the failure is reported CLEAN and the
 *     engine reconnects and re-sends transparently.
 *  2. a POST-PRINT status probe, confirming the link flushed the job. A `SEND_FAIL` seen by then
 *     is a DIRTY failure: output may be partial, and signalling clean would double-print.
 *  3. a probe TIMEOUT with no `SEND_FAIL` means this printer simply does not answer status
 *     queries, which is normal for much of this hardware — proceed and succeed optimistically.
 */
class VolcoraThermalPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
    private val port: Int,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.VOLCORA

    private val key: String = "${transport.name.lowercase()}:${identifier.lowercase()}"

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, identifier)

    override suspend fun isAvailable(context: Context?): Boolean {
        val ctx = context ?: return false
        return when (transport) {
            TransportType.LAN -> identifier.isNotBlank()
            TransportType.BLUETOOTH -> BluetoothLink.hasConnectPermission(ctx) &&
                BluetoothLink.isEnabled(ctx)

            TransportType.USB -> VolcoraManager.usbDevicePaths(ctx).isNotEmpty()
            TransportType.INNER -> false
        }
    }

    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        open(ctx) != null
    }

    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext PrintError(
            PrintCategory.CONNECT_FAILED,
            printerContext,
            detail = "No Context available for the Volcora SDK.",
        )

        if (transport == TransportType.BLUETOOTH) {
            when (val readiness = BluetoothLink.ensureReady(ctx, identifier)) {
                is BtReadiness.NotReady -> return@withContext PrintError(
                    readiness.category,
                    printerContext,
                    detail = readiness.detail,
                )

                BtReadiness.Ready -> Unit
            }
        }

        val fromCache = VolcoraManager.cached(key) != null
        val connection = open(ctx) ?: return@withContext PrintError(
            category = when (transport) {
                TransportType.LAN -> PrintCategory.LAN_UNREACHABLE
                TransportType.BLUETOOTH -> PrintCategory.BT_UNREACHABLE
                TransportType.USB -> PrintCategory.USB_NOT_ENUMERATED
                TransportType.INNER -> PrintCategory.CONNECT_FAILED
            },
            context = printerContext,
            detail = "The Volcora SDK did not connect to \"$identifier\".",
        )

        val printer = POSPrinter(connection)
        VolcoraManager.clearSendFailure(key)

        // ---- 1. pre-print probe --------------------------------------------------------------
        val preProbe = probe(printer)
        if (VolcoraManager.sendFailed(key)) {
            // The probe's own write failed, so nothing has reached paper: a clean failure the
            // engine can transparently retry on a fresh connection.
            VolcoraManager.forceClose(key)
            return@withContext PrintError(
                category = PrintCategory.SEND_FAILED,
                context = printerContext.copy(vendorErrorCode = "pre_probe_send_fail"),
                detail = "The cached connection was dead.",
                clean = fromCache,
            )
        }
        preProbe?.let { status ->
            VolcoraManager.categoryForStatus(status)?.let { category ->
                return@withContext PrintError(
                    category = category,
                    context = printerContext.copy(
                        vendorErrorCode = VolcoraManager.statusName(status),
                    ),
                    detail = "The printer reported ${VolcoraManager.statusName(status)}.",
                    clean = true,
                )
            }
        }

        // ---- 2. print --------------------------------------------------------------------
        var committedToPaper = false
        try {
            printer.initializePrinter()
            val bands = job.bands
            bands.forEachIndexed { index, band ->
                currentCoroutineContext().ensureActive()
                val bandBitmap = job.bandBitmap(band)
                // Multiple of 8, and NEVER upscaled: this SDK will happily stretch an image and
                // print a blurry, sheared mess if asked to.
                val width = (minOf(bandBitmap.width, job.paper.widthDots) / 8 * 8)
                    .coerceAtLeast(8)
                printer.printBitmap(bandBitmap, job.options.alignment.toPos(), width)
                committedToPaper = true
                onProgress((index + 1).toFloat() / bands.size)
            }
            printer.feedLine(job.options.feedLinesAfter)
            if (job.options.cutAfter && job.paper.supportsCutter) {
                runCatching { printer.cutHalfAndFeed(1) }
            }
        } catch (e: CancellationException) {
            VolcoraManager.forceClose(key)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Volcora print threw for $identifier", e)
            VolcoraManager.forceClose(key)
            return@withContext PrintError(
                category = if (committedToPaper) {
                    PrintCategory.PARTIAL_PRINT
                } else {
                    PrintCategory.SEND_FAILED
                },
                context = printerContext,
                cause = e,
                detail = e.message,
            )
        }

        // ---- 3. post-print probe -------------------------------------------------------------
        val postProbe = probe(printer)
        if (VolcoraManager.sendFailed(key)) {
            VolcoraManager.forceClose(key)
            // DIRTY on purpose: part of the image may already be on paper, and re-sending would
            // print it twice.
            return@withContext PrintError(
                category = PrintCategory.PARTIAL_PRINT,
                context = printerContext.copy(vendorErrorCode = "post_probe_send_fail"),
                detail = "The connection failed while the job was being flushed.",
                clean = false,
            )
        }
        postProbe?.let { status ->
            VolcoraManager.categoryForStatus(status)?.let { category ->
                return@withContext PrintError(
                    category = category,
                    context = printerContext.copy(
                        vendorErrorCode = VolcoraManager.statusName(status),
                    ),
                    detail = "The printer reported ${VolcoraManager.statusName(status)} " +
                        "after the job.",
                )
            }
        }

        // A silent printer is a working printer as far as this SDK can tell.
        null
    }

    override suspend fun disconnect(context: Context?): Boolean {
        VolcoraManager.forceClose(key)
        return true
    }

    override fun forceClose() {
        VolcoraManager.forceClose(key)
    }

    override suspend fun resolvedLockIdentity(context: Context?): String? {
        if (transport != TransportType.USB) return null
        val ctx = context ?: return null
        return VolcoraManager.usbDevicePaths(ctx).firstOrNull { it == identifier }
            ?: VolcoraManager.usbDevicePaths(ctx).singleOrNull()
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            val connection = open(ctx) ?: return@withContext null
            val status = probe(POSPrinter(connection)) ?: return@withContext null
            PrinterStatus(
                online = status != POSConst.STS_PRINTER_ERR,
                paperOut = status == POSConst.STS_PAPEREMPTY,
                coverOpen = status == POSConst.STS_COVEROPEN,
                rawVendorCode = VolcoraManager.statusName(status),
            )
        }

    // -------------------------------------------------------------------------------------------

    /** Connects, or reuses the cached connection. */
    private suspend fun open(context: Context): IDeviceConnection? {
        VolcoraManager.ensureInitialized(context)
        VolcoraManager.cached(key)?.let { return it }

        val deviceType = VolcoraManager.deviceType(transport) ?: return null
        val connectInfo = when (transport) {
            TransportType.LAN -> identifier
            TransportType.BLUETOOTH -> BluetoothLink.normalizeAddress(identifier)
            TransportType.USB -> resolveUsbPath(context) ?: return null
            TransportType.INNER -> return null
        }

        // USB permission must be held BEFORE the SDK connects: the SDK asks for it with an
        // unflagged receiver and a mutable implicit PendingIntent, which Android 14+ rejects
        // outright, so the SDK would simply never be granted anything.
        if (transport == TransportType.USB) {
            val device = UsbHostPermission.findDevice(context, connectInfo)
            if (device == null || !UsbHostPermission.ensurePermission(context, device)) return null
        }

        val connection = runCatching { POSConnect.createDevice(deviceType) }.getOrElse {
            Log.w(TAG, "createDevice($deviceType) threw", it)
            return null
        }

        val connected = withTimeoutOrNull(VolcoraManager.CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                val listener = VolcoraManager.listenerFor(key) { code, _ ->
                    val done = when (code) {
                        POSConnect.CONNECT_SUCCESS -> true
                        POSConnect.CONNECT_FAIL,
                        POSConnect.CONNECT_INTERRUPT,
                        POSConnect.BLUETOOTH_INTERRUPT,
                        POSConnect.USB_DETACHED,
                        -> false

                        else -> return@listenerFor
                    }
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(done)
                    }
                }
                runCatching { connection.connect(connectInfo, listener) }.onFailure {
                    Log.w(TAG, "connect($connectInfo) threw", it)
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: false

        if (!connected) {
            runCatching { connection.close() }
            return null
        }
        VolcoraManager.put(key, connection)
        return connection
    }

    private fun resolveUsbPath(context: Context): String? {
        val paths = VolcoraManager.usbDevicePaths(context)
        return paths.firstOrNull { it == identifier } ?: paths.singleOrNull()
    }

    /**
     * Reads the printer status.
     *
     * @return the status token, or `null` when the printer did not answer in time — which means
     *   "this model has no status support", not "this model is broken".
     */
    private suspend fun probe(printer: POSPrinter): Int? =
        withTimeoutOrNull(VolcoraManager.STATUS_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { continuation ->
                val resumed = AtomicBoolean(false)
                runCatching {
                    printer.printerStatus { status ->
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(status)
                        }
                    }
                }.onFailure {
                    Log.d(TAG, "printerStatus threw: ${it.message}")
                    // Leave the continuation to the enclosing timeout: a throw here usually means
                    // the write failed, which the listener reports as SEND_FAIL.
                }
            }
        }

    private fun Alignment.toPos(): Int = when (this) {
        Alignment.LEFT -> POSConst.ALIGNMENT_LEFT
        Alignment.CENTER -> POSConst.ALIGNMENT_CENTER
        Alignment.RIGHT -> POSConst.ALIGNMENT_RIGHT
    }

    private companion object {
        const val TAG = "VolcoraThermalPrinter"
    }
}

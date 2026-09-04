package com.rahul.imager.printer.driver.generic

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
import com.rahul.imager.printer.escpos.EscPosEncoder
import com.rahul.imager.printer.raster.RasterJob
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.RawLink
import com.rahul.imager.printer.transport.UsbHostPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The generic ESC/POS driver: LAN, Bluetooth SPP and USB bulk, one raster encoder.
 *
 * This is the workhorse. It needs no vendor SDK, is always available, and drives the large
 * majority of third-party thermal printers on the market. Vendor drivers exist only where the
 * hardware genuinely cannot be reached this way (built-in heads behind a system service) or where
 * the vendor SDK does something meaningfully better (Star, Epson).
 */
class GenericEscPosPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
    private val port: Int,
    override val brand: PrinterBrand = PrinterBrand.GENERIC_ESCPOS,
) : ThermalPrinter {

    /** Cache key: the physical device, so two saved entries for one unit share a link. */
    private val linkKey: String = "${transport.name.lowercase()}:${identifier.lowercase()}"

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, identifier)

    override suspend fun isAvailable(context: Context?): Boolean {
        val ctx = context ?: return false
        return when (transport) {
            TransportType.LAN -> identifier.isNotBlank()
            TransportType.BLUETOOTH ->
                identifier.isNotBlank() &&
                    BluetoothLink.hasConnectPermission(ctx) &&
                    BluetoothLink.isEnabled(ctx)

            TransportType.USB -> UsbHostPermission.findDevice(ctx, identifier) != null
            TransportType.INNER -> false
        }
    }

    override suspend fun connect(context: Context?): Boolean {
        val ctx = context ?: return false
        if (identifier.isBlank()) return false
        return GenericEscPosManager.open(ctx, transport, identifier, port, resolveKey(ctx))
            .isSuccess
    }

    /**
     * Sends the job band by band.
     *
     * The single subtle rule here is the [PrintError.clean] flag. A cached link that has silently
     * died — the printer was power-cycled, the Wi-Fi roamed, the cable was pulled — reports
     * connected instantly and then fails on the very first write. Nothing has reached paper at
     * that point, so the engine is allowed to reconnect and re-send. The moment the first BAND has
     * been written that is no longer true, and a re-send could double or truncate the print, so
     * every later failure is reported dirty.
     */
    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext PrintError(
            PrintCategory.CONNECT_FAILED,
            printerContext,
            detail = "No Context available for a socket-based printer.",
        )
        val key = resolveKey(ctx)
        val fromCache = GenericEscPosManager.cached(key) != null

        val link: RawLink = GenericEscPosManager.open(ctx, transport, identifier, port, key)
            .getOrElse { failure ->
                return@withContext GenericEscPosManager.mapConnectFailure(
                    failure,
                    brand,
                    transport,
                    identifier,
                )
            }

        var committedToPaper = false
        try {
            link.write(EscPosEncoder.preamble(job))
            link.flush()

            val bands = EscPosEncoder.bands(job)
            bands.forEachIndexed { index, band ->
                currentCoroutineContext().ensureActive()
                link.write(band)
                link.flush()
                committedToPaper = true
                onProgress((index + 1).toFloat() / bands.size)
            }

            link.write(EscPosEncoder.postamble(job))
            link.flush()
            null
        } catch (e: CancellationException) {
            // The engine handles cancellation; the link must go so no half-written job is reused.
            GenericEscPosManager.forceClose(key)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Send failed on $identifier (committed=$committedToPaper)", e)
            GenericEscPosManager.forceClose(key)
            PrintError(
                category = if (committedToPaper) {
                    PrintCategory.PARTIAL_PRINT
                } else {
                    PrintCategory.SEND_FAILED
                },
                context = printerContext,
                cause = e,
                detail = e.message,
                clean = !committedToPaper && fromCache,
            )
        }
    }

    override suspend fun disconnect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context
        val key = if (ctx != null) resolveKey(ctx) else linkKey
        GenericEscPosManager.forceClose(key)
        true
    }

    override fun forceClose() {
        // No Context here by design: the key is derived from the saved address, and the USB
        // variant additionally clears the resolved key it may have cached.
        GenericEscPosManager.forceClose(linkKey)
        resolvedUsbKey?.let { GenericEscPosManager.forceClose(it) }
    }

    /**
     * USB device paths go stale across a replug, so the physical device name is the honest lock
     * identity. Every other transport already has a stable address.
     */
    override suspend fun resolvedLockIdentity(context: Context?): String? {
        if (transport != TransportType.USB) return null
        val ctx = context ?: return null
        return UsbHostPermission.findDevice(ctx, identifier)?.deviceName
    }

    /**
     * Probes the head with `DLE EOT`.
     *
     * A device that does not answer within [STATUS_TIMEOUT_MS] is reported as `null`, meaning
     * "this printer does not support status queries". That is documented behaviour for a large
     * part of this hardware class, not a fault, and the caller proceeds optimistically.
     */
    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            val key = resolveKey(ctx)
            val link = GenericEscPosManager.open(ctx, transport, identifier, port, key)
                .getOrNull() ?: return@withContext null
            try {
                val printerReply = link.readStatus(
                    EscPos.statusQuery(EscPos.STATUS_PRINTER),
                    STATUS_TIMEOUT_MS,
                ) ?: return@withContext null
                val printerStatus = EscPos.decodePrinterStatus(printerReply)

                val paperStatus = link.readStatus(
                    EscPos.statusQuery(EscPos.STATUS_PAPER_SENSOR),
                    STATUS_TIMEOUT_MS,
                )?.let { EscPos.decodePaperStatus(it) }

                val offlineCause = if (printerStatus.offline) {
                    link.readStatus(
                        EscPos.statusQuery(EscPos.STATUS_OFFLINE_CAUSE),
                        STATUS_TIMEOUT_MS,
                    )?.let { EscPos.decodeOfflineCause(it) }
                } else {
                    null
                }

                PrinterStatus(
                    online = !printerStatus.offline,
                    paperOut = paperStatus?.paperOut == true || offlineCause?.paperEndStop == true,
                    paperLow = paperStatus?.paperNearEnd == true,
                    coverOpen = offlineCause?.coverOpen == true,
                    overheat = false,
                    busy = false,
                    rawVendorCode = "printer=0x%02X paper=0x%02X".format(
                        printerStatus.raw,
                        paperStatus?.raw ?: 0,
                    ),
                )
            } catch (e: Exception) {
                Log.d(TAG, "Status probe failed on $identifier: ${e.message}")
                null
            }
        }

    /** Remembers the USB key actually used, so [forceClose] can reach it without a Context. */
    @Volatile
    private var resolvedUsbKey: String? = null

    private suspend fun resolveKey(context: Context): String {
        if (transport != TransportType.USB) return linkKey
        val resolved = resolvedLockIdentity(context)?.let { "usb:$it" } ?: linkKey
        resolvedUsbKey = resolved
        return resolved
    }

    private companion object {
        const val TAG = "GenericEscPosPrinter"

        /** A head that has not answered in three seconds does not implement status queries. */
        const val STATUS_TIMEOUT_MS = 3_000L
    }
}

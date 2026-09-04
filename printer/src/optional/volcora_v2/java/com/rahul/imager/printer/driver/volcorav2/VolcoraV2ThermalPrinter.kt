package com.rahul.imager.printer.driver.volcorav2

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.printer.sdk.PrinterConstants.Command
import com.printer.sdk.PrinterConstants.PAlign
import com.printer.sdk.PrinterInstance
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared state for the Volcora V2 / SPRT family (`com.printer.sdk`).
 *
 * `PrinterInstance` is a PROCESS-WIDE SINGLETON: there is exactly one active connection in the
 * whole app, whatever printer it belongs to. Every job on this family therefore serializes on one
 * module-level mutex — a per-printer lock would be a lie.
 *
 * Class layout note: this SDK nests its constants inside `PrinterConstants` (`PrinterConstants
 * .Command`, `PrinterConstants.PAlign`). If a differently packaged build of the jar is dropped in,
 * these two imports are the only thing that needs adjusting.
 */
object VolcoraV2Manager {

    private const val TAG = "VolcoraV2Manager"

    /** One connection process-wide means one lock process-wide. */
    val globalMutex = Mutex()

    /** Arguments taken verbatim from the vendor's own working demo. */
    const val CUT_ARG_ONE = 66
    const val CUT_ARG_TWO = 50

    /** Lines fed before the cut, so the print clears the head. */
    const val FEED_LINES_BEFORE_CUT = 3

    /**
     * Synchronously closes whatever connection the singleton is holding.
     *
     * Safe from any thread and never suspends, which is what [ThermalPrinter.forceClose] requires.
     */
    fun forceClose() {
        runCatching { PrinterInstance.mPrinter?.closeConnection() }
            .onFailure { Log.d(TAG, "closeConnection threw: ${it.message}") }
    }
}

/**
 * Volcora V2 / SPRT driver (`com.printer.sdk`, from `printersdkv5.7.2.jar`).
 *
 * The SDK's write calls report failure by RETURNING A NEGATIVE NUMBER rather than by throwing, so
 * every call is checked: continuing to feed a dead pipe wastes thirty seconds and ends in a
 * meaningless timeout instead of a clear error.
 */
class VolcoraV2ThermalPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
    private val port: Int,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.VOLCORA_V2

    /** Informational connection events from the SDK arrive here. */
    private val handler = Handler(Looper.getMainLooper())

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, identifier)

    override suspend fun isAvailable(context: Context?): Boolean {
        val ctx = context ?: return false
        return when (transport) {
            TransportType.LAN -> identifier.isNotBlank()
            TransportType.BLUETOOTH -> BluetoothLink.hasConnectPermission(ctx) &&
                BluetoothLink.isEnabled(ctx)

            TransportType.USB -> UsbHostPermission.findDevice(ctx, identifier) != null
            TransportType.INNER -> false
        }
    }

    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        VolcoraV2Manager.globalMutex.withLock {
            val instance = instanceFor(ctx) ?: return@withLock false
            val opened = runCatching { instance.openConnection() }.getOrDefault(false)
            runCatching { instance.closeConnection() }
            opened
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
            detail = "No Context available for the Volcora V2 SDK.",
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

        VolcoraV2Manager.globalMutex.withLock {
            val instance = instanceFor(ctx) ?: return@withLock PrintError(
                category = if (transport == TransportType.USB) {
                    PrintCategory.USB_NOT_ENUMERATED
                } else {
                    PrintCategory.CONNECT_FAILED
                },
                context = printerContext,
                detail = "Could not build a printer instance for \"$identifier\".",
            )

            val opened = runCatching { instance.openConnection() }.getOrDefault(false)
            if (!opened) {
                return@withLock PrintError(
                    category = when (transport) {
                        TransportType.LAN -> PrintCategory.LAN_UNREACHABLE
                        TransportType.BLUETOOTH -> PrintCategory.BT_UNREACHABLE
                        else -> PrintCategory.CONNECT_FAILED
                    },
                    context = printerContext,
                    detail = "openConnection() refused.",
                )
            }

            var committedToPaper = false
            try {
                instance.initPrinter()
                instance.setPrinter(Command.ALIGN, job.options.alignment.toCommand())

                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()
                    val bandBitmap = job.bandBitmap(band)
                    val written = instance.printImage(
                        bandBitmap,
                        job.options.alignment.toPAlign(),
                        0,
                        false,
                    )
                    if (written < 0) {
                        // Stop immediately: every further write into a dead pipe just delays the
                        // error the user is waiting for.
                        return@withLock PrintError(
                            category = if (committedToPaper) {
                                PrintCategory.PARTIAL_PRINT
                            } else {
                                PrintCategory.SEND_FAILED
                            },
                            context = printerContext.copy(vendorErrorCode = "write=$written"),
                            detail = "printImage returned $written on band ${index + 1}.",
                        )
                    }
                    committedToPaper = true
                    onProgress((index + 1).toFloat() / bands.size)
                }

                instance.setPrinter(
                    Command.PRINT_AND_WAKE_PAPER_BY_LINE,
                    job.options.feedLinesAfter.coerceAtLeast(
                        VolcoraV2Manager.FEED_LINES_BEFORE_CUT,
                    ),
                )
                if (job.options.cutAfter && job.paper.supportsCutter) {
                    // Not every unit has a cutter; the vendor demo's own argument pair is used.
                    runCatching {
                        instance.cutPaper(VolcoraV2Manager.CUT_ARG_ONE, VolcoraV2Manager.CUT_ARG_TWO)
                    }
                }
                null
            } catch (e: CancellationException) {
                VolcoraV2Manager.forceClose()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Volcora V2 print failed for $identifier", e)
                PrintError(
                    category = if (committedToPaper) {
                        PrintCategory.PARTIAL_PRINT
                    } else {
                        PrintCategory.SEND_FAILED
                    },
                    context = printerContext,
                    cause = e,
                    detail = e.message,
                )
            } finally {
                runCatching { instance.closeConnection() }
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean {
        VolcoraV2Manager.forceClose()
        return true
    }

    override fun forceClose() {
        VolcoraV2Manager.forceClose()
    }

    override suspend fun resolvedLockIdentity(context: Context?): String? {
        if (transport != TransportType.USB) return null
        val ctx = context ?: return null
        return UsbHostPermission.findDevice(ctx, identifier)?.deviceName
    }

    // -------------------------------------------------------------------------------------------

    /** Builds the singleton instance for this printer's transport. */
    private suspend fun instanceFor(context: Context): PrinterInstance? = when (transport) {
        TransportType.LAN -> runCatching {
            PrinterInstance.getPrinterInstance(identifier, port, handler)
        }.getOrNull()

        TransportType.BLUETOOTH -> {
            val device: BluetoothDevice? = runCatching {
                BluetoothLink.adapter(context)
                    ?.getRemoteDevice(BluetoothLink.normalizeAddress(identifier))
            }.getOrNull()
            device?.let {
                runCatching { PrinterInstance.getPrinterInstance(it, handler) }.getOrNull()
            }
        }

        TransportType.USB -> {
            val device: UsbDevice? = UsbHostPermission.findDevice(context, identifier)
            if (device != null && UsbHostPermission.ensurePermission(context, device)) {
                runCatching {
                    PrinterInstance.getPrinterInstance(context.applicationContext, device, handler)
                }.getOrNull()
            } else {
                null
            }
        }

        TransportType.INNER -> null
    }

    private fun Alignment.toCommand(): Int = when (this) {
        Alignment.LEFT -> Command.ALIGN_LEFT
        Alignment.CENTER -> Command.ALIGN_CENTER
        Alignment.RIGHT -> Command.ALIGN_RIGHT
    }

    private fun Alignment.toPAlign(): PAlign = when (this) {
        Alignment.LEFT -> PAlign.LEFT
        Alignment.CENTER -> PAlign.CENTER
        Alignment.RIGHT -> PAlign.RIGHT
    }

    private companion object {
        const val TAG = "VolcoraV2Printer"
    }
}

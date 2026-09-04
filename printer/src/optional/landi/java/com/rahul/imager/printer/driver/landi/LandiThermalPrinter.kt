package com.rahul.imager.printer.driver.landi

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.Stage
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.Alignment
import com.rahul.imager.printer.raster.RasterJob
import com.sdksuite.omnidriver.OmniDriver
import com.sdksuite.omnidriver.device.printer.OnPrintListener
import com.sdksuite.omnidriver.device.printer.Printer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Shared state for the Landi built-in printer (`com.sdksuite.omnidriver`).
 *
 * The driver object is bound once and kept. Fields written from the binder thread and read from a
 * print coroutine are `@Volatile`, because without it a print can read a stale `null` driver
 * moments after the callback set it.
 *
 * Package-layout note: these classes are only present when `xsuite-omnidriver-api-*.aar` has been
 * dropped into `printer/libs/`. The import paths follow the SDK's documented layout; if a
 * differently packaged build is used, the imports at the top of this file are the only thing that
 * needs adjusting.
 */
object LandiPrinterManager {

    private const val TAG = "LandiPrinterManager"

    const val CONNECT_TIMEOUT_MS = 15_000L
    const val PRINT_TIMEOUT_MS = 30_000L

    /** The built-in head, and therefore the driver, is one device: one lock. */
    val mutex = Mutex()

    @Volatile
    private var driver: OmniDriver? = null

    @Volatile
    private var printer: Printer? = null

    /** True when the app is running on Landi hardware, the only place this family exists. */
    val isLandiDevice: Boolean
        get() = Build.MANUFACTURER?.contains("landi", ignoreCase = true) == true ||
            Build.MODEL?.contains("landi", ignoreCase = true) == true ||
            Build.BRAND?.contains("landi", ignoreCase = true) == true

    /**
     * Some Landi builds only print correctly with right alignment; they are identified by an `RL`
     * marker in the build display id. This is a firmware quirk, not a preference.
     */
    val requiresRightAlignment: Boolean
        get() = Build.DISPLAY?.contains("RL", ignoreCase = false) == true

    /** Connects the driver, bounded by [CONNECT_TIMEOUT_MS]. */
    suspend fun connect(context: Context): Printer? {
        printer?.let { return it }
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                fun finish(result: Printer?) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                runCatching {
                    val instance = OmniDriver.getInstance()
                    driver = instance
                    instance.connect(context.applicationContext) {
                        val resolved = runCatching { instance.printer }.getOrNull()
                        printer = resolved
                        finish(resolved)
                    }
                }.onFailure {
                    Log.w(TAG, "OmniDriver connect threw", it)
                    finish(null)
                }
            }
        }
    }

    /** Releases the driver. */
    fun release() {
        runCatching { driver?.disconnect() }
            .onFailure { Log.d(TAG, "disconnect threw: ${it.message}") }
        printer = null
        driver = null
    }

    /** The bound printer, if any. */
    fun boundPrinter(): Printer? = printer
}

/**
 * The Landi built-in printer.
 *
 * The image path is PNG BYTES, not a `Bitmap`: `addImage` takes an encoded byte array, which is
 * why the compression happens on `Dispatchers.IO` before the print call, which itself has to run
 * on the main thread.
 */
class LandiThermalPrinter(
    override val displayName: String,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.LANDI
    override val transport: TransportType = TransportType.INNER

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, "inner")

    override suspend fun isAvailable(context: Context?): Boolean {
        if (!LandiPrinterManager.isLandiDevice) return false
        val ctx = context ?: return false
        return LandiPrinterManager.connect(ctx) != null
    }

    override suspend fun connect(context: Context?): Boolean {
        val ctx = context ?: return false
        return LandiPrinterManager.connect(ctx) != null
    }

    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? {
        val ctx = context ?: return PrintError(
            PrintCategory.SERVICE_NOT_BOUND,
            printerContext,
            detail = "No Context available to bind the Landi driver.",
        )

        return LandiPrinterManager.mutex.withLock {
            val printer = LandiPrinterManager.connect(ctx) ?: return@withLock PrintError(
                PrintCategory.SERVICE_NOT_BOUND,
                printerContext,
                detail = "The Landi driver did not connect within " +
                    "${LandiPrinterManager.CONNECT_TIMEOUT_MS / 1000}s.",
            )

            var committedToPaper = false
            try {
                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()

                    // Encode off the main thread: PNG compression of a full band is far too slow
                    // to sit on the thread the print call needs.
                    val png = withContext(Dispatchers.IO) {
                        encodePng(job.bandBitmap(band), job.paper.widthDots)
                    }

                    printer.addImage(png, alignmentValue(job.options.alignment), 0)
                    committedToPaper = true
                    onProgress((index + 1).toFloat() / bands.size)
                }

                printer.feedLine(job.options.feedLinesAfter)
                val failure = startPrint(printer)
                failure?.let { refine(printer, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Landi print failed", e)
                refine(
                    printer,
                    PrintError(
                        category = if (committedToPaper) {
                            PrintCategory.PARTIAL_PRINT
                        } else {
                            PrintCategory.SEND_FAILED
                        },
                        context = printerContext,
                        cause = e,
                        detail = e.message,
                    ),
                )
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean {
        LandiPrinterManager.release()
        return true
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? {
        val ctx = context ?: return null
        val printer = LandiPrinterManager.connect(ctx) ?: return null
        val status = runCatching { printer.status }.getOrNull() ?: return null
        return LandiErrorMapper.toDomain(status)
    }

    // -------------------------------------------------------------------------------------------

    /** Starts the print and waits for the device's verdict. */
    private suspend fun startPrint(printer: Printer): PrintError? =
        withTimeoutOrNull(LandiPrinterManager.PRINT_TIMEOUT_MS) {
            suspendCancellableCoroutine<PrintError?> { continuation ->
                val resumed = AtomicBoolean(false)
                fun finish(error: PrintError?) {
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(error)
                    }
                }

                val listener = object : OnPrintListener {
                    override fun onPrintSuccess() = finish(null)

                    override fun onPrintFailed(code: Int, message: String?) {
                        finish(
                            PrintError(
                                category = LandiErrorMapper.fromCode(code)
                                    ?: PrintCategory.SEND_FAILED,
                                context = printerContext.copy(vendorErrorCode = "code=$code"),
                                detail = message,
                            )
                        )
                    }
                }

                runCatching { printer.startPrint(listener) }.onFailure {
                    Log.w(TAG, "startPrint threw", it)
                    finish(
                        PrintError(
                            PrintCategory.SEND_FAILED,
                            printerContext,
                            cause = it,
                            detail = it.message,
                        )
                    )
                }
            }
        } ?: PrintError(
            PrintCategory.SEND_TIMEOUT,
            printerContext,
            detail = "The Landi printer did not report a result within " +
                "${LandiPrinterManager.PRINT_TIMEOUT_MS / 1000}s.",
        )

    /**
     * Sharpens a failure with the hardware status.
     *
     * The status only OVERRIDES the category when it maps to a STATUS-stage fault: replacing a
     * specific send error with a vague "printer says it is fine" would be a downgrade.
     */
    private fun refine(printer: Printer, error: PrintError): PrintError {
        val status = runCatching { printer.status }.getOrNull() ?: return error
        val mapped = LandiErrorMapper.fromStatus(status) ?: return error
        if (mapped.stage != Stage.STATUS) return error
        return error.copy(
            category = mapped,
            context = error.context.copy(vendorErrorCode = "status=$status"),
        )
    }

    /**
     * Compresses one band to PNG, scaled DOWN to the paper width when necessary.
     *
     * Never upscaled: enlarging past the source resolution only prints bigger, blurrier dots.
     */
    private fun encodePng(bitmap: Bitmap, paperWidthDots: Int): ByteArray {
        val scaled = if (bitmap.width > paperWidthDots) {
            val height = (bitmap.height.toLong() * paperWidthDots / bitmap.width)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, paperWidthDots, height, true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /** Landi's alignment values, with the RL firmware quirk applied. */
    private fun alignmentValue(alignment: Alignment): Int {
        if (LandiPrinterManager.requiresRightAlignment) return ALIGN_RIGHT
        return when (alignment) {
            Alignment.LEFT -> ALIGN_LEFT
            Alignment.CENTER -> ALIGN_CENTER
            Alignment.RIGHT -> ALIGN_RIGHT
        }
    }

    private companion object {
        const val TAG = "LandiThermalPrinter"
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }
}

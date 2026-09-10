package com.rahul.imager.printer.driver.dejavoo

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.denovo.app.invokekozen.printer.interfaces.PrintLauncherInterface
import com.denovo.app.invokekozen.printer.launcher.IntentPrintApplication
import com.denovo.app.invokekozen.printer.models.PrintErrorResult
import com.denovo.app.invokekozen.printer.models.PrintResult
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.RasterJob
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
 * Shared state for the Dejavoo / Kozen built-in printer.
 *
 * There is no external transport at all in this family: the head is part of the terminal, and
 * "connected" simply means the SDK object was constructed without throwing — the SDK has no
 * `onConnected` callback to wait for.
 *
 * Package-layout note: these classes only exist when `peripheral_v1.0.aar` has been dropped into
 * `printer/libs/`. The imports follow the SDK's documented layout; a differently packaged build
 * would need them adjusted.
 */
object DejavooPrinterManager {

    const val PRINT_TIMEOUT_MS = 30_000L

    /** The head is 384 dots over a ~48 mm printable width at 8 dots/mm. */
    const val HEAD_WIDTH_DOTS = 384

    /** One head, one lock. */
    val mutex = Mutex()

    /** True when the app is running on Kozen hardware. */
    val isKozenDevice: Boolean
        get() = Build.MODEL?.contains("kozen", ignoreCase = true) == true ||
            Build.MANUFACTURER?.contains("kozen", ignoreCase = true) == true ||
            Build.BRAND?.contains("kozen", ignoreCase = true) == true
}

/**
 * The Dejavoo / Kozen built-in printer.
 *
 * The payload is a markup STRING, and images are the only graphics path:
 *
 * ```
 * <IMG src="BASE64_PNG"></IMG>
 * ```
 *
 * Two details in that one line were each worth a day of debugging, so they are spelled out:
 *
 *  * `src` is an ATTRIBUTE. A bare `<IMG></IMG>` with the payload as element content is silently
 *    DISCARDED by the SDK's parser, which is why images historically never printed on this family.
 *  * the Base64 must use `NO_WRAP`. This is not cosmetic: the SDK decodes with flag 0, whose
 *    encoder wraps at 76 characters, and an XML parser normalises the newlines inside an attribute
 *    value — so a wrapped payload decodes to garbage.
 *
 * The parser hard-centres image lines; alignment is not passed and cannot be.
 */
class DejavooThermalPrinter(
    override val displayName: String,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.DEJAVOO
    override val transport: TransportType = TransportType.INNER

    @Volatile
    private var application: IntentPrintApplication? = null

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, "inner")

    override suspend fun isAvailable(context: Context?): Boolean =
        DejavooPrinterManager.isKozenDevice && context != null

    override suspend fun connect(context: Context?): Boolean {
        val ctx = context ?: return false
        return ensureApplication(ctx) != null
    }

    override suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError? {
        val ctx = context ?: return PrintError(
            PrintCategory.SERVICE_NOT_BOUND,
            printerContext,
            detail = "No Context available for the Dejavoo SDK.",
        )

        return DejavooPrinterManager.mutex.withLock {
            val app = ensureApplication(ctx) ?: return@withLock PrintError(
                PrintCategory.SERVICE_NOT_BOUND,
                printerContext,
                detail = "The Dejavoo print application could not be constructed.",
            )

            var committedToPaper = false
            try {
                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()

                    // Scale, compress and encode OFF the main thread; the print call itself has
                    // to run on it.
                    val markup = withContext(Dispatchers.IO) {
                        imageMarkup(job.bandBitmap(band))
                    }

                    val failure = sendAndAwait(app, markup)
                    committedToPaper = true
                    if (failure != null) return@withLock failure
                    onProgress((index + 1).toFloat() / bands.size)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Dejavoo print failed", e)
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
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean {
        application = null
        return true
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Constructs the SDK object on the MAIN thread.
     *
     * The SDK builds its launcher against the main looper; constructing it anywhere else produces
     * an object whose callbacks never fire.
     */
    private suspend fun ensureApplication(context: Context): IntentPrintApplication? {
        application?.let { return it }
        return withContext(Dispatchers.Main) {
            runCatching { IntentPrintApplication(context.applicationContext) }
                .onSuccess { application = it }
                .onFailure { Log.w(TAG, "IntentPrintApplication construction failed", it) }
                .getOrNull()
        }
    }

    /** Sends one markup payload and waits for the SDK's callback. */
    private suspend fun sendAndAwait(
        app: IntentPrintApplication,
        markup: String,
    ): PrintError? = withTimeoutOrNull(DejavooPrinterManager.PRINT_TIMEOUT_MS) {
        suspendCancellableCoroutine<PrintError?> { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(error: PrintError?) {
                if (resumed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(error)
                }
            }

            val listener = object : PrintLauncherInterface {
                override fun onPrintSuccess(result: PrintResult?) = finish(null)

                override fun onPrintFailed(result: PrintErrorResult?) {
                    val code = result?.let { "code=${it.errorCode}" }
                    val message = result?.errorMessage
                    finish(
                        PrintError(
                            category = PrintCategory.SEND_FAILED,
                            context = printerContext.copy(vendorErrorCode = code),
                            cause = result?.errorException,
                            detail = message
                                ?: "The Dejavoo printer rejected the job${
                                    code?.let { " ($it)" }.orEmpty()
                                }.",
                        )
                    )
                }
            }

            runCatching {
                app.setLaunchInterface(listener)
                app.launchPrinter(markup)
            }.onFailure {
                Log.w(TAG, "Dejavoo print call threw", it)
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
        detail = "The Dejavoo printer did not report a result within " +
            "${DejavooPrinterManager.PRINT_TIMEOUT_MS / 1000}s.",
    )

    /**
     * Builds the `<IMG src="...">` markup for one band.
     *
     * See the class KDoc for why `src` must be an attribute and why `NO_WRAP` is mandatory.
     */
    private fun imageMarkup(bitmap: Bitmap): String {
        val scaled = if (bitmap.width > DejavooPrinterManager.HEAD_WIDTH_DOTS) {
            val height = (bitmap.height.toLong() * DejavooPrinterManager.HEAD_WIDTH_DOTS /
                bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(
                bitmap,
                DejavooPrinterManager.HEAD_WIDTH_DOTS,
                height,
                true,
            )
        } else {
            bitmap
        }

        val png = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        val base64 = Base64.encodeToString(png, Base64.NO_WRAP)
        return "<IMG src=\"$base64\"></IMG>"
    }

    private companion object {
        const val TAG = "DejavooThermalPrinter"
    }
}

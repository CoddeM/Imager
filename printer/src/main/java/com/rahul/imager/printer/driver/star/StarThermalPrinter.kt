package com.rahul.imager.printer.driver.star

import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.raster.RasterJob
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.StarPrinterStatus
import com.starmicronics.stario10.starxpandcommand.DocumentBuilder
import com.starmicronics.stario10.starxpandcommand.PrinterBuilder
import com.starmicronics.stario10.starxpandcommand.StarXpandCommandBuilder
import com.starmicronics.stario10.starxpandcommand.printer.CutType
import com.starmicronics.stario10.starxpandcommand.printer.ImageParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.starmicronics.stario10.starxpandcommand.printer.Alignment as StarAlignment
import com.rahul.imager.printer.raster.Alignment as RasterAlignment

/**
 * Star Micronics driver, built on StarIO10 (`com.starmicronics:stario10`).
 *
 * The SDK is bundled from Maven, so this family is always compiled in — but it declares minSdk 26,
 * so on API 24/25 the driver reports itself unavailable instead of loading classes that cannot
 * run. See [StarPrinterManager.isSupported].
 */
@RequiresApi(StarPrinterManager.MIN_SDK)
class StarThermalPrinter(
    override val displayName: String,
    override val transport: TransportType,
    private val identifier: String,
) : ThermalPrinter {

    override val brand: PrinterBrand = PrinterBrand.STAR

    /** The handle for the job in flight. Null between jobs. */
    @Volatile
    private var printer: StarPrinter? = null

    private val printerContext: PrinterContext
        get() = PrinterContext(brand, transport, StarPrinterManager.normalizeIdentifier(identifier))

    override suspend fun isAvailable(context: Context?): Boolean =
        StarPrinterManager.isSupported && context != null && identifier.isNotBlank()

    /**
     * Star connections are opened per job, inside [printImage], because the SDK ties its state to
     * the open connection. Connecting here only proves the printer answers.
     */
    override suspend fun connect(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext false
        val handle = StarPrinterManager.createPrinter(ctx, transport, identifier)
        try {
            handle.openAsync().await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Star connect failed for $identifier", e)
            false
        } finally {
            runCatching { handle.closeAsync().await() }
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
            detail = "No Context available for the Star SDK.",
        )

        // One physical printer, one operation at a time: overlapping operations throw
        // StarIO10InUseException, which reaches the user as an unexplained random failure.
        StarPrinterManager.mutexFor(identifier).withLock {
            val handle = StarPrinterManager.createPrinter(ctx, transport, identifier)
            printer = handle
            StarPrinterManager.register(identifier, handle)

            var opened = false
            try {
                handle.openAsync().await()
                opened = true

                val bands = job.bands
                bands.forEachIndexed { index, band ->
                    currentCoroutineContext().ensureActive()
                    val isLast = index == bands.lastIndex
                    val commands = buildCommands(job, band, isLast)
                    // An empty command string is a no-op, not something to push at the printer.
                    if (commands.isNotEmpty()) handle.printAsync(commands).await()
                    onProgress((index + 1).toFloat() / bands.size)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Star print failed for $identifier", e)
                val status = if (opened) {
                    runCatching { handle.getStatusAsync().await() }.getOrNull()
                } else {
                    null
                }
                StarErrorMapper.map(e, transport, identifier, status)
            } finally {
                runCatching { handle.closeAsync().await() }
                StarPrinterManager.unregister(identifier)
                printer = null
            }
        }
    }

    override suspend fun disconnect(context: Context?): Boolean {
        StarPrinterManager.forceClose(identifier)
        return true
    }

    override fun forceClose() {
        StarPrinterManager.forceClose(identifier)
    }

    override suspend fun queryStatus(context: Context?): PrinterStatus? =
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext null
            val handle = StarPrinterManager.createPrinter(ctx, transport, identifier)
            try {
                handle.openAsync().await()
                handle.getStatusAsync().await().toDomain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Star status query failed for $identifier: ${e.message}")
                null
            } finally {
                runCatching { handle.closeAsync().await() }
            }
        }

    /**
     * Builds the StarXpand command document for one band.
     *
     * Feed and cut are attached to the LAST band only, so a multi-band photo comes out as one
     * continuous print rather than a stack of cut strips.
     */
    private fun buildCommands(job: RasterJob, band: IntRange, isLast: Boolean): String {
        val bandBitmap = job.bandBitmap(band)
        val printerBuilder = PrinterBuilder()
            .styleAlignment(job.options.alignment.toStar())
            .actionPrintImage(ImageParameter(bandBitmap, job.widthDots))

        if (isLast) {
            printerBuilder.actionFeedLine(job.options.feedLinesAfter)
            if (job.options.cutAfter && job.paper.supportsCutter) {
                printerBuilder.actionCut(CutType.Partial)
            }
        }

        return StarXpandCommandBuilder()
            .addDocument(DocumentBuilder().addPrinter(printerBuilder))
            .getCommands()
    }

    private fun RasterAlignment.toStar(): StarAlignment = when (this) {
        RasterAlignment.LEFT -> StarAlignment.Left
        RasterAlignment.CENTER -> StarAlignment.Center
        RasterAlignment.RIGHT -> StarAlignment.Right
    }

    private fun StarPrinterStatus.toDomain(): PrinterStatus = PrinterStatus(
        online = !hasError,
        paperOut = paperEmpty,
        paperLow = paperNearEmpty,
        coverOpen = coverOpen,
        overheat = false,
        busy = false,
        rawVendorCode = toString(),
    )

    private companion object {
        const val TAG = "StarThermalPrinter"
    }
}

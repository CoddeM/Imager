package com.rahul.imager.usecase

import android.content.Context
import com.rahul.imager.printer.domain.PaperProfile
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.driver.registry.PrinterDriverRegistry
import com.rahul.imager.printer.driver.registry.PrinterRouting
import com.rahul.imager.printer.engine.PrintAttemptResult
import com.rahul.imager.printer.engine.PrintEngine
import com.rahul.imager.printer.engine.PrintProgress
import com.rahul.imager.printer.raster.RasterJob
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One step in the life of a print job, as the printing sheet renders it. */
sealed interface PrintStep {

    data class InProgress(val progress: PrintProgress, val copy: Int, val copies: Int) : PrintStep

    data class Finished(val result: PrintAttemptResult) : PrintStep
}

/**
 * Sends a raster to a printer.
 *
 * Copies are handled here rather than in the engine: each copy is a separate, independently
 * recoverable job, so a printer that runs out of paper on copy three fails with three copies
 * printed rather than silently losing the lot.
 */
@Singleton
class PrintPhotoUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: PrintEngine,
) {

    operator fun invoke(job: RasterJob, target: SavedPrinter): Flow<PrintStep> = channelFlow {
        val copies = job.options.copies.coerceAtLeast(1)
        for (copy in 1..copies) {
            var finished: PrintAttemptResult? = null
            engine.print(
                job = job,
                target = target,
                context = context,
                onProgress = { progress ->
                    trySend(PrintStep.InProgress(progress, copy, copies))
                },
                onResult = { result -> finished = result },
            )
            val result = finished
            // Stop at the first failure: printing the remaining copies onto a printer that has
            // just run out of paper only wastes the user's time.
            if (result == null || !result.success) {
                trySend(
                    PrintStep.Finished(
                        result ?: PrintAttemptResult(
                            success = false,
                            error = PrintError(PrintCategory.UNKNOWN),
                        )
                    )
                )
                return@channelFlow
            }
            if (copy == copies) {
                trySend(PrintStep.Finished(result))
            }
        }
    }
}

/** Connects a printer on demand, e.g. from the Reconnect button. */
@Singleton
class ConnectPrinterUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** @return `null` on success, or why it failed. */
    suspend operator fun invoke(printer: SavedPrinter): PrintError? =
        when (val routing = PrinterDriverRegistry.route(context, printer)) {
            is PrinterRouting.Unroutable -> PrintError(routing.category, detail = routing.detail)

            is PrinterRouting.Resolved -> {
                val connected = runCatching { routing.printer.connect(context) }
                    .getOrDefault(false)
                if (connected) {
                    null
                } else {
                    PrintError(
                        PrintCategory.CONNECT_FAILED,
                        detail = "${printer.displayName} did not accept a connection.",
                    )
                }
            }
        }
}

/** Reads a printer's hardware status, when the transport can report one. */
@Singleton
class QueryPrinterStatusUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** `null` means the printer does not answer status queries, which is not a fault. */
    suspend operator fun invoke(printer: SavedPrinter): PrinterStatus? =
        when (val routing = PrinterDriverRegistry.route(context, printer)) {
            is PrinterRouting.Unroutable -> null
            is PrinterRouting.Resolved ->
                runCatching { routing.printer.queryStatus(context) }.getOrNull()
        }
}

/**
 * Resolves the paper profile a printer should use.
 *
 * The order is deliberate: a user override always wins, then whatever was saved on the printer,
 * and only then the automatic resolution from the model string.
 */
@Singleton
class ResolvePaperProfileUseCase @Inject constructor() {

    operator fun invoke(
        printer: SavedPrinter,
        overrides: Map<String, String> = emptyMap(),
    ): PaperProfile {
        overrides[printer.id]?.let { return PaperProfiles.resolve(it) }
        return PaperProfiles.resolve(printer.paperProfileId)
    }
}

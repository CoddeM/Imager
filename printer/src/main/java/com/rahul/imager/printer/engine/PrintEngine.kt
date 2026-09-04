package com.rahul.imager.printer.engine

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverRegistry
import com.rahul.imager.printer.driver.registry.PrinterRouting
import com.rahul.imager.printer.raster.RasterJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Progress of a print attempt, as the UI shows it. */
sealed interface PrintProgress {
    data object Resolving : PrintProgress
    data object Connecting : PrintProgress

    /** @param fraction 0f..1f across all bands. */
    data class Sending(val bandsSent: Int, val totalBands: Int, val fraction: Float) :
        PrintProgress

    data object Finishing : PrintProgress
}

/** The outcome of one print attempt. */
data class PrintAttemptResult(
    val success: Boolean,
    val error: PrintError? = null,
    val trace: PrintJobTrace? = null,
)

/** Resolves a saved printer to a live driver. Injectable so the engine can be tested. */
fun interface PrinterResolver {
    suspend fun resolve(context: Context?, target: SavedPrinter): PrinterRouting
}

/**
 * The reliability layer between "the user tapped Print" and "bytes reached the head".
 *
 * The stage order is fixed: RESOLVE, BREAKER, LOCK, CONNECT, SEND, FINALIZE. Almost every rule in
 * here exists because of a specific way real printers fail, and the KDoc on each says which.
 */
class PrintEngine(
    private val resolver: PrinterResolver = DefaultResolver,
    private val breaker: PrinterCircuitBreaker = PrinterCircuitBreaker(),
) {

    /** One mutex per PHYSICAL printer, so two saved entries for one unit still serialize. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(key: String): Mutex = mutexes.getOrPut(key) { Mutex() }

    /**
     * Sends [job] to [target].
     *
     * Exactly one [onResult] is emitted on every path EXCEPT external cancellation, which
     * re-throws instead so the caller's own structured concurrency handles it.
     */
    suspend fun print(
        job: RasterJob,
        target: SavedPrinter,
        context: Context?,
        onProgress: (PrintProgress) -> Unit = {},
        onResult: (PrintAttemptResult) -> Unit = {},
    ) {
        val trace = PrintJobTrace(target)

        // ---- RESOLVE ---------------------------------------------------------------------
        // Bounded, and deliberately BEFORE the breaker and the lock: routing may enumerate USB
        // devices and re-resolve live identities, so an unbounded hang here would sit outside
        // every protection the engine has, and the breaker could never trip for it.
        trace.mark(PrintStage.RESOLVE)
        onProgress(PrintProgress.Resolving)

        val resolution = try {
            withTimeout(RESOLVE_TIMEOUT_MS) {
                val routing = resolver.resolve(context, target)
                val printer = (routing as? PrinterRouting.Resolved)?.printer
                Resolution(routing, printer?.let { liveLockKey(it, context) })
            }
        } catch (e: TimeoutCancellationException) {
            return fail(
                trace,
                onResult,
                PrintError(
                    PrintCategory.CONNECT_TIMEOUT,
                    target.printerContext(),
                    cause = e,
                    detail = "Resolving the printer took longer than ${RESOLVE_TIMEOUT_MS}ms.",
                ),
            )
        }

        val printer = when (val routing = resolution.routing) {
            is PrinterRouting.Unroutable -> return fail(
                trace,
                onResult,
                PrintError(routing.category, target.printerContext(), detail = routing.detail),
            )

            is PrinterRouting.Resolved -> routing.printer
        }

        val lockKey = resolution.liveLockKey ?: derivedLockKey(target)
        trace.lockKey = lockKey

        // ---- BREAKER (pre-lock) ------------------------------------------------------------
        // Checked BEFORE the lock so a queue of jobs behind a stuck holder still fast-fails
        // instead of each waiting out its own timeout.
        trace.mark(PrintStage.BREAKER, breaker.state(lockKey).name)
        if (!breaker.tryAcquire(lockKey)) {
            return fail(
                trace,
                onResult,
                PrintError(
                    PrintCategory.CONNECT_FAILED,
                    target.printerContext(),
                    detail = "This printer failed recently; retrying in " +
                        "${breaker.remainingCooldownMs(lockKey) / 1000}s.",
                ),
            )
        }

        var breakerResolved = false
        fun resolveBreaker(action: () -> Unit) {
            if (!breakerResolved) {
                breakerResolved = true
                action()
            }
        }

        var result: PrintAttemptResult? = null

        try {
            trace.mark(PrintStage.LOCK)
            mutexFor(lockKey).withLock {
                // Re-checked after acquiring: a fan-out that all passed the pre-check while the
                // breaker was closed must fast-fail as soon as the first of them trips it.
                if (breaker.isOpen(lockKey)) {
                    resolveBreaker { breaker.releaseProbe(lockKey) }
                    result = PrintAttemptResult(
                        success = false,
                        error = PrintError(
                            PrintCategory.CONNECT_FAILED,
                            target.printerContext(),
                            detail = "Another job just failed on this printer.",
                        ),
                        trace = trace,
                    )
                    return@withLock
                }

                var aborted = false
                var stage = PrintStage.CONNECT
                try {
                    // The timeout lives INSIDE the lock. Outside it, a timeout would only expire
                    // the WAITERS while the stuck holder kept the mutex for ever, and the queue
                    // would never recover.
                    withTimeout(timeoutFor(job)) {
                        // ---- CONNECT -------------------------------------------------------
                        trace.mark(PrintStage.CONNECT)
                        onProgress(PrintProgress.Connecting)
                        val connectStart = System.currentTimeMillis()
                        val connected = printer.connect(context)
                        trace.recordConnectLatency(System.currentTimeMillis() - connectStart)

                        if (!connected) {
                            // The device was never reached: that is a real verdict, trip it.
                            resolveBreaker { breaker.onFailure(lockKey) }
                            result = PrintAttemptResult(
                                success = false,
                                error = PrintError(
                                    PrintCategory.CONNECT_FAILED,
                                    target.printerContext(),
                                    detail = "The printer did not accept a connection.",
                                ),
                                trace = trace,
                            )
                            return@withTimeout
                        }

                        // ---- SEND ----------------------------------------------------------
                        stage = PrintStage.SEND
                        trace.mark(PrintStage.SEND, "${job.bands.size} bands")
                        val totalBands = job.bands.size
                        val firstError = printer.printImage(context, job) { fraction ->
                            onProgress(
                                PrintProgress.Sending(
                                    bandsSent = (fraction * totalBands).toInt(),
                                    totalBands = totalBands,
                                    fraction = fraction,
                                )
                            )
                        }

                        if (firstError == null) {
                            resolveBreaker { breaker.onSuccess(lockKey) }
                            result = PrintAttemptResult(success = true, trace = trace)
                            return@withTimeout
                        }

                        trace.recordStatus(runCatching { printer.queryStatus(context) }.getOrNull())

                        // ALWAYS discard the handle after a send failure. A connection that
                        // reported connected instantly (from a cache) and then failed at SEND is
                        // the classic half-open socket, and throwing the handle away is what
                        // self-heals the "stuck until I restart the app" bug.
                        runCatching { printer.forceClose() }

                        if (firstError.clean) {
                            // Nothing reached paper, so re-sending is safe. Exactly once.
                            trace.mark(PrintStage.SEND, "clean failure, retrying once")
                            val reconnected = printer.connect(context)
                            val retryError = if (reconnected) {
                                printer.printImage(context, job) { fraction ->
                                    onProgress(
                                        PrintProgress.Sending(
                                            bandsSent = (fraction * totalBands).toInt(),
                                            totalBands = totalBands,
                                            fraction = fraction,
                                        )
                                    )
                                }
                            } else {
                                firstError
                            }

                            if (retryError == null) {
                                resolveBreaker { breaker.onSuccess(lockKey) }
                                result = PrintAttemptResult(success = true, trace = trace)
                            } else {
                                resolveBreaker { breaker.onFailure(lockKey) }
                                result = PrintAttemptResult(false, retryError, trace)
                            }
                        } else {
                            // Dirty: output may be partial, so a re-send risks a doubled print.
                            // The handle has already been discarded, so this says nothing about
                            // the hardware either — release the probe without a verdict and let
                            // the next attempt try a fresh connection.
                            resolveBreaker { breaker.releaseProbe(lockKey) }
                            result = PrintAttemptResult(false, firstError, trace)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    // NOTE: a withTimeout throw does NOT cancel the coroutine holding the lock, so
                    // isActive stays true. The abort has to be tracked explicitly, in a catch that
                    // runs before the finally below.
                    aborted = true
                    resolveBreaker { breaker.onFailure(lockKey) }
                    val category = if (stage == PrintStage.CONNECT) {
                        PrintCategory.CONNECT_TIMEOUT
                    } else {
                        PrintCategory.SEND_TIMEOUT
                    }
                    result = PrintAttemptResult(
                        success = false,
                        error = PrintError(
                            category,
                            target.printerContext(),
                            cause = e,
                            detail = "Timed out after ${timeoutFor(job)}ms in $stage.",
                        ),
                        trace = trace,
                    )
                } catch (e: CancellationException) {
                    // The USER cancelled. Record it, drop the socket, and re-throw so the caller's
                    // structured concurrency sees it. Never a verdict on the printer.
                    aborted = true
                    resolveBreaker { breaker.releaseProbe(lockKey) }
                    runCatching { printer.forceClose() }
                    trace.finishCancelled()
                    throw e
                } catch (e: Exception) {
                    aborted = true
                    // A driver that threw where it should have returned an error says nothing
                    // about the hardware; release without a verdict.
                    resolveBreaker { breaker.releaseProbe(lockKey) }
                    Log.w(TAG, "Unexpected exception printing to ${target.displayName}", e)
                    result = PrintAttemptResult(
                        success = false,
                        error = PrintError(
                            PrintCategory.UNKNOWN,
                            target.printerContext(),
                            cause = e,
                            detail = e.message,
                        ),
                        trace = trace,
                    )
                } finally {
                    // ---- FINALIZE --------------------------------------------------------
                    trace.mark(PrintStage.FINALIZE, if (aborted) "aborted" else "clean")
                    onProgress(PrintProgress.Finishing)
                    // Force-close ONLY on an abort: doing it on the happy path can cut off a
                    // print that is still flushing out of the head's buffer.
                    if (aborted) runCatching { printer.forceClose() }
                    withContext(NonCancellable) {
                        runCatching {
                            withTimeout(DISCONNECT_TIMEOUT_MS) { printer.disconnect(context) }
                        }.onFailure {
                            Log.d(TAG, "Disconnect failed for ${target.displayName}: ${it.message}")
                        }
                    }
                }
            }
        } finally {
            // Total bookkeeping: an admitted attempt that somehow escaped without a verdict must
            // still hand its probe token back, or this printer fast-fails for ever.
            resolveBreaker { breaker.releaseProbe(lockKey) }
        }

        val finalResult = result ?: PrintAttemptResult(
            success = false,
            error = PrintError(
                PrintCategory.UNKNOWN,
                target.printerContext(),
                detail = "The print attempt ended without a result.",
            ),
            trace = trace,
        )
        if (finalResult.success) trace.finishSuccess() else finalResult.error?.let(trace::finishFailure)
        onResult(finalResult)
    }

    /** Clears the breaker for one printer, e.g. after the user fixes it and taps Retry. */
    fun resetBreaker(target: SavedPrinter) {
        breaker.reset(derivedLockKey(target))
    }

    /** The breaker state for one printer, for the UI. */
    fun breakerState(target: SavedPrinter): BreakerState = breaker.state(derivedLockKey(target))

    // -----------------------------------------------------------------------------------------

    private class Resolution(val routing: PrinterRouting, val liveLockKey: String?)

    /**
     * Asks the driver for the live identity of the physical device.
     *
     * Any exception falls back to the derived key — a printer that cannot tell us its USB path is
     * still printable. A TIMEOUT does not fall back: it propagates and aborts the attempt, because
     * a hang here is exactly the unbounded stall the enclosing timeout exists to catch.
     */
    private suspend fun liveLockKey(printer: ThermalPrinter, context: Context?): String? =
        try {
            printer.resolvedLockIdentity(context)?.let { "usb:$it" }
        } catch (e: TimeoutCancellationException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Live lock identity unavailable: ${e.message}")
            null
        }

    private fun fail(
        trace: PrintJobTrace,
        onResult: (PrintAttemptResult) -> Unit,
        error: PrintError,
    ) {
        trace.finishFailure(error)
        onResult(PrintAttemptResult(success = false, error = error, trace = trace))
    }

    private companion object {
        const val TAG = "PrintEngine"

        /** Routing may enumerate USB devices; it must still be bounded. */
        const val RESOLVE_TIMEOUT_MS = 10_000L

        /** A small test print. */
        const val TEST_PRINT_TIMEOUT_MS = 30_000L

        /** A full photo, which is legitimately slow on a 203 dpi head. */
        const val PHOTO_TIMEOUT_MS = 60_000L

        /** The disconnect is best-effort; it must never hold the lock open. */
        const val DISCONNECT_TIMEOUT_MS = 5_000L

        /** Above this many bands a job is a photo rather than a test slip. */
        const val PHOTO_BAND_THRESHOLD = 4

        /** The default resolver, backed by the real driver registry. */
        val DefaultResolver = PrinterResolver { context, target ->
            if (context == null) {
                PrinterRouting.Unroutable(
                    PrintCategory.NO_PRINTER_SELECTED,
                    "No Context available to resolve a driver.",
                )
            } else {
                PrinterDriverRegistry.route(context, target)
            }
        }
    }

    private fun timeoutFor(job: RasterJob): Long =
        if (job.bands.size > PHOTO_BAND_THRESHOLD) PHOTO_TIMEOUT_MS else TEST_PRINT_TIMEOUT_MS

    /**
     * The key a printer is locked and fast-failed under when the driver cannot supply a live one.
     *
     * USB paths are namespaced because they are not globally unique across transports; built-in
     * heads key on their model, because there is only ever one of them.
     */
    private fun derivedLockKey(target: SavedPrinter): String = when (target.transport) {
        TransportType.USB -> "usb:${target.identifier}"
        TransportType.INNER -> "inner:${target.model ?: target.brand.name}"
        else -> target.identifier.lowercase()
    }
}

private fun SavedPrinter.printerContext(): PrinterContext =
    PrinterContext(brand = brand, transport = transport, identifier = identifier)

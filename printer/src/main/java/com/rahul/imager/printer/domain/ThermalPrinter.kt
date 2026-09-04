package com.rahul.imager.printer.domain

import android.content.Context
import com.rahul.imager.printer.raster.RasterJob

/**
 * The single contract every printer driver implements.
 *
 * Implementations are thin: they translate this interface onto a vendor SDK or a socket, and hand
 * the actual connection lifecycle to a per-family manager object. Nothing above this interface
 * knows which vendor it is talking to.
 *
 * Threading: every suspending member is called from the print engine on a background dispatcher.
 * Implementations must be cancellable and must never block the main thread.
 */
interface ThermalPrinter {

    /** Human-readable name, used in logs and diagnostics (not the user-chosen label). */
    val displayName: String

    val brand: PrinterBrand

    val transport: TransportType

    /**
     * Whether this driver could reach its hardware right now — the SDK is present, the host device
     * is the right kind, Bluetooth is on, the USB device is enumerated, and so on.
     *
     * Cheap and side-effect free; it must not open a connection.
     */
    suspend fun isAvailable(context: Context?): Boolean

    /**
     * Opens (or reuses) a connection.
     *
     * @return true when the printer is ready to receive a job.
     */
    suspend fun connect(context: Context?): Boolean

    /**
     * Sends a job.
     *
     * @param onProgress called with `0f..1f` as bands are sent.
     * @return `null` on success, or a typed [PrintError]. MUST return exactly once: no path may
     *   leave the caller waiting, and no path may report twice.
     */
    suspend fun printImage(
        context: Context?,
        job: RasterJob,
        onProgress: (Float) -> Unit,
    ): PrintError?

    /** Closes the connection cleanly. Failures here are reported but never fail a printed job. */
    suspend fun disconnect(context: Context?): Boolean

    /**
     * Best-effort, NON-SUSPENDING, SYNCHRONOUS abort of any open socket or stream.
     *
     * `withTimeout` cancels a coroutine but CANNOT interrupt a thread that is blocked inside
     * non-interruptible native or socket I/O. The blocked thread — and the per-device lock it is
     * holding — is only freed when the underlying stream is closed from the outside. The engine
     * calls this on timeout and on abort, so it MUST NOT block and MUST NOT suspend.
     *
     * The default is a no-op, which is correct for service-bound and built-in printers. Every
     * socket-based implementation (LAN / Bluetooth / USB) MUST override it and close the socket.
     */
    fun forceClose() {}

    /**
     * The current canonical identity of the physical device, when that can differ from the
     * persisted address. USB device paths in particular go stale across a replug.
     *
     * The engine prefers this over the saved identifier when choosing its lock and circuit-breaker
     * key, so that two saved entries that happen to point at ONE physical unit serialize on one
     * mutex instead of racing each other.
     *
     * Returning `null` is always safe and simply means "use the persisted identifier".
     */
    suspend fun resolvedLockIdentity(context: Context?): String? = null

    /**
     * Optional pre-flight hardware status probe.
     *
     * Returns `null` when the transport genuinely cannot report status — which is the normal,
     * documented behaviour of many cheap ESC/POS heads and must not be treated as a failure.
     */
    suspend fun queryStatus(context: Context?): PrinterStatus? = null
}

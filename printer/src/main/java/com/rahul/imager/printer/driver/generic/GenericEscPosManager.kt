package com.rahul.imager.printer.driver.generic

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.BluetoothRawLink
import com.rahul.imager.printer.transport.BtReadiness
import com.rahul.imager.printer.transport.BluetoothLink
import com.rahul.imager.printer.transport.LanRawLink
import com.rahul.imager.printer.transport.RawLink
import com.rahul.imager.printer.transport.UsbHostPermission
import com.rahul.imager.printer.transport.UsbRawLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the raw links used by the generic ESC/POS driver.
 *
 * Links are cached per physical device so a second print does not pay the connection cost again;
 * a cached link that has gone stale surfaces as a clean failure on the first write, which the
 * print engine turns into a transparent reconnect-and-resend.
 */
object GenericEscPosManager {

    private const val TAG = "GenericEscPos"

    /** Keyed by the RESOLVED identity of the device, not by the saved address. */
    private val links = ConcurrentHashMap<String, RawLink>()

    /** The cached link for [key], if one is open. */
    fun cached(key: String): RawLink? = links[key]?.takeIf { it.isOpen }

    /**
     * Returns a usable link, reusing a cached one when possible.
     *
     * Blocking socket work runs on [Dispatchers.IO].
     */
    suspend fun open(
        context: Context,
        transport: TransportType,
        identifier: String,
        port: Int,
        key: String,
    ): Result<RawLink> = withContext(Dispatchers.IO) {
        cached(key)?.let { return@withContext Result.success(it) }
        links.remove(key)?.let { runCatching { it.close() } }

        runCatching {
            when (transport) {
                TransportType.LAN -> LanRawLink.open(identifier, port)

                TransportType.BLUETOOTH -> {
                    when (val readiness = BluetoothLink.ensureReady(context, identifier)) {
                        is BtReadiness.NotReady -> throw BtNotReadyException(readiness)
                        BtReadiness.Ready -> BluetoothRawLink.open(context, identifier)
                    }
                }

                TransportType.USB -> {
                    val device = UsbHostPermission.findDevice(context, identifier)
                        ?: throw UsbUnavailableException(
                            PrintCategory.USB_NOT_ENUMERATED,
                            "No attached USB device matches \"$identifier\".",
                        )
                    if (!UsbHostPermission.ensurePermission(context, device)) {
                        throw UsbUnavailableException(
                            PrintCategory.USB_PERMISSION_DENIED,
                            "USB permission was not granted for ${device.deviceName}.",
                        )
                    }
                    UsbRawLink.open(context, device)
                }

                TransportType.INNER -> throw IllegalStateException(
                    "The generic ESC/POS driver has no built-in transport",
                )
            }
        }.onSuccess { links[key] = it }
    }

    /**
     * Closes a link immediately, from any thread, without suspending.
     *
     * This is what unblocks a thread wedged inside a socket write; the print engine calls it on
     * timeout and on abort.
     */
    fun forceClose(key: String) {
        links.remove(key)?.let { link ->
            runCatching { link.close() }
                .onFailure { Log.d(TAG, "forceClose($key) threw: ${it.message}") }
        }
    }

    /** Closes every cached link. Used when the app loses interest in all printers. */
    fun closeAll() {
        links.keys.toList().forEach { forceClose(it) }
    }

    /** Translates a transport-level throwable into the app's error taxonomy. */
    fun mapConnectFailure(
        throwable: Throwable,
        brand: PrinterBrand,
        transport: TransportType,
        identifier: String,
    ): PrintError {
        val printerContext = PrinterContext(brand, transport, identifier)
        return when (throwable) {
            is BtNotReadyException -> PrintError(
                category = throwable.readiness.category,
                context = printerContext,
                detail = throwable.readiness.detail,
            )

            is UsbUnavailableException -> PrintError(
                category = throwable.category,
                context = printerContext,
                detail = throwable.detail,
            )

            is SocketTimeoutException -> PrintError(
                category = PrintCategory.LAN_TIMEOUT,
                context = printerContext,
                cause = throwable,
                detail = throwable.message,
            )

            is IOException -> PrintError(
                category = when (transport) {
                    TransportType.LAN -> PrintCategory.LAN_UNREACHABLE
                    TransportType.BLUETOOTH -> PrintCategory.BT_UNREACHABLE
                    else -> PrintCategory.CONNECT_FAILED
                },
                context = printerContext,
                cause = throwable,
                detail = throwable.message,
            )

            else -> PrintError(
                category = PrintCategory.CONNECT_FAILED,
                context = printerContext,
                cause = throwable,
                detail = throwable.message,
            )
        }
    }

    /** Thrown when the Bluetooth gate refused; carries the specific reason through Result. */
    class BtNotReadyException(val readiness: BtReadiness.NotReady) :
        IOException(readiness.detail)

    /** Thrown when a USB device is missing or unauthorised. */
    class UsbUnavailableException(val category: PrintCategory, val detail: String) :
        IOException(detail)
}

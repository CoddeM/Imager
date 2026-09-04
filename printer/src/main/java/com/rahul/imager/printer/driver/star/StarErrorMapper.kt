package com.rahul.imager.printer.driver.star

import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrintError
import com.rahul.imager.printer.domain.PrinterContext
import com.rahul.imager.printer.domain.TransportType
import com.starmicronics.stario10.StarIO10ArgumentException
import com.starmicronics.stario10.StarIO10BadResponseException
import com.starmicronics.stario10.StarIO10CommunicationException
import com.starmicronics.stario10.StarIO10IllegalHostDeviceStateException
import com.starmicronics.stario10.StarIO10InUseException
import com.starmicronics.stario10.StarIO10InvalidOperationException
import com.starmicronics.stario10.StarIO10NotFoundException
import com.starmicronics.stario10.StarIO10UnknownException
import com.starmicronics.stario10.StarIO10UnprintableException
import com.starmicronics.stario10.StarIO10UnsupportedModelException
import com.starmicronics.stario10.StarPrinterStatus

/**
 * Maps StarIO10 exceptions onto the app's error taxonomy.
 *
 * The SDK models failure entirely through exception TYPE, so this is a straight type switch. The
 * only interesting case is [StarIO10UnprintableException], which means "the printer refused the
 * job" without saying why — the caller re-queries the hardware status and passes it in so the
 * generic refusal can be sharpened into the real fault the user has to fix.
 */
object StarErrorMapper {

    fun map(
        throwable: Throwable,
        transport: TransportType,
        identifier: String,
        status: StarPrinterStatus? = null,
    ): PrintError {
        val printerContext = PrinterContext(
            brand = com.rahul.imager.printer.domain.PrinterBrand.STAR,
            transport = transport,
            identifier = identifier,
            vendorErrorCode = throwable.javaClass.simpleName,
        )

        val category = when (throwable) {
            is StarIO10CommunicationException -> when (transport) {
                TransportType.BLUETOOTH -> PrintCategory.BT_UNREACHABLE
                TransportType.LAN -> PrintCategory.LAN_UNREACHABLE
                else -> PrintCategory.CONNECT_FAILED
            }

            is StarIO10InUseException -> PrintCategory.PRINTER_BUSY
            is StarIO10NotFoundException -> PrintCategory.CONNECT_FAILED

            // The host itself is in the way: Bluetooth off, or the permission not granted.
            is StarIO10IllegalHostDeviceStateException -> when (transport) {
                TransportType.BLUETOOTH -> PrintCategory.BT_OFF
                else -> PrintCategory.CONNECT_FAILED
            }

            is StarIO10UnprintableException -> refineUnprintable(status)
            is StarIO10UnsupportedModelException -> PrintCategory.UNPRINTABLE

            // Programmer errors: bad arguments, or an operation in the wrong state.
            is StarIO10ArgumentException, is StarIO10InvalidOperationException ->
                PrintCategory.UNKNOWN

            is StarIO10BadResponseException, is StarIO10UnknownException ->
                PrintCategory.SEND_FAILED

            else -> PrintCategory.UNKNOWN
        }

        return PrintError(
            category = category,
            context = printerContext,
            cause = throwable,
            detail = throwable.message,
        )
    }

    /** Turns a bare "unprintable" refusal into the concrete hardware fault behind it. */
    private fun refineUnprintable(status: StarPrinterStatus?): PrintCategory = when {
        status == null -> PrintCategory.UNPRINTABLE
        status.paperEmpty -> PrintCategory.PAPER_OUT
        status.coverOpen -> PrintCategory.COVER_OPEN
        status.paperNearEmpty -> PrintCategory.PAPER_LOW
        status.hasError -> PrintCategory.UNKNOWN
        else -> PrintCategory.UNPRINTABLE
    }
}

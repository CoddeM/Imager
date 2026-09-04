package com.rahul.imager.printer.driver.epson

import com.epson.epos2.Epos2Exception
import com.epson.epos2.printer.Printer
import com.epson.epos2.printer.PrinterStatusInfo
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.TransportType

/**
 * Maps ePOS2 failures onto the app's error taxonomy.
 *
 * The SDK reports a failure twice: once as an `Epos2Exception` with an `errorStatus`, and once as
 * a [PrinterStatusInfo] describing the hardware. The status is far more actionable — "load paper"
 * beats "ERR_FAILURE" — so it is consulted FIRST and the exception is only the fallback.
 *
 * Only long-stable SDK constants are referenced here. The per-result `CODE_*` vocabulary is large,
 * varies between SDK revisions, and adds nothing the status does not already say, so this
 * deliberately checks `CODE_SUCCESS` and then reads the status.
 */
object EpsonErrorMapper {

    /** Reads a hardware status into a category, or `null` when the hardware looks healthy. */
    fun fromStatus(status: PrinterStatusInfo?): PrintCategory? {
        status ?: return null
        return when {
            status.connection == Printer.FALSE -> PrintCategory.OFFLINE
            status.online == Printer.FALSE -> PrintCategory.OFFLINE
            status.coverOpen == Printer.TRUE -> PrintCategory.COVER_OPEN
            status.paper == Printer.PAPER_EMPTY -> PrintCategory.PAPER_OUT
            status.paper == Printer.PAPER_NEAR_END -> PrintCategory.PAPER_LOW
            status.errorStatus == Printer.AUTOCUTTER_ERR -> PrintCategory.CUTTER_ERROR
            status.errorStatus == Printer.MECHANICAL_ERR -> PrintCategory.PAPER_JAM
            status.errorStatus == Printer.UNRECOVER_ERR -> PrintCategory.UNPRINTABLE
            status.errorStatus == Printer.AUTORECOVER_ERR -> PrintCategory.UNPRINTABLE
            else -> null
        }
    }

    /** Maps an `Epos2Exception` error status, used when the hardware says nothing useful. */
    fun fromException(throwable: Throwable, transport: TransportType): PrintCategory {
        val status = (throwable as? Epos2Exception)?.errorStatus ?: return PrintCategory.SEND_FAILED
        return when (status) {
            Epos2Exception.ERR_TIMEOUT -> PrintCategory.CONNECT_TIMEOUT
            Epos2Exception.ERR_CONNECT -> when (transport) {
                TransportType.BLUETOOTH -> PrintCategory.BT_UNREACHABLE
                TransportType.LAN -> PrintCategory.LAN_UNREACHABLE
                TransportType.USB -> PrintCategory.USB_NOT_ENUMERATED
                TransportType.INNER -> PrintCategory.CONNECT_FAILED
            }

            Epos2Exception.ERR_NOT_FOUND -> PrintCategory.CONNECT_FAILED
            Epos2Exception.ERR_IN_USE -> PrintCategory.PRINTER_BUSY
            Epos2Exception.ERR_DISCONNECT -> PrintCategory.DISCONNECTED_MID_PRINT
            Epos2Exception.ERR_PROCESSING -> PrintCategory.PRINTER_BUSY
            Epos2Exception.ERR_UNSUPPORTED -> PrintCategory.UNPRINTABLE
            Epos2Exception.ERR_MEMORY -> PrintCategory.IMAGE_TOO_LARGE
            Epos2Exception.ERR_PARAM, Epos2Exception.ERR_ILLEGAL -> PrintCategory.UNKNOWN
            else -> PrintCategory.SEND_FAILED
        }
    }

    /** Human-readable vendor code kept in diagnostics. */
    fun vendorCode(throwable: Throwable): String? =
        (throwable as? Epos2Exception)?.let { "Epos2Exception(${it.errorStatus})" }

    /** Converts a status snapshot into the app's own model. */
    fun toDomain(status: PrinterStatusInfo?): PrinterStatus? {
        status ?: return null
        return PrinterStatus(
            online = status.connection != Printer.FALSE && status.online != Printer.FALSE,
            paperOut = status.paper == Printer.PAPER_EMPTY,
            paperLow = status.paper == Printer.PAPER_NEAR_END,
            coverOpen = status.coverOpen == Printer.TRUE,
            overheat = false,
            busy = false,
            rawVendorCode = "connection=${status.connection} online=${status.online} " +
                "paper=${status.paper} cover=${status.coverOpen} error=${status.errorStatus}",
        )
    }
}

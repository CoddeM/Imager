package com.rahul.imager.printer.driver.landi

import android.content.Context
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.PrinterStatus
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverFactory

/**
 * Maps Landi printer status and result codes onto the app's error taxonomy.
 *
 * The SDK reports faults as small integers. Only the codes with an unambiguous meaning are mapped;
 * anything else falls through so the caller keeps the more specific error it already has.
 */
object LandiErrorMapper {

    const val STATUS_OK = 0
    const val STATUS_OUT_OF_PAPER = 1
    const val STATUS_OVERHEAT = 2
    const val STATUS_BUSY = 3
    const val STATUS_LOW_VOLTAGE = 4
    const val STATUS_HARDWARE_ERROR = 5
    const val STATUS_PAPER_JAM = 6

    /** Maps a status value, or `null` when it says nothing actionable. */
    fun fromStatus(status: Int): PrintCategory? = when (status) {
        STATUS_OK -> null
        STATUS_OUT_OF_PAPER -> PrintCategory.PAPER_OUT
        STATUS_OVERHEAT -> PrintCategory.OVERHEAT
        STATUS_BUSY -> PrintCategory.PRINTER_BUSY
        STATUS_LOW_VOLTAGE -> PrintCategory.BATTERY_LOW
        STATUS_HARDWARE_ERROR -> PrintCategory.OFFLINE
        STATUS_PAPER_JAM -> PrintCategory.PAPER_JAM
        else -> null
    }

    /** Maps a print-result code; the same vocabulary as the status. */
    fun fromCode(code: Int): PrintCategory? = fromStatus(code)

    /** Converts a status value into the app's own model. */
    fun toDomain(status: Int): PrinterStatus = PrinterStatus(
        online = status != STATUS_HARDWARE_ERROR,
        paperOut = status == STATUS_OUT_OF_PAPER,
        coverOpen = false,
        overheat = status == STATUS_OVERHEAT,
        busy = status == STATUS_BUSY,
        rawVendorCode = "landi_status_$status",
    )
}

/**
 * Registers the Landi built-in printer family.
 *
 * Found by fully-qualified name from `PrinterDriverRegistry.OPTIONAL_FACTORY_CLASSES`. The family
 * is connection-independent — there is exactly one head, welded into the terminal — so it only
 * ever serves [TransportType.INNER].
 */
object LandiDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.LANDI

    override val supportedTransports: Set<TransportType> = setOf(TransportType.INNER)

    override val requiredSdkArtifact: String = "xsuite-omnidriver-api-*.aar"

    override fun isSupported(context: Context): Boolean = LandiPrinterManager.isLandiDevice

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (!LandiPrinterManager.isLandiDevice) return null
        return LandiThermalPrinter(saved.displayName)
    }

    override fun unavailableReason(context: Context): String =
        "The built-in Landi printer only exists on Landi terminals"
}

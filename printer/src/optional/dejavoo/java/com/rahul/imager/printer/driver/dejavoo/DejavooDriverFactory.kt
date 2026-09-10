package com.rahul.imager.printer.driver.dejavoo

import android.content.Context
import android.os.Build
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.PrinterDriverFactory

/**
 * Registers the Dejavoo / Kozen built-in printer family.
 *
 * Found by fully-qualified name from `PrinterDriverRegistry.OPTIONAL_FACTORY_CLASSES`. There is no
 * external transport: this family is the terminal's own head and nothing else.
 */
object DejavooDriverFactory : PrinterDriverFactory {

    override val brand: PrinterBrand = PrinterBrand.DEJAVOO

    override val supportedTransports: Set<TransportType> = setOf(TransportType.INNER)

    override val requiredSdkArtifact: String = "peripheral_v1.0.aar"

    override fun isSupported(context: Context): Boolean = DejavooPrinterManager.isKozenDevice

    override fun create(saved: SavedPrinter): ThermalPrinter? {
        if (!DejavooPrinterManager.isKozenDevice) return null
        return DejavooThermalPrinter(saved.displayName)
    }

    /** See the note on LandiDriverFactory: the Build values make a missed match diagnosable. */
    override fun unavailableReason(context: Context): String =
        "The built-in Dejavoo printer only exists on Kozen terminals. This device reports " +
            "${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL}."
}

/**
 * The text markup this family understands, kept here so the vocabulary lives in one place.
 *
 * Only [image] is used by this app — it prints photos, not receipts — but the text tags are
 * documented because they are the only other thing the SDK's parser accepts.
 */
object DejavooMarkup {

    /** Left / centre / right / bold text. 48 characters per line normal, 24 bold. */
    fun left(text: String): String = "<L>$text</L>"

    fun center(text: String): String = "<C>$text</C>"

    fun right(text: String): String = "<R>$text</R>"

    fun bold(text: String): String = "<B>$text</B>"

    /** Characters per line at normal width. */
    const val CHARS_PER_LINE = 48

    /** Characters per line in bold. */
    const val CHARS_PER_LINE_BOLD = 24
}

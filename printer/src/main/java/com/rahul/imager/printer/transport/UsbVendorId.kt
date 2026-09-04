package com.rahul.imager.printer.transport

import com.rahul.imager.printer.domain.PrinterBrand

/**
 * USB vendor IDs of the printer families that have a native driver in this app.
 *
 * For a USB entry the VID is PHYSICAL TRUTH and outranks whatever brand label happens to be saved:
 * a device that enumerates as 0x04B8 is an Epson no matter what the user called it, and routing it
 * to a generic ESC/POS driver would at best print garbage.
 */
object UsbVendorId {

    const val EPSON: Int = 0x04B8   // 1208
    const val STAR: Int = 0x0519    // 1305
    const val SUNMI: Int = 0x324F   // 12879

    /** Every VID this app claims. */
    val KNOWN_PRINTER_VENDORS: Set<Int> = setOf(EPSON, STAR, SUNMI)

    /**
     * The driver family that owns [vendorId], or `null` when no family claims it.
     *
     * `null` means "unclaimed": keep whatever family was derived from the device name, which is
     * how third-party ESC/POS printers end up on the generic driver.
     */
    fun familyForVid(vendorId: Int): PrinterBrand? = when (vendorId) {
        EPSON -> PrinterBrand.EPSON
        STAR -> PrinterBrand.STAR
        SUNMI -> PrinterBrand.SUNMI
        else -> null
    }

    /** USB accessory manufacturer string Star printers identify themselves with. */
    const val STAR_ACCESSORY_MANUFACTURER: String = "Star Micronics"
}

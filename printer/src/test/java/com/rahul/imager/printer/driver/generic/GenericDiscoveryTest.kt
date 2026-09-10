package com.rahul.imager.printer.driver.generic

import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.transport.UsbVendorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which USB devices the generic scan is allowed to offer.
 *
 * The interesting case is a vendor VID whose driver is NOT in this build: it used to be hidden by
 * the vendor scan (absent) and by this one (filtered out by VID), so the printer could not be added
 * at all.
 */
class GenericDiscoveryTest {

    private val everything = PrinterBrand.entries.toSet()
    private val nothingButGeneric = setOf(PrinterBrand.GENERIC_ESCPOS)

    @Test
    fun `an unclaimed vendor id is offered as a generic ESC-POS printer`() {
        assertEquals(
            PrinterBrand.GENERIC_ESCPOS,
            GenericDiscovery.usbBrandFor(0x1234, everything),
        )
    }

    @Test
    fun `a device whose family has a driver is left to that family's own scan`() {
        for (vid in listOf(UsbVendorId.EPSON, UsbVendorId.STAR, UsbVendorId.SUNMI)) {
            assertNull(
                "VID 0x%04X should be left alone".format(vid),
                GenericDiscovery.usbBrandFor(vid, everything),
            )
        }
    }

    @Test
    fun `a device whose ESC-POS family is missing is offered under its real brand`() {
        assertEquals(
            PrinterBrand.EPSON,
            GenericDiscovery.usbBrandFor(UsbVendorId.EPSON, nothingButGeneric),
        )
    }

    @Test
    fun `a missing family that ESC-POS cannot stand in for stays hidden`() {
        // Star below API 26 is unavailable for a reason a different command set does not fix, so
        // the scan does not offer to drive it with one.
        assertNull(GenericDiscovery.usbBrandFor(UsbVendorId.STAR, nothingButGeneric))
        assertNull(GenericDiscovery.usbBrandFor(UsbVendorId.SUNMI, nothingButGeneric))
    }
}

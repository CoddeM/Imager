package com.rahul.imager.printer.driver.registry

import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.UsbVendorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules: family first, transport second, and the USB VID beating the saved label.
 */
class PrinterRouterTest {

    private val allFamilies = PrinterBrand.entries.toSet()

    private fun saved(
        brand: PrinterBrand = PrinterBrand.GENERIC_ESCPOS,
        transport: TransportType = TransportType.LAN,
        identifier: String = "192.168.1.50",
    ) = SavedPrinter(
        id = "id",
        displayName = "Printer",
        brand = brand,
        transport = transport,
        identifier = identifier,
        paperProfileId = PaperProfiles.STANDARD_80MM.id,
    )

    @Test
    fun `a saved family routes to itself`() {
        for (brand in listOf(
            PrinterBrand.GENERIC_ESCPOS,
            PrinterBrand.STAR,
            PrinterBrand.EPSON,
            PrinterBrand.VOLCORA,
            PrinterBrand.VOLCORA_V2,
        )) {
            val decision = PrinterRouter.decide(saved(brand = brand), allFamilies)
            assertEquals(RoutingDecision.Use(brand, TransportType.LAN), decision)
        }
    }

    @Test
    fun `transport is preserved for families whose driver depends on it`() {
        for (transport in listOf(
            TransportType.LAN,
            TransportType.BLUETOOTH,
            TransportType.USB,
        )) {
            val decision = PrinterRouter.decide(
                saved(brand = PrinterBrand.SUNMI, transport = transport, identifier = "addr"),
                allFamilies,
            )
            assertEquals(RoutingDecision.Use(PrinterBrand.SUNMI, transport), decision)
        }
    }

    @Test
    fun `connection-independent families always route to the built-in transport`() {
        for (brand in PrinterRouter.CONNECTION_INDEPENDENT_FAMILIES) {
            val decision = PrinterRouter.decide(
                saved(brand = brand, transport = TransportType.LAN),
                allFamilies,
            )
            assertEquals(RoutingDecision.Use(brand, TransportType.INNER), decision)
        }
    }

    @Test
    fun `an absent ESC-POS family falls back to the generic driver over every socket`() {
        for (brand in PrinterRouter.ESCPOS_COMPATIBLE_FAMILIES) {
            val available = allFamilies - brand
            for (transport in listOf(
                TransportType.LAN,
                TransportType.BLUETOOTH,
                TransportType.USB,
            )) {
                val decision = PrinterRouter.decide(
                    saved(brand = brand, transport = transport, identifier = "addr"),
                    available,
                )
                assertEquals(
                    RoutingDecision.Use(
                        brand = PrinterBrand.GENERIC_ESCPOS,
                        transport = transport,
                        compatibilityFor = brand,
                    ),
                    decision,
                )
            }
        }
    }

    @Test
    fun `the fallback records which family it is standing in for`() {
        val decision = PrinterRouter.decide(
            saved(brand = PrinterBrand.EPSON),
            allFamilies - PrinterBrand.EPSON,
        )
        assertEquals(
            PrinterBrand.EPSON,
            (decision as RoutingDecision.Use).compatibilityFor,
        )
    }

    @Test
    fun `a present vendor family is never diverted to the fallback`() {
        val decision = PrinterRouter.decide(saved(brand = PrinterBrand.EPSON), allFamilies)
        assertEquals(
            RoutingDecision.Use(PrinterBrand.EPSON, TransportType.LAN),
            decision,
        )
    }

    @Test
    fun `an absent built-in family is a typed refusal, because it has no socket`() {
        for (brand in PrinterRouter.CONNECTION_INDEPENDENT_FAMILIES) {
            val available = allFamilies - brand
            val decision = PrinterRouter.decide(
                saved(brand = brand, transport = TransportType.INNER, identifier = ""),
                available,
            )
            assertTrue("$brand should refuse", decision is RoutingDecision.Refuse)
            assertEquals(
                PrintCategory.SDK_NOT_BUNDLED,
                (decision as RoutingDecision.Refuse).category,
            )
        }
    }

    @Test
    fun `the fallback needs the generic driver itself to be present`() {
        val available = setOf(PrinterBrand.STAR)
        val decision = PrinterRouter.decide(saved(brand = PrinterBrand.EPSON), available)

        assertTrue(decision is RoutingDecision.Refuse)
        assertEquals(
            PrintCategory.SDK_NOT_BUNDLED,
            (decision as RoutingDecision.Refuse).category,
        )
    }

    @Test
    fun `an absent family on the built-in transport is refused even when it is ESC-POS capable`() {
        // A Volcora saved as INNER has no socket to fall back onto, whatever the family can speak.
        val decision = PrinterRouter.decide(
            saved(
                brand = PrinterBrand.VOLCORA,
                transport = TransportType.INNER,
                identifier = "",
            ),
            allFamilies - PrinterBrand.VOLCORA,
        )
        assertTrue(decision is RoutingDecision.Refuse)
        assertEquals(
            PrintCategory.SDK_NOT_BUNDLED,
            (decision as RoutingDecision.Refuse).category,
        )
    }

    @Test
    fun `the USB vendor id overrides a disagreeing saved brand`() {
        // The user labelled it generic, but the hardware enumerates as an Epson. The hardware wins:
        // routing it to the generic driver would push raw ESC/POS at an SDK-driven device.
        val decision = PrinterRouter.decide(
            saved(
                brand = PrinterBrand.GENERIC_ESCPOS,
                transport = TransportType.USB,
                identifier = "/dev/bus/usb/001/004",
            ),
            allFamilies,
            usbVendorId = UsbVendorId.EPSON,
        )
        assertEquals(RoutingDecision.Use(PrinterBrand.EPSON, TransportType.USB), decision)
    }

    @Test
    fun `an unclaimed USB vendor id leaves the saved brand alone`() {
        val decision = PrinterRouter.decide(
            saved(
                brand = PrinterBrand.VOLCORA,
                transport = TransportType.USB,
                identifier = "/dev/bus/usb/001/004",
            ),
            allFamilies,
            usbVendorId = 0x1234,
        )
        assertEquals(RoutingDecision.Use(PrinterBrand.VOLCORA, TransportType.USB), decision)
    }

    @Test
    fun `the vendor id is ignored for non-USB printers`() {
        val decision = PrinterRouter.decide(
            saved(brand = PrinterBrand.GENERIC_ESCPOS, transport = TransportType.LAN),
            allFamilies,
            usbVendorId = UsbVendorId.STAR,
        )
        assertEquals(RoutingDecision.Use(PrinterBrand.GENERIC_ESCPOS, TransportType.LAN), decision)
    }

    @Test
    fun `a missing address is refused before anything else is attempted`() {
        val decision = PrinterRouter.decide(saved(identifier = "  "), allFamilies)
        assertTrue(decision is RoutingDecision.Refuse)
        assertEquals(
            PrintCategory.MISSING_ADDRESS,
            (decision as RoutingDecision.Refuse).category,
        )
    }

    @Test
    fun `a built-in printer needs no address`() {
        val decision = PrinterRouter.decide(
            saved(brand = PrinterBrand.SUNMI, transport = TransportType.INNER, identifier = ""),
            allFamilies,
        )
        assertEquals(RoutingDecision.Use(PrinterBrand.SUNMI, TransportType.INNER), decision)
    }

    @Test
    fun `an unknown brand falls back to the generic driver`() {
        val decision = PrinterRouter.decide(saved(brand = PrinterBrand.UNKNOWN), allFamilies)
        assertEquals(
            RoutingDecision.Use(PrinterBrand.GENERIC_ESCPOS, TransportType.LAN),
            decision,
        )
    }

    @Test
    fun `vendor ids map to the families that claim them`() {
        assertEquals(PrinterBrand.EPSON, UsbVendorId.familyForVid(UsbVendorId.EPSON))
        assertEquals(PrinterBrand.STAR, UsbVendorId.familyForVid(UsbVendorId.STAR))
        assertEquals(PrinterBrand.SUNMI, UsbVendorId.familyForVid(UsbVendorId.SUNMI))
        assertEquals(null, UsbVendorId.familyForVid(0x0001))
    }
}

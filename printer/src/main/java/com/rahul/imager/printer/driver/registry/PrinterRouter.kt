package com.rahul.imager.printer.driver.registry

import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.UsbVendorId

/** The family and transport a job should be sent through, or a typed refusal. */
sealed interface RoutingDecision {

    /**
     * Send the job through [brand] over [transport].
     *
     * [compatibilityFor] is set only when [brand] is the generic ESC/POS driver standing in for a
     * vendor family whose SDK is not in this build. It carries the family the printer actually
     * belongs to, so the driver and the UI can keep calling it by its real name.
     */
    data class Use(
        val brand: PrinterBrand,
        val transport: TransportType,
        val compatibilityFor: PrinterBrand? = null,
    ) : RoutingDecision

    /** The job cannot be routed, for a reason the user can act on. */
    data class Refuse(val category: PrintCategory, val detail: String) : RoutingDecision
}

/**
 * Decides which driver family a saved printer belongs to.
 *
 * Pure and side-effect free — no Context, no SDK, no I/O — so the routing rules can be tested
 * exhaustively on a host JVM. [PrinterDriverRegistry] supplies the two facts this needs from the
 * outside world: which families are available, and what the USB device actually enumerates as.
 *
 * Family first, transport second. The family comes from the brand and model FROZEN when the
 * printer was added, never from the user-editable display name, so renaming a printer can never
 * re-route it.
 */
object PrinterRouter {

    /**
     * @param saved the printer to route.
     * @param availableFamilies the families whose driver is present and usable on this device.
     * @param usbVendorId the VID the attached USB device actually reports, when this is a USB
     *   printer and a device was found. For a USB entry the VID is physical truth and OUTRANKS the
     *   saved brand: a device that enumerates as Epson is an Epson whatever the user labelled it.
     */
    fun decide(
        saved: SavedPrinter,
        availableFamilies: Set<PrinterBrand>,
        usbVendorId: Int? = null,
    ): RoutingDecision {
        // 1. Family, from the frozen brand — corrected by the USB VID when they disagree.
        var brand = saved.brand
        if (saved.transport == TransportType.USB && usbVendorId != null) {
            UsbVendorId.familyForVid(usbVendorId)?.let { physical ->
                if (physical != brand) brand = physical
            }
        }
        if (brand == PrinterBrand.UNKNOWN) brand = PrinterBrand.GENERIC_ESCPOS

        // 2. An address is mandatory for every transport except the built-in head.
        if (saved.transport != TransportType.INNER && saved.identifier.isBlank()) {
            return RoutingDecision.Refuse(
                PrintCategory.MISSING_ADDRESS,
                "This printer has no saved address.",
            )
        }

        // 3. The family has to be present — or be one that speaks plain ESC/POS over a socket.
        //
        //    Most receipt printers are ESC/POS devices underneath; the vendor SDK buys discovery,
        //    status reporting and model-specific quirks, not the ability to print at all. So when
        //    a socket-reachable family's SDK is missing we fall back to the generic driver rather
        //    than refusing a printer the app can demonstrably drive. Families welded into a host
        //    terminal have no socket to fall back to, so for them a missing SDK is still a typed
        //    refusal.
        if (brand !in availableFamilies) {
            val canSpeakEscPos = brand in ESCPOS_COMPATIBLE_FAMILIES &&
                saved.transport in ESCPOS_TRANSPORTS &&
                PrinterBrand.GENERIC_ESCPOS in availableFamilies
            if (!canSpeakEscPos) {
                return RoutingDecision.Refuse(
                    PrintCategory.SDK_NOT_BUNDLED,
                    "The $brand driver is not available in this build.",
                )
            }
            return RoutingDecision.Use(
                brand = PrinterBrand.GENERIC_ESCPOS,
                transport = saved.transport,
                compatibilityFor = brand,
            )
        }

        // 4. Some families are connection-independent: a built-in head has exactly one way in.
        if (brand in CONNECTION_INDEPENDENT_FAMILIES) {
            return RoutingDecision.Use(brand, TransportType.INNER)
        }

        return RoutingDecision.Use(brand, saved.transport)
    }

    /**
     * Families whose driver does not vary by transport, because the hardware is welded into the
     * host device and reached through a bound service.
     */
    val CONNECTION_INDEPENDENT_FAMILIES: Set<PrinterBrand> = setOf(
        PrinterBrand.LANDI,
        PrinterBrand.DEJAVOO,
    )

    /**
     * Families the generic ESC/POS driver can stand in for when their vendor SDK is absent.
     *
     * All three are ordinary ESC/POS receipt printers reachable over a socket: Epson defined the
     * command set, and the Volcora/Xprinter and Volcora V2/SPRT units implement it. Membership
     * here is a claim about the WIRE PROTOCOL, not about feature parity — the fallback prints, but
     * it does not get the vendor's discovery, live status or model quirks.
     *
     * Landi and Dejavoo are deliberately absent: their heads are bound services inside a payment
     * terminal, with no socket for ESC/POS to travel over.
     */
    val ESCPOS_COMPATIBLE_FAMILIES: Set<PrinterBrand> = setOf(
        PrinterBrand.EPSON,
        PrinterBrand.VOLCORA,
        PrinterBrand.VOLCORA_V2,
    )

    /** The transports the ESC/POS fallback can actually travel over. */
    private val ESCPOS_TRANSPORTS: Set<TransportType> = setOf(
        TransportType.LAN,
        TransportType.BLUETOOTH,
        TransportType.USB,
    )
}

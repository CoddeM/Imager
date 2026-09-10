package com.rahul.imager.printer.driver.registry

import android.content.Context
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType

/**
 * How one printer FAMILY offers itself to the app.
 *
 * This interface lives in `src/main`, but several implementations live under
 * `src/optional/<family>/java` and are only compiled when their vendor SDK has been dropped into
 * `printer/libs/`. [PrinterDriverRegistry] finds those by name through reflection, so the absence
 * of an SDK is simply an absent factory — never a missing class at compile time, never a crash at
 * runtime, and never a silent fallback to some other vendor's driver.
 */
interface PrinterDriverFactory {

    val brand: PrinterBrand

    /** The transports this family can actually be reached over. */
    val supportedTransports: Set<TransportType>

    /**
     * The local artifact this family needs, or `null` when it needs none.
     *
     * Shown in the UI as the reason a family is greyed out.
     */
    val requiredSdkArtifact: String? get() = null

    /**
     * True when this family can be used ON THIS DEVICE right now.
     *
     * Covers device-specific families (a Sunmi built-in head only exists on Sunmi hardware) and
     * API-level gates (StarIO10 needs API 26).
     */
    fun isSupported(context: Context): Boolean

    /** Builds a driver for a saved printer, or `null` when this family cannot serve it. */
    fun create(saved: SavedPrinter): ThermalPrinter?

    /** The scan for this family, when it has one. */
    fun discovery(): PrinterDiscovery? = null

    /**
     * A human-readable reason this family is unavailable, shown next to the greyed-out entry.
     * Only consulted when [isSupported] is false.
     */
    fun unavailableReason(context: Context): String =
        requiredSdkArtifact?.let { "SDK not bundled in this build ($it)" }
            ?: "Not supported on this device"
}

/** How completely this build can drive one family. */
enum class FamilySupport {

    /** The vendor driver is compiled in: discovery, live status and model quirks all work. */
    FULL,

    /**
     * The vendor SDK is absent, but the family speaks ESC/POS over a socket and the generic driver
     * stands in. Printing works; vendor discovery and status reporting do not.
     */
    ESCPOS_COMPATIBILITY,

    /** The driver is not compiled into this build, because its vendor SDK was not bundled. */
    SDK_MISSING,

    /**
     * The driver IS in this build, but the family cannot exist on this hardware - a built-in head
     * welded into another manufacturer's terminal, or an API level the SDK will not run on.
     *
     * Kept apart from [SDK_MISSING] because the two need opposite things from the user: one is
     * fixed by shipping a different build, the other by using a different device.
     */
    DEVICE_UNSUPPORTED,
}

/** What the UI shows about one family in the add-printer flow. */
data class FamilyAvailability(
    val brand: PrinterBrand,
    val supportedTransports: Set<TransportType>,
    val available: Boolean,
    val reason: String?,
    val requiredSdkArtifact: String?,
    val support: FamilySupport =
        if (available) FamilySupport.FULL else FamilySupport.SDK_MISSING,
)

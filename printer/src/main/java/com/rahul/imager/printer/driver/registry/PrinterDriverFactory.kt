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

/** What the UI shows about one family in the add-printer flow. */
data class FamilyAvailability(
    val brand: PrinterBrand,
    val supportedTransports: Set<TransportType>,
    val available: Boolean,
    val reason: String?,
    val requiredSdkArtifact: String?,
)

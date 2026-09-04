package com.rahul.imager.printer.driver.registry

import android.content.Context
import android.util.Log
import com.rahul.imager.printer.discovery.PrinterDiscovery
import com.rahul.imager.printer.domain.PrintCategory
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.ThermalPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.transport.UsbHostPermission

/** The result of resolving a saved printer to a live driver. */
sealed interface PrinterRouting {

    data class Resolved(val printer: ThermalPrinter) : PrinterRouting

    /** A typed refusal. Never a crash, and never a silent fallback to a different brand. */
    data class Unroutable(val category: PrintCategory, val detail: String) : PrinterRouting
}

/**
 * Maps a [SavedPrinter] onto the driver that can print to it.
 *
 * The three families whose SDKs come from Maven are wired in directly. The five families whose
 * SDKs are local artifacts register themselves through [OPTIONAL_FACTORY_CLASSES]: each provides a
 * [PrinterDriverFactory] under `src/optional/<family>/java`, which is only compiled when its SDK
 * is present. Looking those up reflectively means a missing SDK is an absent factory — the module
 * still compiles, the app still runs, and the family reports `PRN-ROUTE-SDK-ABSENT` if a user
 * somehow still has one saved.
 */
object PrinterDriverRegistry {

    private const val TAG = "PrinterDriverRegistry"

    /** Families that are always compiled in. */
    private val BUILT_IN: List<PrinterDriverFactory> = listOf(
        GenericDriverFactory,
        StarDriverFactory,
        SunmiDriverFactory,
    )

    /**
     * Fully-qualified names of the optional factories, paired with the artifact that unlocks them.
     *
     * These names are a CONTRACT with the optional source sets: renaming a factory class there
     * without updating this list silently disables the family.
     */
    private val OPTIONAL_FACTORY_CLASSES: List<Pair<String, String>> = listOf(
        "com.rahul.imager.printer.driver.epson.EpsonDriverFactory" to "ePOS2.jar",
        "com.rahul.imager.printer.driver.volcora.VolcoraDriverFactory" to "printer-lib-3.2.0.aar",
        "com.rahul.imager.printer.driver.volcorav2.VolcoraV2DriverFactory" to
            "printersdkv5.7.2.jar",
        "com.rahul.imager.printer.driver.landi.LandiDriverFactory" to
            "xsuite-omnidriver-api-*.aar",
        "com.rahul.imager.printer.driver.dejavoo.DejavooDriverFactory" to "peripheral_v1.0.aar",
    )

    /** Brands that exist in the app but whose driver may be missing from this build. */
    private val OPTIONAL_BRANDS: Map<PrinterBrand, String> = mapOf(
        PrinterBrand.EPSON to "ePOS2.jar",
        PrinterBrand.VOLCORA to "printer-lib-3.2.0.aar",
        PrinterBrand.VOLCORA_V2 to "printersdkv5.7.2.jar",
        PrinterBrand.LANDI to "xsuite-omnidriver-api-*.aar",
        PrinterBrand.DEJAVOO to "peripheral_v1.0.aar",
    )

    /**
     * Every factory this build actually has, resolved once.
     *
     * A factory class that is absent, cannot be instantiated, or throws while loading is skipped
     * with a log line — an optional SDK must never be able to take the app down.
     */
    val factories: List<PrinterDriverFactory> by lazy {
        BUILT_IN + OPTIONAL_FACTORY_CLASSES.mapNotNull { (className, artifact) ->
            loadOptionalFactory(className, artifact)
        }
    }

    private fun loadOptionalFactory(className: String, artifact: String): PrinterDriverFactory? =
        runCatching {
            val type = Class.forName(className)
            // Kotlin `object` declarations expose their singleton through INSTANCE.
            val instance = runCatching { type.getField("INSTANCE").get(null) }
                .getOrElse { type.getDeclaredConstructor().newInstance() }
            instance as PrinterDriverFactory
        }.onSuccess {
            Log.i(TAG, "Optional driver family enabled: ${it.brand} (from $artifact)")
        }.onFailure {
            Log.i(TAG, "Optional driver family absent: $className (needs $artifact)")
        }.getOrNull()

    /** The factory for [brand], if this build has one. */
    fun factoryFor(brand: PrinterBrand): PrinterDriverFactory? =
        factories.firstOrNull { it.brand == brand }

    /** Families that can be used on this device right now. */
    fun availableFamilies(context: Context): Set<PrinterBrand> =
        factories.filter { it.isSupported(context) }.map { it.brand }.toSet()

    /**
     * Everything the add-printer UI needs to render the family list, including the families it
     * must show greyed out with a reason rather than hide.
     */
    fun availability(context: Context): List<FamilyAvailability> {
        val present = factories.associateBy { it.brand }
        val known = buildList {
            addAll(present.keys)
            addAll(OPTIONAL_BRANDS.keys)
        }.distinct()

        return known.map { brand ->
            val factory = present[brand]
            when {
                factory == null -> FamilyAvailability(
                    brand = brand,
                    supportedTransports = emptySet(),
                    available = false,
                    reason = "SDK not bundled in this build",
                    requiredSdkArtifact = OPTIONAL_BRANDS[brand],
                )

                !factory.isSupported(context) -> FamilyAvailability(
                    brand = brand,
                    supportedTransports = factory.supportedTransports,
                    available = false,
                    reason = factory.unavailableReason(context),
                    requiredSdkArtifact = factory.requiredSdkArtifact,
                )

                else -> FamilyAvailability(
                    brand = brand,
                    supportedTransports = factory.supportedTransports,
                    available = true,
                    reason = null,
                    requiredSdkArtifact = factory.requiredSdkArtifact,
                )
            }
        }.sortedBy { it.brand.ordinal }
    }

    /** Every discovery this build can run. */
    fun discoveries(context: Context): List<PrinterDiscovery> = factories
        .filter { it.isSupported(context) }
        .mapNotNull { it.discovery() }
        .distinct()

    /**
     * Resolves a saved printer to a driver.
     *
     * For a USB entry the attached device is looked up first so the routing decision can be made
     * on the VID the hardware actually reports rather than on the label it was saved under.
     */
    fun route(context: Context, saved: SavedPrinter): PrinterRouting {
        val usbVendorId = if (saved.transport == TransportType.USB) {
            runCatching { UsbHostPermission.findDevice(context, saved.identifier)?.vendorId }
                .getOrNull()
        } else {
            null
        }

        return when (
            val decision = PrinterRouter.decide(saved, availableFamilies(context), usbVendorId)
        ) {
            is RoutingDecision.Refuse -> PrinterRouting.Unroutable(decision.category, decision.detail)

            is RoutingDecision.Use -> {
                val factory = factoryFor(decision.brand)
                    ?: return PrinterRouting.Unroutable(
                        PrintCategory.SDK_NOT_BUNDLED,
                        "No driver factory for ${decision.brand}.",
                    )
                val effective = saved.copy(brand = decision.brand, transport = decision.transport)
                val printer = factory.create(effective)
                    ?: return PrinterRouting.Unroutable(
                        PrintCategory.NO_DRIVER_FOR_MODEL,
                        "The ${decision.brand} driver cannot serve a " +
                            "${decision.transport} connection.",
                    )
                PrinterRouting.Resolved(printer)
            }
        }
    }
}

package com.rahul.imager.usecase

import android.content.Context
import com.rahul.imager.data.PrinterConnectionManager
import com.rahul.imager.data.PrinterStore
import com.rahul.imager.printer.discovery.DiscoveredPrinter
import com.rahul.imager.printer.domain.PaperProfiles
import com.rahul.imager.printer.domain.PaperWidthResolver
import com.rahul.imager.printer.domain.PrinterBrand
import com.rahul.imager.printer.domain.SavedPrinter
import com.rahul.imager.printer.domain.TransportType
import com.rahul.imager.printer.driver.registry.FamilyAvailability
import com.rahul.imager.printer.driver.registry.PrinterDriverRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a scan for printers.
 *
 * When no family is named, every family that supports the transport is scanned AT ONCE and the
 * results are merged: from the user's point of view there is one list of printers, not one list
 * per manufacturer.
 */
@Singleton
class DiscoverPrintersUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    operator fun invoke(
        transport: TransportType,
        brand: PrinterBrand? = null,
    ): Flow<DiscoveredPrinter> {
        val discoveries = PrinterDriverRegistry.discoveries(context)
            .filter { transport in it.transports }
            .filter { brand == null || it.brand == brand }
            .filter { it.isSupported(context) }

        if (discoveries.isEmpty()) return emptyFlow()
        return discoveries.map { it.discover(context, transport) }.merge()
    }

    /** Which families this build has, and why the missing ones are missing. */
    fun availability(): List<FamilyAvailability> = PrinterDriverRegistry.availability(context)

    /** The transports at least one available family can scan. */
    fun availableTransports(): Set<TransportType> = PrinterDriverRegistry
        .factories
        .filter { it.isSupported(context) }
        .flatMap { it.supportedTransports }
        .toSet()
}

/**
 * Turns a discovered (or manually entered) printer into a saved one.
 *
 * This is where the model string is FROZEN. Everything downstream — driver routing, paper width —
 * keys off the brand and model captured here, never off the display name, so renaming a printer
 * later can never change how it is driven.
 */
@Singleton
class SavePrinterUseCase @Inject constructor(
    private val printerStore: PrinterStore,
    private val connectionManager: PrinterConnectionManager,
) {

    suspend operator fun invoke(
        discovered: DiscoveredPrinter,
        displayName: String = discovered.name,
        paperProfileId: String? = null,
        makeDefault: Boolean = false,
    ): SavedPrinter {
        val resolvedPaper = paperProfileId ?: PaperWidthResolver.resolve(
            model = discovered.model,
            displayName = discovered.name,
            transport = discovered.transport,
        ).profile.id

        val printer = SavedPrinter(
            id = UUID.randomUUID().toString(),
            displayName = displayName.trim().ifBlank { discovered.name },
            brand = discovered.brand,
            transport = discovered.transport,
            identifier = discovered.identifier,
            port = discovered.port,
            paperProfileId = resolvedPaper,
            model = discovered.model,
            isDefault = makeDefault,
            addedAtEpochMs = System.currentTimeMillis(),
        )

        printerStore.upsert(printer)
        if (makeDefault) printerStore.setDefault(printer.id)
        connectionManager.connect(printer)
        return printer
    }

    /** Updates an existing printer in place, keeping its id and its frozen brand and model. */
    suspend fun update(printer: SavedPrinter) = printerStore.upsert(printer)
}

/** Removes a printer and everything the app remembers about it. */
@Singleton
class DeletePrinterUseCase @Inject constructor(
    private val printerStore: PrinterStore,
    private val connectionManager: PrinterConnectionManager,
) {

    suspend operator fun invoke(printer: SavedPrinter) {
        connectionManager.disconnect(printer)
        connectionManager.forget(printer.id)
        printerStore.delete(printer.id)
    }
}

/** Makes a printer the one the one-tap print path uses. */
@Singleton
class SetDefaultPrinterUseCase @Inject constructor(
    private val printerStore: PrinterStore,
    private val connectionManager: PrinterConnectionManager,
) {

    suspend operator fun invoke(printer: SavedPrinter) {
        printerStore.setDefault(printer.id)
        // Connect straight away, so the next print really is one tap.
        connectionManager.connect(printer.copy(isDefault = true))
    }
}

/**
 * Builds the candidate printer entry for a manually entered LAN address.
 *
 * Manual entry exists because plenty of network printers do not answer any discovery protocol at
 * all, and a user who knows the IP should not be blocked by that.
 */
@Singleton
class ManualLanPrinterUseCase @Inject constructor() {

    operator fun invoke(
        ipAddress: String,
        port: Int,
        brand: PrinterBrand = PrinterBrand.GENERIC_ESCPOS,
        name: String = ipAddress,
    ): DiscoveredPrinter = DiscoveredPrinter(
        brand = brand,
        transport = TransportType.LAN,
        identifier = ipAddress.trim(),
        name = name.trim().ifBlank { ipAddress.trim() },
        model = null,
        port = port,
        detail = "${ipAddress.trim()}:$port",
    )

    /** True when [ipAddress] is a plausible IPv4 address or hostname. */
    fun isValidAddress(ipAddress: String): Boolean {
        val trimmed = ipAddress.trim()
        if (trimmed.isEmpty()) return false
        val octets = trimmed.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) return true
        // Hostnames are allowed too: plenty of printers are reachable by mDNS name.
        return trimmed.matches(HOSTNAME)
    }

    private companion object {
        val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")
    }
}

/**
 * The built-in printer families that exist on THIS device, if any.
 *
 * The add-printer flow only offers "Built-in printer" when this returns something, because on a
 * normal phone the option would be a dead end.
 */
@Singleton
class DetectBuiltInPrinterUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    operator fun invoke(): List<DiscoveredPrinter> = PrinterDriverRegistry
        .factories
        .filter { TransportType.INNER in it.supportedTransports && it.isSupported(context) }
        .map { factory ->
            DiscoveredPrinter(
                brand = factory.brand,
                transport = TransportType.INNER,
                identifier = "",
                name = "${factory.brand.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                    "built-in printer",
                model = PaperWidthResolver.defaultHostDevice(),
                detail = PaperWidthResolver.defaultHostDevice(),
            )
        }

    /** Paper profile suggested for a built-in head on this device. */
    fun suggestedPaper(discovered: DiscoveredPrinter) = PaperWidthResolver.resolve(
        model = discovered.model,
        displayName = discovered.name,
        transport = TransportType.INNER,
    ).profile.id.ifEmpty { PaperProfiles.FALLBACK.id }
}

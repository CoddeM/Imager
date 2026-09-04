package com.rahul.imager.printer.domain

import kotlinx.serialization.Serializable

/**
 * The printer families this app knows how to drive.
 *
 * A brand is decided ONCE, when the user adds a printer, and is then frozen on [SavedPrinter].
 * It is never re-derived from user-editable text later on, because a user renaming their printer
 * to "Kitchen" must not silently re-route their jobs to a different driver.
 */
@Serializable
enum class PrinterBrand {
    SUNMI,
    STAR,
    EPSON,
    VOLCORA,
    VOLCORA_V2,
    LANDI,
    DEJAVOO,
    GENERIC_ESCPOS,
    UNKNOWN,
}

/**
 * How the app physically reaches a printer.
 *
 * [INNER] is a built-in head wired into the host device (Sunmi / Landi / Kozen); it has no address
 * and is reached through a bound system service rather than a socket.
 */
@Serializable
enum class TransportType {
    INNER,
    BLUETOOTH,
    LAN,
    USB,
}

/** Default TCP port used by virtually every network-attached thermal printer (RAW / JetDirect). */
const val DEFAULT_LAN_PORT: Int = 9100

/**
 * A printer the user has added and that the app remembers across launches.
 *
 * @param id stable UUID, the key everything else in the app refers to.
 * @param displayName user-editable label. NEVER used for driver routing.
 * @param brand driver family, frozen at add time.
 * @param transport how to reach it, frozen at add time.
 * @param identifier IP for [TransportType.LAN], MAC for [TransportType.BLUETOOTH], device path or
 *   serial for [TransportType.USB], empty for [TransportType.INNER].
 * @param port TCP port, only meaningful for LAN.
 * @param paperProfileId id of the [PaperProfile] in use, which may be a user override of whatever
 *   [PaperWidthResolver] originally resolved.
 * @param model raw model / product string captured when the printer was added. Frozen: it is the
 *   evidence used for width resolution and must not drift when the user renames the printer.
 * @param isDefault whether this is the printer the one-tap print path uses.
 */
@Serializable
data class SavedPrinter(
    val id: String,
    val displayName: String,
    val brand: PrinterBrand,
    val transport: TransportType,
    val identifier: String,
    val port: Int = DEFAULT_LAN_PORT,
    val paperProfileId: String,
    val model: String? = null,
    val isDefault: Boolean = false,
    val addedAtEpochMs: Long = 0L,
)

/**
 * A hardware status snapshot, as far as the transport is able to report one.
 *
 * Many cheap ESC/POS heads never answer a status query at all; that is not an error and is
 * represented by [ThermalPrinter.queryStatus] returning `null` rather than by a fabricated status.
 */
data class PrinterStatus(
    val online: Boolean,
    val paperOut: Boolean = false,
    val paperLow: Boolean = false,
    val coverOpen: Boolean = false,
    val overheat: Boolean = false,
    val busy: Boolean = false,
    val rawVendorCode: String? = null,
)

package com.rahul.imager.printer.domain

/**
 * The stage of a print attempt a failure belongs to.
 *
 * The [code] is a STORED constant rather than something derived from the enum name: a Kotlin
 * rename must never shift a code that has already shipped, been screenshotted by a user and
 * quoted in a support ticket.
 */
enum class Stage(val code: String) {
    IMAGE("IMG"),
    ROUTING("ROUTE"),
    PERMISSION("PERM"),
    CONNECTION("CONN"),
    SEND("SEND"),
    STATUS("STATUS"),
    POSTPROCESS("POST"),
    UNKNOWN("UNKNOWN"),
}

/**
 * Every distinct failure the print path can produce, with a stable human-readable code.
 *
 * The codes are surfaced verbatim in the UI (small text under the error message) so a user can
 * read one out and a support engineer can map it straight back to a branch in the code.
 */
enum class PrintCategory(val stage: Stage, val code: String) {
    // ---- IMAGE ------------------------------------------------------------------------------
    IMAGE_DECODE_FAILED(Stage.IMAGE, "PRN-IMG-DECODE-FAILED"),
    IMAGE_TOO_LARGE(Stage.IMAGE, "PRN-IMG-TOO-LARGE"),
    IMAGE_EMPTY(Stage.IMAGE, "PRN-IMG-EMPTY"),

    // ---- ROUTING ----------------------------------------------------------------------------
    NO_PRINTER_SELECTED(Stage.ROUTING, "PRN-ROUTE-NO-PRINTER"),
    NO_DRIVER_FOR_MODEL(Stage.ROUTING, "PRN-ROUTE-NO-DRIVER"),
    MISSING_ADDRESS(Stage.ROUTING, "PRN-ROUTE-MISSING-ADDRESS"),
    SDK_NOT_BUNDLED(Stage.ROUTING, "PRN-ROUTE-SDK-ABSENT"),

    // ---- PERMISSION -------------------------------------------------------------------------
    BT_PERMISSION_DENIED(Stage.PERMISSION, "PRN-PERM-BT-DENIED"),
    USB_PERMISSION_DENIED(Stage.PERMISSION, "PRN-PERM-USB-DENIED"),
    MEDIA_PERMISSION_DENIED(Stage.PERMISSION, "PRN-PERM-MEDIA-DENIED"),

    // ---- CONNECTION -------------------------------------------------------------------------
    BT_OFF(Stage.CONNECTION, "PRN-CONN-BT-OFF"),
    BT_NOT_PAIRED(Stage.CONNECTION, "PRN-CONN-BT-NOT-PAIRED"),
    BT_PAIRING_FAILED(Stage.CONNECTION, "PRN-CONN-BT-PAIRING-FAILED"),
    BT_PAIRING_TIMEOUT(Stage.CONNECTION, "PRN-CONN-BT-PAIRING-TIMEOUT"),
    BT_UNREACHABLE(Stage.CONNECTION, "PRN-CONN-BT-UNREACHABLE"),
    LAN_UNREACHABLE(Stage.CONNECTION, "PRN-CONN-LAN-UNREACHABLE"),
    LAN_TIMEOUT(Stage.CONNECTION, "PRN-CONN-LAN-TIMEOUT"),
    USB_NOT_ENUMERATED(Stage.CONNECTION, "PRN-CONN-USB-NOT-ENUMERATED"),
    SERVICE_NOT_BOUND(Stage.CONNECTION, "PRN-CONN-SERVICE-NOT-BOUND"),
    CONNECT_TIMEOUT(Stage.CONNECTION, "PRN-CONN-TIMEOUT"),
    CONNECT_FAILED(Stage.CONNECTION, "PRN-CONN-FAILED"),

    // ---- SEND -------------------------------------------------------------------------------
    SEND_TIMEOUT(Stage.SEND, "PRN-SEND-TIMEOUT"),
    DISCONNECTED_MID_PRINT(Stage.SEND, "PRN-SEND-DISCONNECTED-MID-PRINT"),
    UNPRINTABLE(Stage.SEND, "PRN-SEND-UNPRINTABLE"),
    PARTIAL_PRINT(Stage.SEND, "PRN-SEND-PARTIAL"),
    SEND_FAILED(Stage.SEND, "PRN-SEND-FAILED"),

    // ---- STATUS -----------------------------------------------------------------------------
    PAPER_OUT(Stage.STATUS, "PRN-STATUS-PAPER-OUT"),
    PAPER_LOW(Stage.STATUS, "PRN-STATUS-PAPER-LOW"),
    PAPER_JAM(Stage.STATUS, "PRN-STATUS-PAPER-JAM"),
    COVER_OPEN(Stage.STATUS, "PRN-STATUS-COVER-OPEN"),
    OFFLINE(Stage.STATUS, "PRN-STATUS-OFFLINE"),
    OVERHEAT(Stage.STATUS, "PRN-STATUS-OVERHEAT"),
    CUTTER_ERROR(Stage.STATUS, "PRN-STATUS-CUTTER-ERROR"),
    BATTERY_LOW(Stage.STATUS, "PRN-STATUS-BATTERY-LOW"),
    PRINTER_BUSY(Stage.STATUS, "PRN-STATUS-BUSY"),

    // ---- POSTPROCESS ------------------------------------------------------------------------
    DISCONNECT_FAILED(Stage.POSTPROCESS, "PRN-POST-DISCONNECT-FAILED"),

    UNKNOWN(Stage.UNKNOWN, "PRN-UNKNOWN"),
}

/** Identifying detail about the printer a failure happened on, for logs and diagnostics. */
data class PrinterContext(
    val brand: PrinterBrand = PrinterBrand.UNKNOWN,
    val transport: TransportType? = null,
    val identifier: String? = null,
    val vendorErrorCode: String? = null,
)

/**
 * A typed print failure.
 *
 * @param clean `true` ONLY when the driver can GUARANTEE that nothing reached paper — the classic
 *   case being a cached-but-dead connection handle that failed on the very first write. The engine
 *   is then allowed to reconnect and re-send the same job in place. Once any content has been
 *   committed the failure is dirty (the default), because re-sending would risk a doubled or
 *   truncated print, which is worse than a clear failure the user can retry themselves.
 */
data class PrintError(
    val category: PrintCategory,
    val context: PrinterContext = PrinterContext(),
    val cause: Throwable? = null,
    val detail: String? = null,
    val clean: Boolean = false,
) {
    val stage: Stage get() = category.stage
    val code: String get() = category.code

    override fun toString(): String = buildString {
        append(code)
        context.identifier?.let { append(" @").append(it) }
        context.vendorErrorCode?.let { append(" vendor=").append(it) }
        detail?.let { append(" - ").append(it) }
        cause?.let {
            append(" (").append(it.javaClass.simpleName).append(": ").append(it.message).append(')')
        }
    }
}

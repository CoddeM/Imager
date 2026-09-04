package com.rahul.imager.printer.driver.sunmi

import com.rahul.imager.printer.domain.PrintCategory
import com.sunmi.externalprinterlibrary.api.Status
import com.sunmi.externalprinterlibrary2.style.CloudPrinterStatus

/**
 * Translates Sunmi status codes into the app's error taxonomy.
 *
 * Sunmi exposes three different status vocabularies — an int from the built-in AIDL service, an
 * int from the external Bluetooth API, and an enum from the CloudPrinter API — so each gets its
 * own mapping and they all land on the same [PrintCategory] values.
 */
object SunmiErrorMapper {

    // -------------------------------------------------------------------------------------------
    // Built-in printer: SunmiPrinterService.updatePrinterState()
    // -------------------------------------------------------------------------------------------

    const val INNER_STATE_NORMAL = 1
    const val INNER_STATE_PREPARING = 2
    const val INNER_STATE_COMMUNICATION_ERROR = 3
    const val INNER_STATE_OUT_OF_PAPER = 4
    const val INNER_STATE_OVERHEATED = 5
    const val INNER_STATE_COVER_OPEN = 6
    const val INNER_STATE_CUTTER_ERROR = 7
    const val INNER_STATE_CUTTER_RECOVERED = 8
    const val INNER_STATE_BLACK_MARK_NOT_FOUND = 9
    const val INNER_STATE_PRINTER_NOT_DETECTED = 505
    const val INNER_STATE_FIRMWARE_UPGRADE_FAILED = 507

    /**
     * Refines a generic send failure using the built-in printer's own state.
     *
     * Returns `null` when the state says nothing useful, so the caller keeps the error it already
     * has rather than replacing a specific failure with a vaguer one.
     */
    fun fromInnerState(state: Int): PrintCategory? = when (state) {
        INNER_STATE_OUT_OF_PAPER -> PrintCategory.PAPER_OUT
        INNER_STATE_OVERHEATED -> PrintCategory.OVERHEAT
        INNER_STATE_COVER_OPEN -> PrintCategory.COVER_OPEN
        INNER_STATE_CUTTER_ERROR -> PrintCategory.CUTTER_ERROR
        INNER_STATE_COMMUNICATION_ERROR -> PrintCategory.DISCONNECTED_MID_PRINT
        INNER_STATE_PRINTER_NOT_DETECTED -> PrintCategory.OFFLINE
        INNER_STATE_FIRMWARE_UPGRADE_FAILED -> PrintCategory.OFFLINE
        INNER_STATE_BLACK_MARK_NOT_FOUND -> PrintCategory.PAPER_JAM
        INNER_STATE_PREPARING -> PrintCategory.PRINTER_BUSY
        else -> null
    }

    /** Human-readable name for the raw state, kept in diagnostics alongside the number. */
    fun innerStateName(state: Int): String = when (state) {
        INNER_STATE_NORMAL -> "normal"
        INNER_STATE_PREPARING -> "preparing"
        INNER_STATE_COMMUNICATION_ERROR -> "communication_error"
        INNER_STATE_OUT_OF_PAPER -> "out_of_paper"
        INNER_STATE_OVERHEATED -> "overheated"
        INNER_STATE_COVER_OPEN -> "cover_open"
        INNER_STATE_CUTTER_ERROR -> "cutter_error"
        INNER_STATE_CUTTER_RECOVERED -> "cutter_recovered"
        INNER_STATE_BLACK_MARK_NOT_FOUND -> "black_mark_not_found"
        INNER_STATE_PRINTER_NOT_DETECTED -> "printer_not_detected"
        INNER_STATE_FIRMWARE_UPGRADE_FAILED -> "firmware_upgrade_failed"
        else -> "state_$state"
    }

    // -------------------------------------------------------------------------------------------
    // External Bluetooth printer: SunmiPrinterApi.getPrinterStatus()
    // -------------------------------------------------------------------------------------------

    /** Maps a [Status] constant from the external Bluetooth API. */
    fun fromExternalStatus(status: Int): PrintCategory? = when (status) {
        Status.DISCONNECT -> PrintCategory.DISCONNECTED_MID_PRINT
        Status.COVER -> PrintCategory.COVER_OPEN
        Status.OUTPAPER -> PrintCategory.PAPER_OUT
        Status.NEAROUTPAPER -> PrintCategory.PAPER_LOW
        Status.OVERHOT -> PrintCategory.OVERHEAT
        Status.ERROR -> PrintCategory.SEND_FAILED
        else -> null
    }

    /** Human-readable name for an external Bluetooth status int. */
    fun externalStatusName(status: Int): String = when (status) {
        Status.DISCONNECT -> "disconnect"
        Status.RUNNING -> "running"
        Status.COVER -> "cover_open"
        Status.OUTPAPER -> "out_of_paper"
        Status.NEAROUTPAPER -> "near_out_of_paper"
        Status.OVERHOT -> "over_hot"
        Status.ERROR -> "error"
        Status.UNKNOW -> "unknown"
        else -> "status_$status"
    }

    // -------------------------------------------------------------------------------------------
    // CloudPrinter (external LAN / USB)
    // -------------------------------------------------------------------------------------------

    /** Maps a [CloudPrinterStatus] from the LAN / USB CloudPrinter API. */
    fun fromCloudStatus(status: CloudPrinterStatus?): PrintCategory = when (status) {
        CloudPrinterStatus.OFFLINE -> PrintCategory.OFFLINE
        CloudPrinterStatus.RUNNING -> PrintCategory.PRINTER_BUSY
        CloudPrinterStatus.NEAR_OUT_PAPER -> PrintCategory.PAPER_LOW
        CloudPrinterStatus.OUT_PAPER -> PrintCategory.PAPER_OUT
        CloudPrinterStatus.JAM_PAPER -> PrintCategory.PAPER_JAM
        CloudPrinterStatus.PICK_PAPER -> PrintCategory.PAPER_JAM
        CloudPrinterStatus.COVER -> PrintCategory.COVER_OPEN
        CloudPrinterStatus.OVER_HOT, CloudPrinterStatus.MOTOR_HOT -> PrintCategory.OVERHEAT
        CloudPrinterStatus.UNKNOWN, null -> PrintCategory.SEND_FAILED
    }
}

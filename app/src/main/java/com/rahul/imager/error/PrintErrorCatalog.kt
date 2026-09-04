package com.rahul.imager.error

import androidx.annotation.StringRes
import com.rahul.imager.R
import com.rahul.imager.printer.domain.PrintCategory

/**
 * How one failure is shown to the user.
 *
 * Three separate strings, because they answer three different questions: what went wrong, why, and
 * what to do about it. The [code] is shown in small monospace text underneath so a user can read
 * it out and a support engineer can find the exact branch it came from.
 */
data class PrintErrorPresentation(
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
    @param:StringRes val actionRes: Int,
    val code: String,
    /** Whether retrying the same job unchanged has any chance of working. */
    val retryable: Boolean,
)

/**
 * Maps every [PrintCategory] to user-facing text.
 *
 * The mapping is TOTAL and exhaustive — the `when` has no `else`, so adding a category to the
 * taxonomy is a compile error here until someone writes the words a user will actually read.
 */
object PrintErrorCatalog {

    fun presentationFor(category: PrintCategory): PrintErrorPresentation = when (category) {
        // ---- IMAGE --------------------------------------------------------------------------
        PrintCategory.IMAGE_DECODE_FAILED -> build(
            category,
            R.string.err_image_decode_failed_title,
            R.string.err_image_decode_failed_message,
            R.string.err_image_decode_failed_action,
            retryable = false,
        )

        PrintCategory.IMAGE_TOO_LARGE -> build(
            category,
            R.string.err_image_too_large_title,
            R.string.err_image_too_large_message,
            R.string.err_image_too_large_action,
            retryable = false,
        )

        PrintCategory.IMAGE_EMPTY -> build(
            category,
            R.string.err_image_empty_title,
            R.string.err_image_empty_message,
            R.string.err_image_empty_action,
            retryable = false,
        )

        // ---- ROUTING ------------------------------------------------------------------------
        PrintCategory.NO_PRINTER_SELECTED -> build(
            category,
            R.string.err_no_printer_title,
            R.string.err_no_printer_message,
            R.string.err_no_printer_action,
            retryable = false,
        )

        PrintCategory.NO_DRIVER_FOR_MODEL -> build(
            category,
            R.string.err_no_driver_title,
            R.string.err_no_driver_message,
            R.string.err_no_driver_action,
            retryable = false,
        )

        PrintCategory.MISSING_ADDRESS -> build(
            category,
            R.string.err_missing_address_title,
            R.string.err_missing_address_message,
            R.string.err_missing_address_action,
            retryable = false,
        )

        PrintCategory.SDK_NOT_BUNDLED -> build(
            category,
            R.string.err_sdk_absent_title,
            R.string.err_sdk_absent_message,
            R.string.err_sdk_absent_action,
            retryable = false,
        )

        // ---- PERMISSION ---------------------------------------------------------------------
        PrintCategory.BT_PERMISSION_DENIED -> build(
            category,
            R.string.err_bt_permission_title,
            R.string.err_bt_permission_message,
            R.string.err_bt_permission_action,
            retryable = true,
        )

        PrintCategory.USB_PERMISSION_DENIED -> build(
            category,
            R.string.err_usb_permission_title,
            R.string.err_usb_permission_message,
            R.string.err_usb_permission_action,
            retryable = true,
        )

        PrintCategory.MEDIA_PERMISSION_DENIED -> build(
            category,
            R.string.err_media_permission_title,
            R.string.err_media_permission_message,
            R.string.err_media_permission_action,
            retryable = true,
        )

        // ---- CONNECTION ---------------------------------------------------------------------
        PrintCategory.BT_OFF -> build(
            category,
            R.string.err_bt_off_title,
            R.string.err_bt_off_message,
            R.string.err_bt_off_action,
            retryable = true,
        )

        PrintCategory.BT_NOT_PAIRED -> build(
            category,
            R.string.err_bt_not_paired_title,
            R.string.err_bt_not_paired_message,
            R.string.err_bt_not_paired_action,
            retryable = true,
        )

        PrintCategory.BT_PAIRING_FAILED -> build(
            category,
            R.string.err_bt_pairing_failed_title,
            R.string.err_bt_pairing_failed_message,
            R.string.err_bt_pairing_failed_action,
            retryable = true,
        )

        PrintCategory.BT_PAIRING_TIMEOUT -> build(
            category,
            R.string.err_bt_pairing_timeout_title,
            R.string.err_bt_pairing_timeout_message,
            R.string.err_bt_pairing_timeout_action,
            retryable = true,
        )

        PrintCategory.BT_UNREACHABLE -> build(
            category,
            R.string.err_bt_unreachable_title,
            R.string.err_bt_unreachable_message,
            R.string.err_bt_unreachable_action,
            retryable = true,
        )

        PrintCategory.LAN_UNREACHABLE -> build(
            category,
            R.string.err_lan_unreachable_title,
            R.string.err_lan_unreachable_message,
            R.string.err_lan_unreachable_action,
            retryable = true,
        )

        PrintCategory.LAN_TIMEOUT -> build(
            category,
            R.string.err_lan_timeout_title,
            R.string.err_lan_timeout_message,
            R.string.err_lan_timeout_action,
            retryable = true,
        )

        PrintCategory.USB_NOT_ENUMERATED -> build(
            category,
            R.string.err_usb_not_enumerated_title,
            R.string.err_usb_not_enumerated_message,
            R.string.err_usb_not_enumerated_action,
            retryable = true,
        )

        PrintCategory.SERVICE_NOT_BOUND -> build(
            category,
            R.string.err_service_not_bound_title,
            R.string.err_service_not_bound_message,
            R.string.err_service_not_bound_action,
            retryable = true,
        )

        PrintCategory.CONNECT_TIMEOUT -> build(
            category,
            R.string.err_connect_timeout_title,
            R.string.err_connect_timeout_message,
            R.string.err_connect_timeout_action,
            retryable = true,
        )

        PrintCategory.CONNECT_FAILED -> build(
            category,
            R.string.err_connect_failed_title,
            R.string.err_connect_failed_message,
            R.string.err_connect_failed_action,
            retryable = true,
        )

        // ---- SEND ---------------------------------------------------------------------------
        PrintCategory.SEND_TIMEOUT -> build(
            category,
            R.string.err_send_timeout_title,
            R.string.err_send_timeout_message,
            R.string.err_send_timeout_action,
            retryable = true,
        )

        PrintCategory.DISCONNECTED_MID_PRINT -> build(
            category,
            R.string.err_disconnected_mid_print_title,
            R.string.err_disconnected_mid_print_message,
            R.string.err_disconnected_mid_print_action,
            retryable = true,
        )

        PrintCategory.UNPRINTABLE -> build(
            category,
            R.string.err_unprintable_title,
            R.string.err_unprintable_message,
            R.string.err_unprintable_action,
            retryable = false,
        )

        PrintCategory.PARTIAL_PRINT -> build(
            category,
            R.string.err_partial_print_title,
            R.string.err_partial_print_message,
            R.string.err_partial_print_action,
            retryable = true,
        )

        PrintCategory.SEND_FAILED -> build(
            category,
            R.string.err_send_failed_title,
            R.string.err_send_failed_message,
            R.string.err_send_failed_action,
            retryable = true,
        )

        // ---- STATUS -------------------------------------------------------------------------
        PrintCategory.PAPER_OUT -> build(
            category,
            R.string.err_paper_out_title,
            R.string.err_paper_out_message,
            R.string.err_paper_out_action,
            retryable = true,
        )

        PrintCategory.PAPER_LOW -> build(
            category,
            R.string.err_paper_low_title,
            R.string.err_paper_low_message,
            R.string.err_paper_low_action,
            retryable = true,
        )

        PrintCategory.PAPER_JAM -> build(
            category,
            R.string.err_paper_jam_title,
            R.string.err_paper_jam_message,
            R.string.err_paper_jam_action,
            retryable = true,
        )

        PrintCategory.COVER_OPEN -> build(
            category,
            R.string.err_cover_open_title,
            R.string.err_cover_open_message,
            R.string.err_cover_open_action,
            retryable = true,
        )

        PrintCategory.OFFLINE -> build(
            category,
            R.string.err_offline_title,
            R.string.err_offline_message,
            R.string.err_offline_action,
            retryable = true,
        )

        PrintCategory.OVERHEAT -> build(
            category,
            R.string.err_overheat_title,
            R.string.err_overheat_message,
            R.string.err_overheat_action,
            retryable = true,
        )

        PrintCategory.CUTTER_ERROR -> build(
            category,
            R.string.err_cutter_title,
            R.string.err_cutter_message,
            R.string.err_cutter_action,
            retryable = true,
        )

        PrintCategory.BATTERY_LOW -> build(
            category,
            R.string.err_battery_low_title,
            R.string.err_battery_low_message,
            R.string.err_battery_low_action,
            retryable = true,
        )

        PrintCategory.PRINTER_BUSY -> build(
            category,
            R.string.err_busy_title,
            R.string.err_busy_message,
            R.string.err_busy_action,
            retryable = true,
        )

        // ---- POSTPROCESS / UNKNOWN ----------------------------------------------------------
        PrintCategory.DISCONNECT_FAILED -> build(
            category,
            R.string.err_disconnect_failed_title,
            R.string.err_disconnect_failed_message,
            R.string.err_disconnect_failed_action,
            retryable = true,
        )

        PrintCategory.UNKNOWN -> build(
            category,
            R.string.err_unknown_title,
            R.string.err_unknown_message,
            R.string.err_unknown_action,
            retryable = true,
        )
    }

    private fun build(
        category: PrintCategory,
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes action: Int,
        retryable: Boolean,
    ) = PrintErrorPresentation(title, message, action, category.code, retryable)
}

package com.rahul.imager.printer.escpos

import com.rahul.imager.printer.raster.Alignment

/**
 * The ESC/POS byte sequences this app emits.
 *
 * This is the WHOLE vocabulary. Nothing outside this file may invent a command: every sequence
 * here is one of the handful that is genuinely universal across the thermal heads this app drives,
 * and anything beyond them is vendor-specific and belongs in that vendor's SDK driver instead.
 *
 * Notably absent is a "print density" command. There is no portable ESC/POS density command, so
 * darkness is implemented in the image domain (gamma / contrast / threshold) and, only where a
 * vendor SDK exposes a real parameter, mapped onto that parameter.
 */
object EscPos {

    /** `ESC @` — initialize printer: clears buffers and restores default settings. */
    val INITIALIZE: ByteArray = byteArrayOf(0x1B, 0x40)

    /** `GS V 1` — partial cut. Not every unit has a cutter, so callers wrap this in runCatching. */
    val PARTIAL_CUT: ByteArray = byteArrayOf(0x1D, 0x56, 0x01)

    /** `DLE EOT 1` — real-time printer status. */
    const val STATUS_PRINTER: Int = 1

    /** `DLE EOT 2` — real-time offline-cause status. */
    const val STATUS_OFFLINE_CAUSE: Int = 2

    /** `DLE EOT 4` — real-time paper sensor status. */
    const val STATUS_PAPER_SENSOR: Int = 4

    /** `ESC a n` — 0 left, 1 centre, 2 right. */
    fun align(alignment: Alignment): ByteArray {
        val n: Byte = when (alignment) {
            Alignment.LEFT -> 0
            Alignment.CENTER -> 1
            Alignment.RIGHT -> 2
        }
        return byteArrayOf(0x1B, 0x61, n)
    }

    /** `ESC d n` — feed n lines. */
    fun feedLines(lines: Int): ByteArray =
        byteArrayOf(0x1B, 0x64, lines.coerceIn(0, 255).toByte())

    /** `DLE EOT n` — real-time status query. */
    fun statusQuery(n: Int): ByteArray = byteArrayOf(0x10, 0x04, n.toByte())

    /**
     * `GS v 0` header for one band of raster data.
     *
     * ```
     * 0x1D 0x76 0x30   GS v 0
     * 0x00             m = 0 (normal size, no scaling)
     * xL xH            bytesPerRow, little-endian
     * yL yH            rows in this band, little-endian
     * <bytesPerRow * rows bytes of packed data follow>
     * ```
     */
    fun rasterHeader(bytesPerRow: Int, rows: Int): ByteArray {
        require(bytesPerRow in 1..0xFFFF) { "bytesPerRow out of range: $bytesPerRow" }
        require(rows in 1..0xFFFF) { "rows out of range: $rows" }
        return byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            (bytesPerRow and 0xFF).toByte(),
            ((bytesPerRow shr 8) and 0xFF).toByte(),
            (rows and 0xFF).toByte(),
            ((rows shr 8) and 0xFF).toByte(),
        )
    }

    /** Header plus packed payload for one band, as a single write. */
    fun rasterBand(bytesPerRow: Int, rows: Int, packed: ByteArray): ByteArray {
        require(packed.size == bytesPerRow * rows) {
            "packed is ${packed.size} bytes, expected ${bytesPerRow * rows}"
        }
        val header = rasterHeader(bytesPerRow, rows)
        val out = ByteArray(header.size + packed.size)
        header.copyInto(out)
        packed.copyInto(out, header.size)
        return out
    }

    // -------------------------------------------------------------------------------------------
    // Status replies
    // -------------------------------------------------------------------------------------------

    /**
     * Decodes a `DLE EOT 1` reply byte.
     *
     * Bit 3 set means the printer is OFFLINE; bit 6 means the paper-feed button is held.
     */
    fun decodePrinterStatus(reply: Byte): EscPosPrinterStatus {
        val b = reply.toInt() and 0xFF
        return EscPosPrinterStatus(
            offline = (b and 0x08) != 0,
            feedButtonPressed = (b and 0x40) != 0,
            drawerOpen = (b and 0x04) != 0,
            raw = b,
        )
    }

    /**
     * Decodes a `DLE EOT 2` reply byte (why the printer is offline).
     *
     * Bit 2 is the cover, bit 3 the feed button, bit 5 a paper-end stop, bit 6 an error state.
     */
    fun decodeOfflineCause(reply: Byte): EscPosOfflineCause {
        val b = reply.toInt() and 0xFF
        return EscPosOfflineCause(
            coverOpen = (b and 0x04) != 0,
            paperEndStop = (b and 0x20) != 0,
            errorOccurred = (b and 0x40) != 0,
            raw = b,
        )
    }

    /**
     * Decodes a `DLE EOT 4` reply byte.
     *
     * Bits 2 and 3 together mean paper NEAR end; bits 5 and 6 together mean paper OUT.
     */
    fun decodePaperStatus(reply: Byte): EscPosPaperStatus {
        val b = reply.toInt() and 0xFF
        return EscPosPaperStatus(
            paperNearEnd = (b and 0x0C) == 0x0C,
            paperOut = (b and 0x60) == 0x60,
            raw = b,
        )
    }
}

/** Decoded `DLE EOT 1` reply. */
data class EscPosPrinterStatus(
    val offline: Boolean,
    val feedButtonPressed: Boolean,
    val drawerOpen: Boolean,
    val raw: Int,
)

/** Decoded `DLE EOT 2` reply. */
data class EscPosOfflineCause(
    val coverOpen: Boolean,
    val paperEndStop: Boolean,
    val errorOccurred: Boolean,
    val raw: Int,
)

/** Decoded `DLE EOT 4` reply. */
data class EscPosPaperStatus(
    val paperNearEnd: Boolean,
    val paperOut: Boolean,
    val raw: Int,
)

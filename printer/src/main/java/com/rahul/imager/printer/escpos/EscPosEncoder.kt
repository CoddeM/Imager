package com.rahul.imager.printer.escpos

import com.rahul.imager.printer.raster.RasterJob

/**
 * Turns a [RasterJob] into the exact byte writes a generic ESC/POS head expects.
 *
 * The encoder is transport-agnostic: LAN, Bluetooth and USB all send the same bytes and differ
 * only in how they are pushed. It is also pure, so its output can be asserted byte-for-byte in a
 * host-JVM test.
 */
object EscPosEncoder {

    /** Bytes sent once, before the first band: initialize, then set the alignment. */
    fun preamble(job: RasterJob): ByteArray =
        EscPos.INITIALIZE + EscPos.align(job.options.alignment)

    /**
     * One `GS v 0` command per band, in print order.
     *
     * A photo is far too tall to push as a single raster command — the head's input buffer would
     * overflow — so the job is split into bands of [com.rahul.imager.printer.raster.RasterCore
     * .DEFAULT_BAND_ROWS] rows, each of which is its own complete command.
     */
    fun bands(job: RasterJob): List<ByteArray> = job.bands.map { band ->
        EscPos.rasterBand(
            bytesPerRow = job.bytesPerRow,
            rows = band.last - band.first + 1,
            packed = job.bandBytes(band),
        )
    }

    /**
     * Bytes sent once, after the last band: feed the print clear of the head, then cut.
     *
     * The cut is only emitted when the paper profile actually has a cutter; sending it to a unit
     * without one is harmless on most firmware but prints a stray character on some.
     */
    fun postamble(job: RasterJob): ByteArray {
        var out = EscPos.feedLines(job.options.feedLinesAfter)
        if (job.options.cutAfter && job.paper.supportsCutter) {
            out += EscPos.PARTIAL_CUT
        }
        return out
    }
}

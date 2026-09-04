package com.rahul.imager.printer.raster

import android.graphics.Bitmap
import com.rahul.imager.printer.domain.PaperProfile

/**
 * A photo that has been fully reduced to printable dots.
 *
 * A job is produced ONCE and then consumed by both the preview screen and the driver, which is
 * what guarantees the preview is what actually comes out of the printer. It deliberately carries
 * the same picture in two shapes, because the two kinds of driver need different ones:
 *
 *  * [packedRows] for the raw ESC/POS transports, which write `GS v 0` byte-for-byte;
 *  * [monoBitmap] for the vendor SDKs (Star, Sunmi, Epson, ...), which take a `Bitmap`.
 *
 * Both describe exactly the same dots, so a job can be handed to any driver family.
 *
 * @param widthDots always a multiple of 8 — see [RasterCore.targetWidthDots].
 */
class RasterJob(
    val monoBitmap: Bitmap,
    val packedRows: ByteArray,
    val widthDots: Int,
    val heightDots: Int,
    val bytesPerRow: Int,
    val paper: PaperProfile,
    val options: PrintOptions,
) {
    init {
        require(widthDots % 8 == 0) { "widthDots must be a multiple of 8, was $widthDots" }
        require(bytesPerRow == RasterCore.bytesPerRow(widthDots)) {
            "bytesPerRow $bytesPerRow does not match widthDots $widthDots"
        }
        require(packedRows.size == bytesPerRow * heightDots) {
            "packedRows is ${packedRows.size} bytes, expected ${bytesPerRow * heightDots}"
        }
    }

    /** The bands this job is sent in. Every row appears in exactly one band. */
    val bands: List<IntRange> = RasterCore.bandRanges(heightDots)

    /** Length of paper this job consumes, in millimetres, at 203 dpi. */
    val lengthMm: Int get() = heightDots / 8

    /** The packed bytes of one band, ready to follow a `GS v 0` header. */
    fun bandBytes(band: IntRange): ByteArray {
        val from = band.first * bytesPerRow
        val to = (band.last + 1) * bytesPerRow
        return packedRows.copyOfRange(from, to)
    }

    /**
     * One band as its own `Bitmap`, for the SDKs that print bitmaps.
     *
     * The slice shares no pixels with [monoBitmap]; callers may recycle it once sent.
     */
    fun bandBitmap(band: IntRange): Bitmap =
        Bitmap.createBitmap(monoBitmap, 0, band.first, widthDots, band.last - band.first + 1)

    override fun toString(): String =
        "RasterJob(${widthDots}x$heightDots dots, ${bands.size} bands, paper=${paper.id})"
}

/**
 * The result of running the pipeline.
 *
 * Warnings are non-fatal facts the user should see in the preview screen (for example that their
 * photo is lower resolution than the paper), not errors.
 */
sealed interface RasterOutcome {
    data class Success(val job: RasterJob, val warnings: List<RasterWarning> = emptyList()) :
        RasterOutcome

    data class Failure(val error: com.rahul.imager.printer.domain.PrintError) : RasterOutcome
}

/** A non-fatal observation about a raster, surfaced in the preview UI. */
enum class RasterWarning {
    /** The photo has fewer pixels across than the paper has dots, so it prints narrower. */
    SOURCE_NARROWER_THAN_PAPER,

    /** A landscape photo would print very small at this paper width; a 90 degree turn would help. */
    ROTATION_SUGGESTED,

    /** The print is long enough to be worth warning about before the user commits paper to it. */
    VERY_LONG_PRINT,

    /**
     * The photo could only be decoded by shrinking it further than asked for.
     *
     * The print still happens — always preferable to refusing the photo — but it is softer than it
     * would otherwise have been, and the user deserves to know why.
     */
    DECODED_AT_LOWER_QUALITY,

    /**
     * The photo was so tall relative to the paper that it had to be narrowed to stay within the
     * maximum print length. Better a smaller print than no print.
     */
    SHRUNK_TO_FIT_PAPER,
}

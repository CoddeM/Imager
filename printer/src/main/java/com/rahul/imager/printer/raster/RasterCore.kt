package com.rahul.imager.printer.raster

/**
 * The pure, deterministic core of the image pipeline.
 *
 * Everything here works on primitive arrays and has NO Android dependency at all: no `Bitmap`, no
 * `Context`, no coroutines. That is deliberate on three counts.
 *
 *  1. It is the part with real algorithmic risk (bit packing, error diffusion, the multiple-of-8
 *     rule), so it must be unit-testable on a plain host JVM.
 *  2. The preview screen and the drivers call the exact same functions, which is what makes the
 *     on-screen preview a true WYSIWYG of the printed dots.
 *  3. Per-pixel work never touches `Bitmap.getPixel`, which is orders of magnitude slower than
 *     reading the whole plane once with `getPixels`.
 *
 * Conventions used throughout:
 *  * a *grey plane* is an `IntArray` of `width * height` values in `0..255`, row-major;
 *  * a *mono plane* is a `ByteArray` of `width * height` values where `1` means a BLACK dot;
 *  * *packed rows* are MSB-first, `(width + 7) / 8` bytes per row, a set bit meaning BLACK.
 */
object RasterCore {

    /** Rows per band. See [bandRanges] for why banding is mandatory. */
    const val DEFAULT_BAND_ROWS = 192

    /** How often the cancellation hook is polled while diffusing error. */
    private const val ROWS_PER_CANCELLATION_CHECK = 16

    // ---------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------

    /** Bytes needed for one packed row of [widthDots] dots. */
    fun bytesPerRow(widthDots: Int): Int = (widthDots + 7) / 8

    /**
     * Computes the raster width for a job.
     *
     * Three rules, all of them load-bearing:
     *
     *  * margins are taken off both sides of the printable width;
     *  * the result is FLOORED TO A MULTIPLE OF 8. `GS v 0` is byte-packed per row, so a width
     *    that is not a whole number of bytes shears the image diagonally down the page;
     *  * the result NEVER exceeds the source width. Upscaling a photo past its own resolution
     *    just prints bigger, blurrier dots, so the pipeline refuses and the UI tells the user
     *    their photo is lower resolution than the paper.
     *
     * @return a width that is a positive multiple of 8, at least 8.
     */
    fun targetWidthDots(
        paperWidthDots: Int,
        marginDots: Int,
        scalePercent: Int,
        sourceWidthDots: Int,
    ): Int {
        val printable = (paperWidthDots - 2 * marginDots.coerceAtLeast(0)).coerceAtLeast(8)
        val scaled = printable.toLong() * scalePercent.coerceIn(1, 100) / 100
        val capped = minOf(scaled, sourceWidthDots.toLong().coerceAtLeast(1))
        return (capped / 8 * 8).coerceAtLeast(8).toInt()
    }

    /**
     * Splits [heightDots] rows into consecutive bands of at most [bandRows] rows.
     *
     * A full-page photo is hundreds of times taller than a receipt logo and WILL overflow the
     * input buffer of a thermal head if it is pushed in one command. Every driver therefore emits
     * one command per band and flushes between them, which also gives the UI real progress.
     *
     * The split is exact: bands never overlap and never leave a gap, so every row is printed
     * exactly once.
     */
    fun bandRanges(heightDots: Int, bandRows: Int = DEFAULT_BAND_ROWS): List<IntRange> {
        if (heightDots <= 0) return emptyList()
        val step = bandRows.coerceAtLeast(1)
        val bands = ArrayList<IntRange>((heightDots + step - 1) / step)
        var y = 0
        while (y < heightDots) {
            val end = minOf(y + step, heightDots)
            bands += y until end
            y = end
        }
        return bands
    }

    // ---------------------------------------------------------------------------------------
    // Tone
    // ---------------------------------------------------------------------------------------

    /**
     * Collapses a row-major ARGB plane to an 8-bit grey plane.
     *
     * Partially transparent pixels are composited over WHITE, because paper is white and an
     * un-composited alpha channel would otherwise print as solid black.
     */
    fun toGrayscale(argb: IntArray, mode: GrayscaleMode): IntArray {
        val out = IntArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val a = (p ushr 24) and 0xFF
            var r = (p ushr 16) and 0xFF
            var g = (p ushr 8) and 0xFF
            var b = p and 0xFF
            if (a != 255) {
                r = (r * a + 255 * (255 - a)) / 255
                g = (g * a + 255 * (255 - a)) / 255
                b = (b * a + 255 * (255 - a)) / 255
            }
            out[i] = when (mode) {
                GrayscaleMode.LUMINANCE -> (r * 299 + g * 587 + b * 114) / 1000
                GrayscaleMode.AVERAGE -> (r + g + b) / 3
            }.coerceIn(0, 255)
        }
        return out
    }

    /**
     * Applies brightness, contrast, gamma and inversion to a grey plane, in that order.
     *
     * Implemented as a single 256-entry lookup table built once and then applied per pixel, so the
     * cost is O(pixels) with no per-pixel pow() call and no intermediate bitmap allocations.
     *
     * @param brightness -100..+100, additive (scaled onto the 0..255 range).
     * @param contrast -100..+100, multiplicative around a pivot of 128.
     * @param gamma 0.4..2.5, applied as `255 * (v/255)^(1/gamma)`. Because the exponent is the
     *   RECIPROCAL, a LARGER gamma lifts the tone curve and prints LIGHTER, and a smaller gamma
     *   prints darker. The UI's Darkness control therefore maps to gamma inverted.
     */
    fun applyTone(
        gray: IntArray,
        brightness: Float,
        contrast: Float,
        gamma: Float,
        invert: Boolean,
    ): IntArray {
        val lut = toneLut(brightness, contrast, gamma, invert)
        val out = IntArray(gray.size)
        for (i in gray.indices) out[i] = lut[gray[i].coerceIn(0, 255)]
        return out
    }

    /** Builds the 256-entry tone curve used by [applyTone]. Exposed for the live histogram. */
    fun toneLut(brightness: Float, contrast: Float, gamma: Float, invert: Boolean): IntArray {
        val b = brightness.coerceIn(-100f, 100f) * 1.28f
        val c = contrast.coerceIn(-100f, 100f)
        // Standard contrast factor: -100 flattens towards mid grey, +100 approaches a hard cut.
        val factor = (259f * (c + 255f)) / (255f * (259f - c))
        val invGamma = 1f / gamma.coerceIn(0.4f, 2.5f)
        val lut = IntArray(256)
        for (v in 0..255) {
            var x = v + b
            x = factor * (x - 128f) + 128f
            x = x.coerceIn(0f, 255f)
            x = 255f * Math.pow((x / 255f).toDouble(), invGamma.toDouble()).toFloat()
            var r = Math.round(x).coerceIn(0, 255)
            if (invert) r = 255 - r
            lut[v] = r
        }
        return lut
    }

    /** Counts how many pixels fall in each of the 256 grey levels, for the tone histogram. */
    fun histogram(gray: IntArray): IntArray {
        val h = IntArray(256)
        for (v in gray) h[v.coerceIn(0, 255)]++
        return h
    }

    // ---------------------------------------------------------------------------------------
    // Dithering
    // ---------------------------------------------------------------------------------------

    /**
     * Reduces a grey plane to a mono plane (`1` = black dot).
     *
     * @param onRow invoked with the row index as the pass proceeds. The pipeline uses it to check
     *   for cancellation; for the diffusion modes it is called every 16 rows, which is frequent
     *   enough to abort promptly and rare enough to cost nothing.
     */
    fun dither(
        gray: IntArray,
        width: Int,
        height: Int,
        mode: DitherMode,
        threshold: Int,
        onRow: ((Int) -> Unit)? = null,
    ): ByteArray {
        require(gray.size >= width * height) { "grey plane too small for ${width}x$height" }
        val t = threshold.coerceIn(1, 254)
        return when (mode) {
            DitherMode.FLOYD_STEINBERG -> diffuse(gray, width, height, t, FLOYD_STEINBERG, onRow)
            DitherMode.ATKINSON -> diffuse(gray, width, height, t, ATKINSON, onRow)
            DitherMode.ORDERED_BAYER_8 -> ordered(gray, width, height, t, onRow)
            // NONE_GRAYSCALE has no 1-bit meaning; a driver must never receive it, and the preview
            // renders the grey plane directly. Falling back to a hard cut preserves the contract
            // that this function ALWAYS returns a printable plane.
            DitherMode.THRESHOLD, DitherMode.NONE_GRAYSCALE ->
                hardCut(gray, width, height, t, onRow)
        }
    }

    /** One error-diffusion neighbour: `dx`, `dy` and a numerator over [Kernel.denominator]. */
    private class Tap(val dx: Int, val dy: Int, val weight: Int)

    private class Kernel(val denominator: Int, val taps: Array<Tap>) {
        /** How many rows below the current one this kernel writes into. */
        val lookahead: Int = taps.maxOf { it.dy }
    }

    /** The classic Floyd-Steinberg kernel: 7/16 right, then 3/16, 5/16, 1/16 on the next row. */
    private val FLOYD_STEINBERG = Kernel(
        denominator = 16,
        taps = arrayOf(
            Tap(1, 0, 7),
            Tap(-1, 1, 3),
            Tap(0, 1, 5),
            Tap(1, 1, 1),
        ),
    )

    /** Atkinson: 1/8 to each of six neighbours, deliberately discarding 2/8 of the error. */
    private val ATKINSON = Kernel(
        denominator = 8,
        taps = arrayOf(
            Tap(1, 0, 1),
            Tap(2, 0, 1),
            Tap(-1, 1, 1),
            Tap(0, 1, 1),
            Tap(1, 1, 1),
            Tap(0, 2, 1),
        ),
    )

    private fun diffuse(
        gray: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        kernel: Kernel,
        onRow: ((Int) -> Unit)?,
    ): ByteArray {
        val out = ByteArray(width * height)
        // Rolling error buffer: only the rows the kernel can still write into are kept, so memory
        // stays O(width) instead of O(width * height) even for a very tall photo.
        val rows = kernel.lookahead + 1
        val err = Array(rows) { IntArray(width) }

        for (y in 0 until height) {
            if (onRow != null && y % ROWS_PER_CANCELLATION_CHECK == 0) onRow(y)
            val cur = err[y % rows]
            val rowBase = y * width
            for (x in 0 until width) {
                val old = (gray[rowBase + x] + cur[x]).coerceIn(-255, 510)
                val black = old < threshold
                out[rowBase + x] = if (black) 1 else 0
                val quantErr = old - if (black) 0 else 255
                if (quantErr != 0) {
                    for (tap in kernel.taps) {
                        val nx = x + tap.dx
                        if (nx < 0 || nx >= width) continue
                        val ny = y + tap.dy
                        if (ny >= height) continue
                        err[ny % rows][nx] += quantErr * tap.weight / kernel.denominator
                    }
                }
            }
            // Recycle the row just consumed as the furthest-ahead row of the rolling buffer.
            java.util.Arrays.fill(cur, 0)
        }
        return out
    }

    /**
     * 8x8 ordered Bayer matrix, values 0..63.
     *
     * Ordered dithering has no error to carry, so it is allocation free and produces a stable
     * texture that does not crawl while the user drags a slider.
     */
    private val BAYER_8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    private fun ordered(
        gray: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        onRow: ((Int) -> Unit)?,
    ): ByteArray {
        val out = ByteArray(width * height)
        // The matrix spans 0..255 with a mean near 128; shifting by (threshold - 128) lets the
        // threshold slider bias an ordered dither exactly as it biases a diffusion one.
        val bias = threshold - 128
        for (y in 0 until height) {
            onRow?.invoke(y)
            val rowBase = y * width
            val matrixRow = (y and 7) shl 3
            for (x in 0 until width) {
                val limit = (BAYER_8[matrixRow or (x and 7)] * 255 / 63) + bias
                out[rowBase + x] = if (gray[rowBase + x] < limit) 1 else 0
            }
        }
        return out
    }

    private fun hardCut(
        gray: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        onRow: ((Int) -> Unit)?,
    ): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            onRow?.invoke(y)
            val rowBase = y * width
            for (x in 0 until width) {
                out[rowBase + x] = if (gray[rowBase + x] < threshold) 1 else 0
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Packing
    // ---------------------------------------------------------------------------------------

    /**
     * Packs a mono plane into MSB-first rows, one set bit per BLACK dot.
     *
     * `byteIndex = y * bytesPerRow + x / 8`, `bitMask = 1 shl (7 - (x % 8))`.
     * This is exactly the layout `GS v 0` and every vendor raster command expects.
     */
    fun packRows(mono: ByteArray, width: Int, height: Int): ByteArray {
        val bpr = bytesPerRow(width)
        val out = ByteArray(bpr * height)
        for (y in 0 until height) {
            val srcBase = y * width
            val dstBase = y * bpr
            for (x in 0 until width) {
                if (mono[srcBase + x].toInt() != 0) {
                    val idx = dstBase + (x shr 3)
                    out[idx] = (out[idx].toInt() or (1 shl (7 - (x and 7)))).toByte()
                }
            }
        }
        return out
    }

    /** Expands a mono plane to opaque black/white ARGB pixels, for the on-screen preview. */
    fun monoToArgb(mono: ByteArray, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (i in out.indices) out[i] = if (mono[i].toInt() != 0) BLACK else WHITE
        return out
    }

    /** Expands a grey plane to opaque ARGB pixels, for the un-dithered preview mode. */
    fun grayToArgb(gray: IntArray): IntArray {
        val out = IntArray(gray.size)
        for (i in gray.indices) {
            val v = gray[i].coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}

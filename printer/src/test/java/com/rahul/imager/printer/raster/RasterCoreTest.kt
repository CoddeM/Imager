package com.rahul.imager.printer.raster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The algorithmic heart of the app: geometry, bit packing, banding and dithering.
 *
 * Every rule tested here has a specific failure mode on real hardware — a width that is not a
 * multiple of 8 shears the print diagonally, a wrong bit order prints a photographic negative,
 * a gap between bands drops a stripe of the picture.
 */
class RasterCoreTest {

    // ---- geometry --------------------------------------------------------------------------

    @Test
    fun `target width is always a multiple of eight`() {
        for (paper in listOf(384, 576, 203, 999)) {
            for (scale in listOf(10, 33, 67, 100)) {
                val width = RasterCore.targetWidthDots(paper, 0, scale, sourceWidthDots = 4000)
                assertEquals("width $width from paper=$paper scale=$scale", 0, width % 8)
                assertTrue(width >= 8)
            }
        }
    }

    @Test
    fun `target width never exceeds the source resolution`() {
        // A 100 px wide photo on an 80 mm head must print 96 dots wide, not 576.
        assertEquals(96, RasterCore.targetWidthDots(576, 0, 100, sourceWidthDots = 100))
    }

    @Test
    fun `margins are taken off both sides`() {
        assertEquals(376, RasterCore.targetWidthDots(576, 100, 100, sourceWidthDots = 4000))
    }

    @Test
    fun `scale reduces the width proportionally`() {
        assertEquals(288, RasterCore.targetWidthDots(576, 0, 50, sourceWidthDots = 4000))
    }

    @Test
    fun `bytes per row rounds up to whole bytes`() {
        assertEquals(1, RasterCore.bytesPerRow(1))
        assertEquals(1, RasterCore.bytesPerRow(8))
        assertEquals(2, RasterCore.bytesPerRow(9))
        assertEquals(48, RasterCore.bytesPerRow(384))
        assertEquals(72, RasterCore.bytesPerRow(576))
    }

    // ---- banding ---------------------------------------------------------------------------

    @Test
    fun `bands cover every row exactly once with no overlap or gap`() {
        val height = 1000
        val bands = RasterCore.bandRanges(height, bandRows = 192)
        val covered = bands.flatMap { it.toList() }
        assertEquals(height, covered.size)
        assertEquals((0 until height).toList(), covered)
        assertEquals(0, bands.first().first)
        assertEquals(height - 1, bands.last().last)
    }

    @Test
    fun `banding handles exact multiples and empty rasters`() {
        assertEquals(2, RasterCore.bandRanges(384, 192).size)
        assertEquals(1, RasterCore.bandRanges(10, 192).size)
        assertTrue(RasterCore.bandRanges(0).isEmpty())
    }

    // ---- packing ---------------------------------------------------------------------------

    @Test
    fun `packing is MSB first with a set bit meaning black`() {
        // Two rows of eight dots: 1000_0001 then 0111_1110.
        val mono = byteArrayOf(
            1, 0, 0, 0, 0, 0, 0, 1,
            0, 1, 1, 1, 1, 1, 1, 0,
        )
        val packed = RasterCore.packRows(mono, width = 8, height = 2)
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x7E), packed)
    }

    @Test
    fun `packing pads the final partial byte of a row with zeros`() {
        // Nine dots per row: the second byte carries one dot and seven pad bits.
        val mono = byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1)
        val packed = RasterCore.packRows(mono, width = 9, height = 1)
        assertEquals(2, packed.size)
        assertEquals(0xFF.toByte(), packed[0])
        assertEquals(0x80.toByte(), packed[1])
    }

    @Test
    fun `an all-white plane packs to all zero bytes`() {
        val packed = RasterCore.packRows(ByteArray(64), width = 8, height = 8)
        assertArrayEquals(ByteArray(8), packed)
    }

    // ---- grayscale and tone ------------------------------------------------------------------

    @Test
    fun `luminance and average weight the channels differently`() {
        val pureGreen = intArrayOf(0xFF00FF00.toInt())
        assertEquals(149, RasterCore.toGrayscale(pureGreen, GrayscaleMode.LUMINANCE)[0])
        assertEquals(85, RasterCore.toGrayscale(pureGreen, GrayscaleMode.AVERAGE)[0])
    }

    @Test
    fun `transparent pixels composite over white paper`() {
        // Fully transparent black must read as white, not as a solid black block.
        val transparent = intArrayOf(0x00000000)
        assertEquals(255, RasterCore.toGrayscale(transparent, GrayscaleMode.LUMINANCE)[0])
    }

    @Test
    fun `the neutral tone curve is the identity`() {
        val lut = RasterCore.toneLut(brightness = 0f, contrast = 0f, gamma = 1f, invert = false)
        for (v in 0..255) assertEquals(v, lut[v])
    }

    @Test
    fun `invert mirrors the curve`() {
        val lut = RasterCore.toneLut(0f, 0f, 1f, invert = true)
        assertEquals(255, lut[0])
        assertEquals(0, lut[255])
    }

    @Test
    fun `gamma moves the midtones the documented way`() {
        // The curve is 255 * (v/255)^(1/gamma): the RECIPROCAL exponent means a larger gamma
        // LIFTS the tone curve (lighter print) and a smaller one darkens it. The UI's Darkness
        // slider inverts this on the way in, which is exactly why the direction is pinned here.
        val neutral = RasterCore.toneLut(0f, 0f, 1f, false)
        val lighter = RasterCore.toneLut(0f, 0f, 2f, false)
        val darker = RasterCore.toneLut(0f, 0f, 0.5f, false)
        assertTrue("gamma 2.0 must lighten mid grey", lighter[128] > neutral[128])
        assertTrue("gamma 0.5 must darken mid grey", darker[128] < neutral[128])
    }

    @Test
    fun `histogram counts every pixel`() {
        val gray = intArrayOf(0, 0, 128, 255)
        val histogram = RasterCore.histogram(gray)
        assertEquals(2, histogram[0])
        assertEquals(1, histogram[128])
        assertEquals(1, histogram[255])
        assertEquals(gray.size, histogram.sum())
    }

    // ---- dithering ---------------------------------------------------------------------------

    @Test
    fun `threshold mode is an exact hard cut`() {
        val gray = intArrayOf(0, 127, 128, 129, 255)
        val mono = RasterCore.dither(gray, width = 5, height = 1, DitherMode.THRESHOLD, 128)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0), mono)
    }

    @Test
    fun `every dither mode returns one value per pixel and only zero or one`() {
        val gray = IntArray(64) { it * 4 }
        for (mode in DitherMode.entries) {
            val mono = RasterCore.dither(gray, width = 8, height = 8, mode, 128)
            assertEquals("mode $mode", 64, mono.size)
            assertTrue("mode $mode produced a value other than 0/1", mono.all { it.toInt() in 0..1 })
        }
    }

    @Test
    fun `Floyd-Steinberg on a fixed gradient is deterministic and balanced`() {
        val width = 16
        val height = 16
        // A left-to-right ramp: the left edge is black, the right edge is white, and error
        // diffusion has to land somewhere near half coverage overall.
        val gray = IntArray(width * height) { index -> (index % width) * 255 / (width - 1) }

        val first = RasterCore.dither(gray, width, height, DitherMode.FLOYD_STEINBERG, 128)
        val second = RasterCore.dither(gray, width, height, DitherMode.FLOYD_STEINBERG, 128)
        assertArrayEquals("the same input must always produce the same dots", first, second)

        // The darkest column is fully black and the lightest fully white; the whole ramp averages
        // out to roughly half the dots being black.
        for (y in 0 until height) {
            assertEquals("leftmost column", 1, first[y * width].toInt())
            assertEquals("rightmost column", 0, first[y * width + width - 1].toInt())
        }
        val blackFraction = first.count { it.toInt() == 1 }.toFloat() / first.size
        assertTrue("black coverage was $blackFraction", blackFraction in 0.35f..0.65f)
    }

    @Test
    fun `Atkinson is lighter than Floyd-Steinberg on the same midtone`() {
        // Atkinson deliberately discards 2/8 of the error, which shows as less ink on flat grey.
        val gray = IntArray(64 * 64) { 140 }
        val floyd = RasterCore.dither(gray, 64, 64, DitherMode.FLOYD_STEINBERG, 128)
        val atkinson = RasterCore.dither(gray, 64, 64, DitherMode.ATKINSON, 128)
        assertTrue(atkinson.count { it.toInt() == 1 } <= floyd.count { it.toInt() == 1 })
    }

    @Test
    fun `ordered dithering produces a repeating 8x8 texture`() {
        val gray = IntArray(16 * 16) { 128 }
        val mono = RasterCore.dither(gray, 16, 16, DitherMode.ORDERED_BAYER_8, 128)
        // The Bayer matrix tiles, so (x, y) and (x + 8, y + 8) must agree.
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(
                    "tile mismatch at ($x, $y)",
                    mono[y * 16 + x],
                    mono[(y + 8) * 16 + x + 8],
                )
            }
        }
    }

    @Test
    fun `a solid black plane dithers to all black and a solid white plane to all white`() {
        val black = IntArray(256)
        val white = IntArray(256) { 255 }
        for (mode in listOf(DitherMode.FLOYD_STEINBERG, DitherMode.ATKINSON, DitherMode.THRESHOLD)) {
            assertTrue(RasterCore.dither(black, 16, 16, mode, 128).all { it.toInt() == 1 })
            assertTrue(RasterCore.dither(white, 16, 16, mode, 128).all { it.toInt() == 0 })
        }
    }

    @Test
    fun `the row hook is called while dithering`() {
        var rows = 0
        RasterCore.dither(IntArray(32 * 32), 32, 32, DitherMode.THRESHOLD, 128) { rows++ }
        assertEquals(32, rows)
    }

    // ---- preview conversion --------------------------------------------------------------

    @Test
    fun `mono converts to opaque black and white pixels`() {
        val argb = RasterCore.monoToArgb(byteArrayOf(1, 0), width = 2, height = 1)
        assertEquals(0xFF000000.toInt(), argb[0])
        assertEquals(0xFFFFFFFF.toInt(), argb[1])
    }

    @Test
    fun `grey converts to opaque neutral pixels`() {
        val argb = RasterCore.grayToArgb(intArrayOf(0, 128, 255))
        assertEquals(0xFF000000.toInt(), argb[0])
        assertEquals(0xFF808080.toInt(), argb[1])
        assertEquals(0xFFFFFFFF.toInt(), argb[2])
    }
}
